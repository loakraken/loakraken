package com.loakraken.market.application;

import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import com.loakraken.market.domain.MarketVolume;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 시세 보드. 기본·고가·저가는 공식 정렬 한 페이지(검색 캐시 공유), 상승·하락은 탭 전체를 순차 스캔해 등락률로 정렬한다.
 *
 * <p>전체 스캔은 탭+티어 단위로 캐시하고 같은 스캔 동시 요청을 합친다. 페이지 상한(MAX_SCAN_PAGES)을 넘는 탭은 complete=false다.
 */
@Service
public class MarketBoardService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(MarketBoardService.class);
  public static final int PAGE_SIZE = 10;
  public static final int MAX_SCAN_PAGES = 10;

  /**
   * 거래량을 찾는 지난 지도 일수. 돌림 탭(아바타)은 한 바퀴에 약 5일이 걸려 그 사이 값을 그 volumeDate와 함께 보인다(날짜가 오래된 값임을 화면이 보임).
   */
  public static final int VOLUME_LOOKBACK_DAYS = 7;

  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final MarketQueryService markets;
  private final MarketCache cache;
  private final Clock clock;
  private final ConcurrentHashMap<ScanKey, CompletableFuture<MarketBoardScan>> scans =
      new ConcurrentHashMap<>();
  // 서로 다른 탭·티어 스캔이 겹쳐 공유 호출 예산을 몰아 쓰지 않도록 전체 스캔은 한 번에 하나만 실행한다.
  private final ReentrantLock scanLock = new ReentrantLock(true);

  public MarketBoardService(MarketQueryService markets, MarketCache cache) {
    this(markets, cache, Clock.systemUTC());
  }

  @Autowired
  public MarketBoardService(MarketQueryService markets, MarketCache cache, Clock clock) {
    this.markets = markets;
    this.cache = cache;
    this.clock = clock;
  }

  public MarketBoard board(MarketTab tab, Integer tier, MarketBoardRules.Sort sort, int page) {
    return board(tab, tier, sort, page, null);
  }

  /**
   * query(정리·검증한 검색어)가 있으면 상승·하락 정렬과 같은 탭 전체 스캔(같은 캐시·합치기·한 번에 하나)에서 이름·초성으로 거른 뒤 정렬·페이지를
   * 적용한다(#619). totalItems는 걸러진 품목 수, scannedItems는 스캔한 품목 수, complete는 스캔이 탭 전체를 덮었는지다.
   */
  public MarketBoard board(
      MarketTab tab, Integer tier, MarketBoardRules.Sort sort, int page, String query) {
    Integer effectiveTier = tier == null ? tab.defaultTier() : tier;
    if (query != null) {
      var scan = scan(tab, effectiveTier);
      var matched =
          scan.items().stream()
              .filter(item -> MarketBoardRules.nameMatches(item.name(), query))
              .toList();
      var volumes =
          sort == MarketBoardRules.Sort.VOLUME
              ? volumes(ids(matched))
              : Map.<Long, MarketVolume>of();
      var sorted = sortScanned(matched, sort, volumes);
      int from = Math.min(sorted.size(), (page - 1) * PAGE_SIZE);
      int to = Math.min(sorted.size(), from + PAGE_SIZE);
      return new MarketBoard(
          tab,
          effectiveTier,
          sort,
          page,
          scan.observedAt(),
          sorted.size(),
          scan.items().size(),
          scan.complete(),
          rows(sorted.subList(from, to), volumes));
    }
    if (!sort.scansWholeTab()) {
      var result =
          markets.search(MarketSource.ACTIVE_LISTING, search(tab, effectiveTier, sort, page));
      var items = result.page().items();
      return new MarketBoard(
          tab,
          effectiveTier,
          sort,
          page,
          result.observedAt(),
          result.page().totalCount(),
          items.size(),
          true,
          rows(items, Map.of()));
    }
    var scan = scan(tab, effectiveTier);
    var volumes =
        sort == MarketBoardRules.Sort.VOLUME
            ? volumes(ids(scan.items()))
            : Map.<Long, MarketVolume>of();
    var sorted =
        sort == MarketBoardRules.Sort.VOLUME
            ? MarketBoardRules.sortByVolume(scan.items(), volumes)
            : MarketBoardRules.sortByChange(scan.items(), sort);
    int from = Math.min(sorted.size(), (page - 1) * PAGE_SIZE);
    int to = Math.min(sorted.size(), from + PAGE_SIZE);
    return new MarketBoard(
        tab,
        effectiveTier,
        sort,
        page,
        scan.observedAt(),
        scan.totalItems(),
        scan.items().size(),
        scan.complete(),
        rows(sorted.subList(from, to), volumes));
  }

  /**
   * 스캔한 품목의 정렬. 기본은 스캔 순서(공식 등급순 페이지 순서), 고가·저가는 현재 최저가(없으면 맨 뒤, 같으면 스캔 순서), 상승·하락은 등락률 정렬, 거래량은
   * 거래량 정렬(volumes)과 같다.
   */
  static List<MarketPrice> sortScanned(
      List<MarketPrice> items, MarketBoardRules.Sort sort, Map<Long, MarketVolume> volumes) {
    return switch (sort) {
      case DEFAULT -> items;
      case HIGH, LOW -> {
        java.util.Comparator<java.math.BigDecimal> byPrice =
            sort == MarketBoardRules.Sort.HIGH
                ? java.util.Comparator.reverseOrder()
                : java.util.Comparator.naturalOrder();
        yield items.stream()
            .sorted(
                java.util.Comparator.comparing(
                    MarketPrice::currentMinPrice, java.util.Comparator.nullsLast(byPrice)))
            .toList();
      }
      case RISE, FALL -> MarketBoardRules.sortByChange(items, sort);
      case VOLUME -> MarketBoardRules.sortByVolume(items, volumes);
    };
  }

  /**
   * 한 요청에서 새로 스캔할 수 있는 (탭, 티어) 묶음 수. 묶음마다 공식 검색이 최대 MAX_SCAN_PAGES(10)번이라 2묶음 = 최대 20번으로 방문자 한도(분당
   * 20) 안이다.
   */
  public static final int MAX_COLD_GROUPS = 2;

  public static final int MAX_PRICE_ITEMS = 50;

  /** 묶음 시세 한 칸: 공식 상품 ID와 그 품목이 있는 탭·티어(null이면 탭 기본 티어). */
  public record PriceRequest(long itemId, MarketTab tab, Integer tier) {}

  /** READY = 값 있음, PENDING = 이번 요청에서 그 탭 스캔을 하지 못함(다음 갱신 때), NOT_FOUND = 그 탭 스캔에 없음. */
  public enum PriceStatus {
    READY,
    PENDING,
    NOT_FOUND
  }

  /** price·changeDay는 READY일 때만 있다. observedAt은 그 탭 스캔 시각(READY·NOT_FOUND). */
  public record ItemPrice(
      long itemId,
      PriceStatus status,
      MarketPrice price,
      BigDecimal changeDay,
      Instant observedAt) {}

  /**
   * 즐겨찾기 묶음 시세. (탭, 티어)마다 상승·하락 정렬과 같은 탭 전체 스캔(같은 캐시·합치기·한 번에 하나)에서 찾는다. 캐시에 없는 묶음은 요청당
   * MAX_COLD_GROUPS개까지만 새로 스캔하고, 한도·공식 장애나 그 밖의 오류로 스캔하지 못하면 그 묶음과 뒤의 새 묶음은 PENDING이다(500 대신). 같은
   * itemId는 처음 것만, 요청 순서대로 준다.
   */
  public List<ItemPrice> prices(List<PriceRequest> requests) {
    var scans = new java.util.HashMap<ScanKey, Optional<MarketBoardScan>>();
    int cold = 0;
    boolean stopped = false;
    for (var request : requests) {
      var key = new ScanKey(request.tab(), effectiveTier(request.tab(), request.tier()));
      if (scans.containsKey(key)) continue;
      var cached = cache.findBoardScan(key.tab(), key.tier());
      if (cached.isPresent()) {
        scans.put(key, cached);
        continue;
      }
      if (stopped || cold >= MAX_COLD_GROUPS) {
        scans.put(key, Optional.empty());
        continue;
      }
      cold++;
      try {
        scans.put(key, Optional.of(scan(key.tab(), key.tier())));
      } catch (com.loakraken.common.api.BusinessException exception) {
        stopped = true;
        scans.put(key, Optional.empty());
      } catch (RuntimeException exception) {
        // 예상하지 못한 오류도 이 요청의 나머지 값(캐시 묶음)은 돌려주고 그 묶음만 PENDING으로 둔다. 원인은 로그로 남긴다.
        log.warn(
            "event=market_item_prices_scan_failed tab={} tier={} exceptionType={}",
            key.tab(),
            key.tier(),
            exception.getClass().getName());
        stopped = true;
        scans.put(key, Optional.empty());
      }
    }
    var result = new ArrayList<ItemPrice>();
    var seen = new java.util.HashSet<Long>();
    for (var request : requests) {
      if (!seen.add(request.itemId())) continue;
      var scan =
          scans.get(new ScanKey(request.tab(), effectiveTier(request.tab(), request.tier())));
      if (scan.isEmpty()) {
        result.add(new ItemPrice(request.itemId(), PriceStatus.PENDING, null, null, null));
        continue;
      }
      var item =
          scan.get().items().stream().filter(price -> price.id() == request.itemId()).findFirst();
      result.add(
          item.map(
                  price ->
                      new ItemPrice(
                          request.itemId(),
                          PriceStatus.READY,
                          price,
                          MarketBoardRules.changeDay(price),
                          scan.get().observedAt()))
              .orElseGet(
                  () ->
                      new ItemPrice(
                          request.itemId(),
                          PriceStatus.NOT_FOUND,
                          null,
                          null,
                          scan.get().observedAt())));
    }
    return List.copyOf(result);
  }

  private static Integer effectiveTier(MarketTab tab, Integer tier) {
    return tier == null ? tab.defaultTier() : tier;
  }

  /**
   * 거래량 채우기 작업 전용: 탭 기본 티어의 전체 스캔(보드 상승·하락·거래량 정렬과 같은 캐시·합치기·한 번에 하나). 캐시에 없으면 공식 검색을 최대
   * MAX_SCAN_PAGES번 부른다.
   */
  public MarketBoardScan scanForVolume(MarketTab tab) {
    return scan(tab, tab.defaultTier());
  }

  /**
   * 거래량 채우기 돌림 탭 전용: 탭 기본 티어·기본 정렬의 page번째 페이지(공식 검색 1회, 캐시 없음). 보드 스캔의 10페이지 상한과 상관없이 끝까지 받을 수 있다.
   */
  public MarketPage volumePage(MarketTab tab, int page) {
    return markets
        .search(
            MarketSource.ACTIVE_LISTING,
            search(tab, tab.defaultTier(), MarketBoardRules.Sort.DEFAULT, page))
        .page();
  }

  /**
   * 품목들의 완료일 거래량. 오늘(KST) 거래량 지도에서 찾고, 없는 품목은 하루씩 거슬러 최근 VOLUME_LOOKBACK_DAYS일 지도에서 찾는다(자정 뒤 채우기
   * 전까지 어제 값, 돌림 탭은 며칠 전 값을 그 volumeDate와 함께 보여 준다). 그래도 없으면 빠진다.
   */
  public Map<Long, MarketVolume> volumes(Collection<Long> itemIds) {
    if (itemIds.isEmpty()) return Map.of();
    LocalDate today = LocalDate.now(clock.withZone(KST));
    var result = new LinkedHashMap<Long, MarketVolume>(cache.findVolumes(today, itemIds));
    for (int back = 1; back <= VOLUME_LOOKBACK_DAYS; back++) {
      var missing = itemIds.stream().filter(id -> !result.containsKey(id)).distinct().toList();
      if (missing.isEmpty()) break;
      cache.findVolumes(today.minusDays(back), missing).forEach(result::putIfAbsent);
    }
    return result;
  }

  /**
   * 상세 이력에서 그 품목(묶음·거래 가능 횟수가 맞는 변형)의 완료일 거래량. volumeDate = 가장 최근 완료일, yesterday = 그날 거래량, sevenDay
   * = 최근 완료일 최대 7일 합. 맞는 변형이나 완료일이 없으면 null.
   */
  public static MarketVolume volumeOf(
      MarketItemHistory history, MarketPrice item, LocalDate today) {
    if (history == null) return null;
    var variant = variant(history, item);
    if (variant == null) return null;
    var complete = variant.completeDays(today);
    if (complete.isEmpty()) return null;
    var week = complete.subList(0, Math.min(MarketItemHistory.SEVEN_DAYS, complete.size()));
    return new MarketVolume(
        item.id(),
        complete.getFirst().date(),
        complete.getFirst().tradeCount(),
        week.stream().mapToLong(MarketItemHistory.DailyStat::tradeCount).sum());
  }

  private static List<Long> ids(List<MarketPrice> items) {
    return items.stream().map(MarketPrice::id).toList();
  }

  /**
   * 탭의 기본 보드(defaultTier, 기본 정렬, 1페이지)를 캐시를 건너뛰고 다시 받아 저장한다. 예열 전용이다. 홈 요약과 시세 기본 탭이 이 키를 같이 쓴다.
   */
  public MarketResult refreshDefaultPage(MarketTab tab) {
    return markets.refreshSearch(
        MarketSource.ACTIVE_LISTING,
        search(tab, tab.defaultTier(), MarketBoardRules.Sort.DEFAULT, 1));
  }

  private MarketBoardScan scan(MarketTab tab, Integer tier) {
    var cached = cache.findBoardScan(tab, tier);
    if (cached.isPresent()) return cached.get();
    var key = new ScanKey(tab, tier);
    var future = new CompletableFuture<MarketBoardScan>();
    var existing = scans.putIfAbsent(key, future);
    if (existing != null) return join(existing);
    try {
      var result =
          cache
              .findBoardScan(tab, tier)
              .orElseGet(
                  () -> {
                    var fresh = fetchAllSerialized(tab, tier);
                    cache.saveBoardScan(fresh);
                    return fresh;
                  });
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      scans.remove(key, future);
    }
  }

  private MarketBoardScan fetchAllSerialized(MarketTab tab, Integer tier) {
    scanLock.lock();
    try {
      // 대기하는 동안 다른 요청이 같은 스캔을 저장했을 수 있다.
      return cache.findBoardScan(tab, tier).orElseGet(() -> fetchAll(tab, tier));
    } finally {
      scanLock.unlock();
    }
  }

  /** 기본 정렬로 1페이지부터 순차 조회한다. 동시 호출 예산을 독점하지 않도록 병렬화하지 않는다. */
  private MarketBoardScan fetchAll(MarketTab tab, Integer tier) {
    var first =
        markets.search(
            MarketSource.ACTIVE_LISTING, search(tab, tier, MarketBoardRules.Sort.DEFAULT, 1));
    int total = first.page().totalCount();
    int pages = Math.min(MAX_SCAN_PAGES, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    var items = new ArrayList<>(first.page().items());
    Instant observedAt = first.observedAt();
    for (int page = 2; page <= pages; page++) {
      var next =
          markets.search(
              MarketSource.ACTIVE_LISTING, search(tab, tier, MarketBoardRules.Sort.DEFAULT, page));
      items.addAll(next.page().items());
      if (next.observedAt().isBefore(observedAt)) observedAt = next.observedAt();
    }
    return new MarketBoardScan(tab, tier, observedAt, total, distinct(items));
  }

  private static List<MarketPrice> distinct(List<MarketPrice> items) {
    // 페이지를 넘기는 사이 순위가 바뀌면 같은 품목이 두 번 나올 수 있다.
    var seen = new java.util.LinkedHashMap<Long, MarketPrice>();
    for (var item : items) seen.putIfAbsent(item.id(), item);
    return List.copyOf(seen.values());
  }

  /** 페이지 행. 거래량은 거래량 정렬에 쓴 값(known)을 먼저 쓰고, 없으면 거래량 지도, 그래도 없으면 이력 캐시에서 계산한다(정렬 순서와 보이는 값이 같게). */
  private List<MarketBoard.Row> rows(List<MarketPrice> items, Map<Long, MarketVolume> known) {
    if (items.isEmpty()) return List.of();
    var ids = ids(items);
    Map<Long, MarketItemHistoryResult> histories = cache.findItemHistories(ids);
    var volumes = new LinkedHashMap<Long, MarketVolume>(known);
    var missing = ids.stream().filter(id -> !volumes.containsKey(id)).toList();
    if (!missing.isEmpty()) volumes.putAll(volumes(missing));
    return items.stream()
        .map(item -> row(item, histories.get(item.id()), volumes.get(item.id())))
        .toList();
  }

  private static MarketBoard.Row row(
      MarketPrice item, MarketItemHistoryResult history, MarketVolume volume) {
    var changeDay = MarketBoardRules.changeDay(item);
    var variant = history == null ? null : variant(history.history(), item);
    if (variant == null)
      return new MarketBoard.Row(item, changeDay, null, List.of(), false, volume);
    LocalDate today = LocalDate.ofInstant(history.observedAt(), KST);
    if (volume == null) volume = volumeOf(history.history(), item, today);
    var summary = variant.summary(today);
    var complete = variant.completeDays(today);
    var week = complete.subList(0, Math.min(MarketItemHistory.SEVEN_DAYS, complete.size()));
    var spark = new ArrayList<MarketBoard.SparkPoint>();
    for (int i = week.size() - 1; i >= 0; i--)
      spark.add(new MarketBoard.SparkPoint(week.get(i).date(), week.get(i).averagePrice()));
    return new MarketBoard.Row(
        item,
        changeDay,
        MarketBoardRules.change(item.currentMinPrice(), summary.sevenDayAveragePrice()),
        List.copyOf(spark),
        true,
        volume);
  }

  /** 묶음 수와 거래 가능 횟수가 같은 변형을 우선하고, 없으면 묶음 수만 같은 변형을 쓴다. */
  private static MarketItemHistory.Variant variant(MarketItemHistory history, MarketPrice item) {
    MarketItemHistory.Variant sameBundle = null;
    for (var variant : history.variants()) {
      if (!Objects.equals(variant.bundleCount(), item.bundleCount())) continue;
      if (Objects.equals(variant.tradeRemainCount(), item.tradeRemainCount())) return variant;
      if (sameBundle == null) sameBundle = variant;
    }
    return sameBundle;
  }

  private static MarketSearch search(
      MarketTab tab, Integer tier, MarketBoardRules.Sort sort, int page) {
    var officialSort =
        sort == MarketBoardRules.Sort.HIGH || sort == MarketBoardRules.Sort.LOW
            ? MarketSearch.Sort.CURRENT_MIN_PRICE
            : MarketSearch.Sort.GRADE;
    var condition =
        sort == MarketBoardRules.Sort.LOW
            ? MarketSearch.SortCondition.ASC
            : MarketSearch.SortCondition.DESC;
    return new MarketSearch(tab.categoryCode(), "", tier, "", "", page, officialSort, condition);
  }

  private static <T> T join(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException exception) {
      if (exception.getCause() instanceof RuntimeException cause) throw cause;
      if (exception.getCause() instanceof Error cause) throw cause;
      throw exception;
    }
  }

  private record ScanKey(MarketTab tab, Integer tier) {}
}
