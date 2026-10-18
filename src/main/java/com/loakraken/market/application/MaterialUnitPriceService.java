package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.ClientLimiter;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MaterialPriceBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 재료 이름으로 거래소 현재 판매 목록에서 1개당 골드를 찾는다. 운명의 파편은 주머니 시세로 환산한다.
 *
 * <p><b>예열 캐시 먼저</b>: 재련 재료 스캔(T4 재련 재료·추가 재료)과 생활 스캔(생활 분류 전체)에 정확히 같은 이름이 있으면 그 값을 쓰고 공식 호출을 하지
 * 않는다. 둘 다 예열이 사용자 몫을 남기며 주기적으로 채운다.
 *
 * <p><b>캐시에 없을 때</b>: 강화 재료(50000)에서 먼저 찾고, 정확히 같은 이름이 없으면 생활(90000)에서 찾는다. 두 코드는 공식 거래소
 * options(2026-10-14 확인)의 상위 분류다. 이름 검색은 부분 일치라 정확한 이름이 1페이지에 없으면 몇 페이지 더 본다. 한 번 찾은 이름은 그 분류부터
 * 찾는다.
 */
@Service
public class MaterialUnitPriceService {
  public static final String SHARD = "운명의 파편";
  static final int MATERIAL_CATEGORY = 50000;
  static final int LIFE_CATEGORY = 90000;
  static final int BATTLE_ITEM_CATEGORY = 60000;

  /** 검색 순서. 세 코드는 공식 거래소 options의 상위 분류다(60000 전투 용품은 2026-10-14 배틀 아이템 확인). */
  static final List<Integer> CATEGORIES =
      List.of(MATERIAL_CATEGORY, LIFE_CATEGORY, BATTLE_ITEM_CATEGORY);

  /** 예열이 분류 전체를 받는 분류(생활·전투 용품). 다 받은 분류는 스캔에 없는 이름을 다시 검색하지 않는다. */
  public static final List<Integer> SCANNED_CATEGORIES =
      List.of(LIFE_CATEGORY, BATTLE_ITEM_CATEGORY);

  /** 끝까지 찾았는데 없던 이름을 다시 검색하지 않는 시간(음성 캐시). */
  static final java.time.Duration NEGATIVE_TTL = java.time.Duration.ofHours(1);

  /** 공식 거래소에서 사고팔 수 없는 품목(데이터 파일, 출처는 파일 안). 검색하지 않고 UNTRADEABLE이다. */
  static final java.util.Set<String> UNTRADEABLE = untradeable();

  private static java.util.Set<String> untradeable() {
    try (var input =
        MaterialUnitPriceService.class.getResourceAsStream("/market/untradeable-items.json")) {
      if (input == null) throw new IllegalStateException("거래 불가 품목 파일이 없습니다.");
      var node = new tools.jackson.databind.json.JsonMapper().readTree(input);
      var names = new java.util.LinkedHashSet<String>();
      for (var item : node.path("items")) names.add(item.asString());
      return java.util.Set.copyOf(names);
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  /** 정확한 이름을 찾을 때 한 분류에서 보는 최대 페이지(페이지당 10개). */
  static final int MAX_PAGES = 3;

  /**
   * 재료 시세 API 한 요청이 캐시에 없는 이름 때문에 일으킬 수 있는 거래소 검색 수 상한. 이름 20개 × 분류 2 + 이름이 겹쳐 밀린 페이지 몇 개(예열 전 첫
   * 요청에 제작 재료 20개가 약 39번).
   */
  static final int MAX_REQUEST_SEARCHES = 45;

  /**
   * 재료 검색 중에도 캐릭터 검색 등 사용자 호출을 위해 공유 예산(분당 90)에 늘 남겨 두는 몫. 게이트는 '호출 뒤에도 이만큼 남는지'로 허가한다. 수집기·예열은 40을
   * 남기고 멈추므로(분당 최대 50) 재료 검색은 그 위에서도 전체 70까지 쓸 수 있고, 그때도 20은 사용자 호출에 남는다. 재료 검색 자신의 상한(분당 36, 모든
   * 클라이언트 합계)은 클라이언트 한도의 MATERIAL_SEARCH_ALL 버킷이 따로 센다(보안 점검 10-18, PM 판정).
   */
  static final int CHARACTER_SEARCH_RESERVE = 20;

  /** 분류 전체 예열 스캔의 검색 조건(이름 없음, 등급순). */
  public static MarketSearch categorySearch(int category, int page) {
    return new MarketSearch(
        category, "", null, "", "", page, MarketSearch.Sort.GRADE, MarketSearch.SortCondition.ASC);
  }

  private static final int MAX_REMEMBERED = 2000;
  private static final String SHARD_POUCH_SEARCH = "운명의 파편 주머니";
  // 공식 거래소 상세 Tooltip "사용 시, N 운명의 파편을 획득할 수 있다."(2026-10-05 확인)
  static final Map<String, Integer> SHARD_POUCHES =
      Map.of(
          "운명의 파편 주머니(소)", 1000,
          "운명의 파편 주머니(중)", 2000,
          "운명의 파편 주머니(대)", 3000);

  private final MarketQueryService markets;
  private final MarketCache cache;
  private final LostArkRequestGate gate;
  private final Clock clock;
  // 이름 → 찾은 분류(공식 호출을 줄이는 힌트일 뿐, 틀리면 다른 분류도 본다).
  private final Map<String, Integer> foundIn = new java.util.concurrent.ConcurrentHashMap<>();
  // 이름 → 끝까지 찾았는데 없던 시각(음성 캐시, NEGATIVE_TTL 동안 다시 검색하지 않음).
  private final Map<String, Instant> notFoundAt = new java.util.concurrent.ConcurrentHashMap<>();

  public MaterialUnitPriceService(
      MarketQueryService markets, MarketCache cache, LostArkRequestGate gate, Clock clock) {
    this.markets = markets;
    this.cache = cache;
    this.gate = gate;
    this.clock = clock;
  }

  /**
   * unavailableReason: PRICE_UNAVAILABLE 가운데 '지금 조회하지 못한' 이유(열린 문자열). RATE_LIMITED = 재료 검색
   * 한도(클라이언트·전체)나 공유 호출 예산에 걸려 부르지 않음(잠시 뒤 다시), SEARCH_LIMIT = 요청당 검색 상한, OFFICIAL_UNAVAILABLE = 공식
   * 서버 장애. 시세를 읽었는데 가격이 없거나 0이면 null.
   */
  public record UnitPrice(
      String name,
      Status status,
      BigDecimal unitPriceGold,
      String sourceItemName,
      Integer bundleCount,
      BigDecimal bundlePrice,
      Integer contentsPerItem,
      Instant observedAt,
      String unavailableReason) {
    public UnitPrice(
        String name,
        Status status,
        BigDecimal unitPriceGold,
        String sourceItemName,
        Integer bundleCount,
        BigDecimal bundlePrice,
        Integer contentsPerItem,
        Instant observedAt) {
      this(
          name,
          status,
          unitPriceGold,
          sourceItemName,
          bundleCount,
          bundlePrice,
          contentsPerItem,
          observedAt,
          null);
    }

    public enum Status {
      PRICED,
      NOT_FOUND,
      PRICE_UNAVAILABLE,
      UNTRADEABLE
    }
  }

  /** 서버 안 경로(장비 영수증 등). 예열 캐시에 없는 이름은 거래소를 검색하고, 그 공식 호출은 지금 요청 클라이언트의 공식 호출 한도에 하나씩 매긴다. */
  public List<UnitPrice> prices(MaterialPriceBasis basis, List<String> names) {
    var scans = scans();
    var result = new ArrayList<UnitPrice>();
    for (var name : new LinkedHashSet<>(names)) {
      var known = known(basis, name, scans);
      result.add(known != null ? known : lookup(basis, name, new int[] {Integer.MAX_VALUE}, scans));
    }
    return result;
  }

  /** 검색 없이 답할 수 있으면 그 값: 거래 불가 → 예열 스캔 → 음성 캐시. 없으면 null(검색 필요). */
  private UnitPrice known(MaterialPriceBasis basis, String name, Scans scans) {
    if (UNTRADEABLE.contains(name))
      return new UnitPrice(
          name, UnitPrice.Status.UNTRADEABLE, null, null, null, null, null, clock.instant());
    var cached = fromScan(basis, name, scans.items());
    if (cached != null) return cached;
    var missing = notFoundAt.get(name);
    if (missing != null) {
      if (missing.isAfter(clock.instant().minus(NEGATIVE_TTL)))
        return new UnitPrice(
            name, UnitPrice.Status.NOT_FOUND, null, null, null, null, null, missing);
      notFoundAt.remove(name, missing);
    }
    return null;
  }

  /**
   * 재료 시세 API. 예열 캐시에 있는 이름은 공식 호출 없이 답한다. 캐시에 없는 이름이 있으면 그 요청을 MATERIAL_PRICES 버킷에 <b>한 번</b> 매기고,
   * 검색은 요청당 MAX_REQUEST_SEARCHES번까지, 공식 호출마다 요청 클라이언트의 MATERIAL_SEARCH 버킷(분당 15, 공식 호출 한도 OFFICIAL과
   * 따로)에 매기고, 모든 클라이언트 합계 MATERIAL_SEARCH_ALL(분당 36)에도 매기며, 공유 예산에는 캐릭터 검색 몫
   * CHARACTER_SEARCH_RESERVE(20)를 남긴다. 상한·클라이언트 버킷·공유 예산에 걸린 이름부터는 PRICE_UNAVAILABLE이다(429가 아님).
   */
  public List<UnitPrice> requestPrices(MaterialPriceBasis basis, List<String> names) {
    var scans = scans();
    var result = new LinkedHashMap<String, UnitPrice>();
    var misses = new ArrayList<String>();
    for (var name : new LinkedHashSet<>(names)) {
      var cached = known(basis, name, scans);
      result.put(name, cached);
      if (cached == null) misses.add(name);
    }
    if (!misses.isEmpty()) {
      gate.chargeClient(ClientLimiter.Bucket.MATERIAL_PRICES);
      var budget = new int[] {MAX_REQUEST_SEARCHES};
      gate.withClientBucket(
          CHARACTER_SEARCH_RESERVE,
          ClientLimiter.Bucket.MATERIAL_SEARCH,
          ClientLimiter.Bucket.MATERIAL_SEARCH_ALL,
          () -> {
            String stopped = null;
            for (var name : misses) {
              if (stopped != null) {
                result.put(name, unavailable(name, stopped));
                continue;
              }
              try {
                result.put(name, lookup(basis, name, budget, scans));
              } catch (BusinessException exception) {
                // 재료 검색 한도(클라이언트·전체)·공유 예산·공식 장애: 이 이름부터 나머지는 지금 알 수 없음으로 둔다(캐시에서 답한 이름은 그대로).
                stopped = reason(exception);
                result.put(name, unavailable(name, stopped));
              }
            }
            return null;
          });
    }
    return List.copyOf(result.values());
  }

  static final String RATE_LIMITED = "RATE_LIMITED";
  static final String SEARCH_LIMIT = "SEARCH_LIMIT";
  static final String OFFICIAL_UNAVAILABLE = "OFFICIAL_UNAVAILABLE";

  /** 부르지 못한 이유: 한도·예산이면 RATE_LIMITED, 그 밖(공식 장애·관문 저장소 장애)은 OFFICIAL_UNAVAILABLE. */
  static String reason(BusinessException exception) {
    var code = exception.errorCode();
    return code == com.loakraken.common.lostark.LostArkErrorCode.CLIENT_RATE_LIMITED
            || code == com.loakraken.common.lostark.LostArkErrorCode.LOST_ARK_RATE_LIMITED
        ? RATE_LIMITED
        : OFFICIAL_UNAVAILABLE;
  }

  private UnitPrice unavailable(String name, String reason) {
    return new UnitPrice(
        name,
        UnitPrice.Status.PRICE_UNAVAILABLE,
        null,
        null,
        null,
        null,
        null,
        clock.instant(),
        reason);
  }

  /** 예열 스캔(재련 재료, 생활·전투 용품). 읽지 못하면 없는 것으로 둔다. */
  private record Scan(Instant observedAt, List<MarketPrice> items) {}

  /** complete: 다 받은 분류(이 분류는 스캔에 없는 이름을 검색하지 않는다). */
  private record Scans(List<Scan> items, java.util.Set<Integer> complete) {}

  private Scans scans() {
    var scans = new ArrayList<Scan>();
    var complete = new java.util.HashSet<Integer>();
    try {
      cache
          .findRefiningMaterials()
          .ifPresent(scan -> scans.add(new Scan(scan.observedAt(), scan.items())));
    } catch (RuntimeException ignored) {
      // 캐시를 읽지 못하면 검색으로 넘어간다.
    }
    for (var category : SCANNED_CATEGORIES)
      try {
        cache
            .findLifeMaterials(category)
            .ifPresent(
                scan -> {
                  scans.add(new Scan(scan.observedAt(), scan.items()));
                  complete.addAll(scan.completeCategories());
                });
      } catch (RuntimeException ignored) {
        // 캐시를 읽지 못하면 검색으로 넘어간다.
      }
    return new Scans(scans, java.util.Set.copyOf(complete));
  }

  /** 예열 스캔에 정확히 같은 이름(파편은 주머니)이 있으면 그 값. 없으면 null. */
  private static UnitPrice fromScan(MaterialPriceBasis basis, String name, List<Scan> scans) {
    for (var scan : scans) {
      if (SHARD.equals(name)) {
        var pouches =
            scan.items().stream().filter(item -> SHARD_POUCHES.containsKey(item.name())).toList();
        if (!pouches.isEmpty()) return shard(basis, pouches, scan.observedAt());
        continue;
      }
      var exact = scan.items().stream().filter(item -> item.name().equals(name)).toList();
      if (!exact.isEmpty()) return material(basis, name, exact, scan.observedAt());
    }
    return null;
  }

  /** 정확히 같은 이름의 판매 목록과 그 조회 시각. 못 찾으면 items가 비고 시각은 마지막 조회 시각. */
  private record Exact(List<MarketPrice> items, Instant observedAt) {}

  private UnitPrice lookup(MaterialPriceBasis basis, String name, int[] budget, Scans scans) {
    if (SHARD.equals(name)) {
      if (budget[0] <= 0) return unavailable(name, SEARCH_LIMIT);
      budget[0]--;
      var page = search(MATERIAL_CATEGORY, SHARD_POUCH_SEARCH, 1);
      return shard(basis, page.page().items(), page.observedAt());
    }
    var found = exact(name, budget, scans.complete());
    if (found == null) return unavailable(name, SEARCH_LIMIT);
    var observedAt = found.observedAt() == null ? clock.instant() : found.observedAt();
    if (found.items().isEmpty()
        && (notFoundAt.size() < MAX_REMEMBERED || notFoundAt.containsKey(name)))
      // 모든 분류를 끝까지 봤는데 없다 → 음성 캐시(NEGATIVE_TTL 동안 다시 검색하지 않음).
      notFoundAt.put(name, observedAt);
    return material(basis, name, found.items(), observedAt);
  }

  /** 검색 상한(budget)에 걸려 끝까지 보지 못하면 null. 다 받은 예열 분류(complete)는 이미 스캔에 없다는 것을 알았으므로 검색하지 않는다. */
  private Exact exact(String name, int[] budget, java.util.Set<Integer> complete) {
    var known = foundIn.get(name);
    var order = new ArrayList<Integer>();
    if (known != null) order.add(known);
    for (var category : CATEGORIES) if (!order.contains(category)) order.add(category);
    order.removeAll(complete);
    Instant observedAt = null;
    for (var category : order) {
      for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
        if (budget[0] <= 0) return null;
        budget[0]--;
        var result = search(category, name, pageNo);
        observedAt = result.observedAt();
        var exact =
            result.page().items().stream().filter(item -> item.name().equals(name)).toList();
        if (!exact.isEmpty()) {
          if (foundIn.size() < MAX_REMEMBERED || foundIn.containsKey(name))
            foundIn.put(name, category);
          return new Exact(exact, observedAt);
        }
        if ((long) pageNo * result.page().pageSize() >= result.page().totalCount()) break;
      }
    }
    return new Exact(List.of(), observedAt);
  }

  private static UnitPrice material(
      MaterialPriceBasis basis, String name, List<MarketPrice> exact, Instant observedAt) {
    if (exact.isEmpty())
      return new UnitPrice(
          name, UnitPrice.Status.NOT_FOUND, null, null, null, null, null, observedAt);
    MarketPrice best = null;
    BigDecimal bestUnit = null;
    for (var item : exact) {
      var unit = unit(basis.select(item), item.bundleCount(), 1);
      if (unit != null && (bestUnit == null || unit.compareTo(bestUnit) < 0)) {
        best = item;
        bestUnit = unit;
      }
    }
    if (best == null)
      return new UnitPrice(
          name,
          UnitPrice.Status.PRICE_UNAVAILABLE,
          null,
          exact.getFirst().name(),
          exact.getFirst().bundleCount(),
          null,
          null,
          observedAt);
    return new UnitPrice(
        name,
        UnitPrice.Status.PRICED,
        bestUnit,
        best.name(),
        best.bundleCount(),
        basis.select(best),
        null,
        observedAt);
  }

  private static UnitPrice shard(
      MaterialPriceBasis basis, List<MarketPrice> items, Instant observedAt) {
    MarketPrice best = null;
    BigDecimal bestUnit = null;
    for (var item : items) {
      var contents = SHARD_POUCHES.get(item.name());
      if (contents == null) continue;
      var unit = unit(basis.select(item), item.bundleCount(), contents);
      if (unit != null && (bestUnit == null || unit.compareTo(bestUnit) < 0)) {
        best = item;
        bestUnit = unit;
      }
    }
    if (best == null)
      return new UnitPrice(
          SHARD, UnitPrice.Status.PRICE_UNAVAILABLE, null, null, null, null, null, observedAt);
    return new UnitPrice(
        SHARD,
        UnitPrice.Status.PRICED,
        bestUnit,
        best.name(),
        best.bundleCount(),
        basis.select(best),
        SHARD_POUCHES.get(best.name()),
        observedAt);
  }

  /** 예열용: 예열 분류(생활·전투 용품) 한 페이지를 공식 API에서 새로 받는다. */
  public MarketResult fetchCategoryPage(int category, int page) {
    return markets.refreshSearch(MarketSource.ACTIVE_LISTING, categorySearch(category, page));
  }

  /** 예열용: 다 모은 생활·전투 용품 스캔을 저장한다. ttl은 예열 주기보다 넉넉하게 준다. */
  public void saveCategoryScan(int category, LifeMaterialScan scan, java.time.Duration ttl) {
    cache.saveLifeMaterials(category, scan, ttl);
  }

  private MarketResult search(int category, String itemName, int pageNo) {
    return markets.search(
        MarketSource.ACTIVE_LISTING,
        new MarketSearch(
            category,
            "",
            null,
            "",
            itemName,
            pageNo,
            MarketSearch.Sort.RECENT_PRICE,
            MarketSearch.SortCondition.ASC));
  }

  private static BigDecimal unit(BigDecimal bundlePrice, int bundleCount, int contents) {
    if (bundlePrice == null || bundlePrice.signum() <= 0) return null;
    return bundlePrice.divide(
        BigDecimal.valueOf((long) bundleCount * contents), 6, RoundingMode.HALF_UP);
  }
}
