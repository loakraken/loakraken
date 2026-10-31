package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.AuctionQuery;
import com.loakraken.market.application.AuctionPort.EtcFilter;
import com.loakraken.market.domain.AuctionCatalog;
import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.PolishOption;
import com.loakraken.market.domain.AuctionCatalog.Role;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import com.loakraken.market.domain.GemSkillCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 경매장 보석·장신구 시세 표(auction-board-v1).
 *
 * <p>표의 칸마다 공식 검색이 1회 든다(보석 20칸, 장신구 표 19~24칸). 사용자 검색을 보호하기 위해 칸을 따로 3분 캐시하고, 비어 있는 칸만 {@code
 * gate.withUserReserve(RESERVE, …)}로 채운다. 공유 게이트가 사용자 몫(reserve)을 남기는지 판정과 기록을 한 번에 하므로, 다른
 * 요청·인스턴스가 끼어들어도 사용자 몫을 파고들지 않는다(#130). 칸 채우기도 요청 클라이언트의 분당 공식 호출 한도(OFFICIAL)에 매기므로, 한도에 걸리면 남은 칸은
 * 다음 요청으로 미룬다. 한 요청에 최대 {@link #MAX_FETCH_PER_REQUEST}칸, 동시에 한 요청만 채운다. 채우지 못한 칸은 PENDING으로 준다. 전일
 * 대비는 우리가 관측한 KST 날짜별 마지막 최저가로 계산한다.
 *
 * <p>한 요청이 채우는 칸을 적게 둔다(#526): 캐시가 빈 순간의 방문 한 번이 표 전체(보석 20칸)를 한꺼번에 부르면 몇 명만 와도 사용자 몫을 다 쓴다. 남은 칸은
 * PENDING으로 두고 다음 요청이 이어서 채운다.
 */
@Service
public class MarketAuctionService {
  public static final int MAX_FETCH_PER_REQUEST = 5;
  public static final int RESERVE = 40;
  public static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(MarketAuctionService.class);
  private final AuctionPort auctions;
  private final AuctionSearchPort search;
  private final LostArkRequestGate gate;
  private final Clock clock;
  private final ReentrantLock fillLock = new ReentrantLock();
  private final MarketMetrics metrics;

  // 일별 최저가 기록(최대 24회)은 요청 밖 단일 작업자가 쓴다(PM-백엔드 #139 P2). 대기열이 차면 버리고 다음 관측에서 다시 쓴다.
  private final java.util.concurrent.Executor dailyWriter;

  @org.springframework.beans.factory.annotation.Autowired
  public MarketAuctionService(
      AuctionPort auctions,
      AuctionSearchPort search,
      LostArkRequestGate gate,
      Clock clock,
      MarketMetrics metrics) {
    this(
        auctions,
        search,
        gate,
        clock,
        metrics,
        new java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(200),
            Thread.ofVirtual().name("auction-daily-", 0).factory(),
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()));
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    if (dailyWriter instanceof java.util.concurrent.ExecutorService executor) executor.shutdown();
  }

  /** 테스트: 일별 기록 작업자를 바꾼다(예: Runnable::run). */
  MarketAuctionService(
      AuctionPort auctions,
      AuctionSearchPort search,
      LostArkRequestGate gate,
      Clock clock,
      MarketMetrics metrics,
      java.util.concurrent.Executor dailyWriter) {
    this.dailyWriter = dailyWriter;
    this.metrics = metrics;
    this.auctions = auctions;
    this.search = search;
    this.gate = gate;
    this.clock = clock;
  }

  public enum Status {
    READY,
    PENDING
  }

  public List<GemCell> gems() {
    var cells = new LinkedHashMap<String, GemSpec>();
    for (var kind : AuctionCatalog.GEM_KINDS)
      for (int level = AuctionCatalog.MAX_GEM_LEVEL; level >= 1; level--)
        cells.put(
            gemQuery(kind, level, 1).key(), new GemSpec(kind, level, gemQuery(kind, level, 1)));
    // 한 요청 안에서는 '전날'을 한 날짜로 본다(자정을 넘는 요청에서 칸마다 다른 전날을 쓰지 않게, 코드 리뷰 10-25).
    var today = LocalDate.now(clock.withZone(KST));
    var filled = fill(cells.values().stream().map(GemSpec::query).toList(), today);
    // 3분 칸이 비었고 이번에 채우지 못했으면 마지막 관측 값을 그 시각과 함께 보인다(PENDING 대신, PM 총괄 승인 10-25).
    var lasts = new LinkedHashMap<String, AuctionCell>();
    for (var key : cells.keySet())
      if (filled.get(key).cell() == null)
        auctions.findLastCell(key).ifPresent(cell -> lasts.put(key, cell));
    var previous = previousOf(List.copyOf(lasts.keySet()), today);
    var result = new ArrayList<GemCell>();
    for (var spec : cells.values()) {
      var value = filled.get(spec.query().key());
      var last = lasts.get(spec.query().key());
      var cell = value.cell() != null ? value.cell() : last;
      result.add(
          new GemCell(
              spec.kind(),
              spec.level(),
              AuctionCatalog.gemName(spec.kind(), spec.level()),
              cell,
              last != null
                  ? changeRate(last, previous.get(spec.query().key()))
                  : value.previousDayChangeRate(),
              cell == null ? Status.PENDING : Status.READY,
              last != null));
    }
    return result;
  }

  /**
   * 보석 시세 표를 캐시에서만 읽는다(#315 스펙업 가이드). 빈 칸을 채우려고 공식 API를 부르지 않으므로, 아직 관측하지 못한 칸은 PENDING이다. 전일 대비는
   * 주지 않는다(null).
   */
  public List<GemCell> cachedGems() {
    var result = new ArrayList<GemCell>();
    for (var kind : AuctionCatalog.GEM_KINDS)
      for (int level = AuctionCatalog.MAX_GEM_LEVEL; level >= 1; level--) {
        var key = gemQuery(kind, level, 1).key();
        var cell = auctions.findCell(key).orElse(null);
        // 3분 칸이 없으면 마지막 관측 값을 stale로(그 관측 시각과 함께 — 지금 시세처럼 보이지 않게, PM 총괄 10-25).
        var last = cell == null ? auctions.findLastCell(key).orElse(null) : null;
        result.add(
            new GemCell(
                kind,
                level,
                AuctionCatalog.gemName(kind, level),
                cell != null ? cell : last,
                null,
                cell == null && last == null ? Status.PENDING : Status.READY,
                last != null));
      }
    return result;
  }

  public List<AccessorySlot> accessories(Grade grade, Role role, Integer minQuality) {
    var specs = new ArrayList<AccessorySpec>();
    for (var slot : Slot.values()) {
      var options = AuctionCatalog.options(role, slot);
      for (var levels : AuctionCatalog.combinations(options.size()))
        specs.add(
            new AccessorySpec(
                slot,
                options,
                levels,
                accessoryQuery(grade, slot, options, levels, minQuality, 1)));
    }
    var filled =
        fill(specs.stream().map(AccessorySpec::query).toList(), LocalDate.now(clock.withZone(KST)));
    var slots = new ArrayList<AccessorySlot>();
    for (var slot : Slot.values()) {
      var cells = new ArrayList<AccessoryCell>();
      for (var spec : specs) {
        if (spec.slot() != slot) continue;
        var value = filled.get(spec.query().key());
        cells.add(
            new AccessoryCell(
                spec.levels(),
                AuctionCatalog.label(spec.levels()),
                AuctionCatalog.optionLabels(spec.options(), spec.levels()),
                value.cell(),
                value.previousDayChangeRate(),
                value.cell() == null ? Status.PENDING : Status.READY));
      }
      slots.add(new AccessorySlot(slot, AuctionCatalog.options(role, slot), cells));
    }
    return slots;
  }

  public AuctionPage gemListings(String kind, int level, int page) {
    return listings(gemQuery(kind, level, page));
  }

  public AuctionPage accessoryListings(
      Grade grade, Role role, Slot slot, List<Level> levels, Integer minQuality, int page) {
    var options = AuctionCatalog.options(role, slot);
    if (levels.size() != options.size())
      throw new BusinessException(MarketErrorCode.INVALID_AUCTION_LEVELS);
    return listings(accessoryQuery(grade, slot, options, levels, minQuality, page));
  }

  /** 매물 목록은 1회 검색이다. 1분 캐시하고 사용자 요청과 같은 게이트를 쓴다. */
  private AuctionPage listings(AuctionQuery query) {
    String key = query.key() + ":" + query.pageNo();
    var cached = auctions.findListings(key);
    if (cached.isPresent()) return cached.get();
    var page = search.search(query);
    auctions.saveListings(key, page);
    // 조건 붙은 검색(#542)은 GemSkillFilterService가 따로 하지만, 걸러진 최저가가 칸에 들어가지 않게 여기서도 막는다.
    if (query.pageNo() == 1 && !query.filtered()) remember(query.key(), toCell(page));
    return page;
  }

  private Map<String, Filled> fill(List<AuctionQuery> queries, LocalDate today) {
    var cells = new LinkedHashMap<String, AuctionCell>();
    for (var query : queries) cells.put(query.key(), auctions.findCell(query.key()).orElse(null));
    int fetched = 0;
    if (cells.containsValue(null) && fillLock.tryLock()) {
      try {
        for (var query : queries) {
          if (cells.get(query.key()) != null) continue;
          if (fetched == MAX_FETCH_PER_REQUEST) break;
          try {
            // 사용자 요청 중 호출이므로 요청 클라이언트의 공식 호출 한도에도 매긴다.
            var cell = toCell(gate.withUserReserve(RESERVE, () -> search.search(query)));
            fetched++;
            cells.put(query.key(), cell);
            remember(query.key(), cell);
          } catch (BusinessException exception) {
            // 호출 한도·오류는 남은 칸을 다음 요청으로 미룬다.
            log.debug("경매장 칸을 채우지 못했습니다. 코드={}", exception.errorCode().code());
            break;
          }
        }
      } finally {
        fillLock.unlock();
      }
    }
    Map<String, Long> previous;
    try {
      previous = auctions.previousDay(List.copyOf(cells.keySet()), today);
    } catch (DataAccessException exception) {
      previous = Map.of();
    }
    var result = new LinkedHashMap<String, Filled>();
    for (var entry : cells.entrySet())
      result.put(
          entry.getKey(),
          new Filled(entry.getValue(), changeRate(entry.getValue(), previous.get(entry.getKey()))));
    return result;
  }

  /** 마지막 값으로 보이는 칸들의 전날 마지막 최저가(한 번에). 저장소 장애면 빈 값. */
  private Map<String, Long> previousOf(List<String> keys, LocalDate today) {
    if (keys.isEmpty()) return Map.of();
    try {
      return auctions.previousDay(keys, today);
    } catch (DataAccessException exception) {
      return Map.of();
    }
  }

  /** 보석 시세 표 칸 수(겁화·작열 × 1~10레벨). */
  public static final int GEM_CELLS =
      AuctionCatalog.GEM_KINDS.size() * AuctionCatalog.MAX_GEM_LEVEL;

  /** 예열 한 번의 결과. outcome: refreshed·capped·failed·idle(지표 태그). */
  public record GemWarm(int refreshed, String outcome) {}

  /**
   * 보석 시세 표 칸 예열: 마지막 관측이 오래된 칸(없는 칸이 먼저)부터 count칸을 공식 경매장에서 다시 받아 칸(3분)·마지막 값·일별 기록에 남긴다(표 채우기와 같은
   * 저장 경로). allow는 예열 전체 분당 상한, call은 예열 게이트로 공식 호출을 감싼다. 한도·실패면 거기서 멈춘다.
   */
  public GemWarm warmGems(
      int count,
      java.util.function.BooleanSupplier allow,
      java.util.function.Function<java.util.function.Supplier<AuctionPage>, AuctionPage> call) {
    var queries = new ArrayList<AuctionQuery>();
    for (var kind : AuctionCatalog.GEM_KINDS)
      for (int level = AuctionCatalog.MAX_GEM_LEVEL; level >= 1; level--)
        queries.add(gemQuery(kind, level, 1));
    queries.sort(
        java.util.Comparator.comparing(
            (AuctionQuery query) ->
                auctions
                    .findLastCell(query.key())
                    .map(AuctionCell::observedAt)
                    .orElse(Instant.EPOCH)));
    int refreshed = 0;
    for (var query : queries) {
      if (refreshed >= count) break;
      if (!allow.getAsBoolean()) return new GemWarm(refreshed, "capped");
      try {
        var cell = toCell(call.apply(() -> search.search(query)));
        remember(query.key(), cell);
        refreshed++;
      } catch (BusinessException exception) {
        log.debug("보석 시세 칸 예열 실패. 코드={}", exception.errorCode().code());
        return new GemWarm(refreshed, "failed");
      }
    }
    return new GemWarm(refreshed, refreshed > 0 ? "refreshed" : "idle");
  }

  /** 보석 시세 표 20칸의 키(마지막 값을 남기는 칸). */
  private static final java.util.Set<String> GEM_KEYS = gemKeys();

  private static java.util.Set<String> gemKeys() {
    var keys = new java.util.HashSet<String>();
    for (var kind : AuctionCatalog.GEM_KINDS)
      for (int level = 1; level <= AuctionCatalog.MAX_GEM_LEVEL; level++)
        keys.add(gemQuery(kind, level, 1).key());
    return java.util.Set.copyOf(keys);
  }

  private void remember(String key, AuctionCell cell) {
    auctions.saveCell(key, cell);
    if (GEM_KEYS.contains(key)) auctions.saveLastCell(key, cell);
    if (cell.minBuyPrice() == null) return;
    dailyWriter.execute(() -> recordDaily(key, cell));
  }

  private void recordDaily(String key, AuctionCell cell) {
    try {
      auctions.recordDaily(key, LocalDate.ofInstant(cell.observedAt(), KST), cell);
    } catch (DataAccessException exception) {
      log.warn("경매장 일별 최저가를 남기지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  /** 스펙업 연마 비용에 쓰는 연마 줄 하나(옵션 이름, % 줄인가, 값 글자 예: "+2.60%"). */
  public record PolishLine(String name, boolean percent, String value) {}

  /**
   * OK: 값 있음(minBuyPrice null이면 매물 없음), MISSING: 캐시에 없고 이번에 채우지 않음, UNSUPPORTED: 경매장 옵션으로 나타낼 수 없는
   * 줄.
   */
  public enum PolishStatus {
    OK,
    MISSING,
    UNSUPPORTED
  }

  public record PolishPrice(PolishStatus status, Long minBuyPrice, Instant observedAt) {
    static final PolishPrice MISSING = new PolishPrice(PolishStatus.MISSING, null, null);
    static final PolishPrice UNSUPPORTED = new PolishPrice(PolishStatus.UNSUPPORTED, null, null);
  }

  /** 요청 하나의 연마 시세 채우기 몫. 스위치가 꺼져 있으면 0칸(캐시만 읽음). 공식 호출이 한도·장애로 실패하면 남은 몫을 쓰지 않는다. */
  public static final class PolishFill {
    private final boolean enabled;
    private int left;

    public PolishFill(boolean enabled) {
      this.enabled = enabled;
      this.left = enabled ? MAX_FETCH_PER_REQUEST : 0;
    }

    /** 스위치가 켜져 있었는가(몫을 다 써도 true). */
    boolean enabled() {
      return enabled;
    }

    boolean take() {
      if (left <= 0) return false;
      left--;
      return true;
    }

    void stop() {
      left = 0;
    }
  }

  /**
   * 장신구 연마 옵션 조합의 즉시 구매 최저가(스펙업 연마 비용). 비교 기준 B와 같은 칸: 고대·4티어·품질 조건 없음, 그 연마 값들이 있는 매물(다른 줄 허용, 계산
   * 검증 10-25 공식 경매장 대조). 캐시된 칸이 있으면 그 값, 없으면 fill 몫 안에서 사용자 요청 한도로 채운다(보석·장신구 표와 같은 게이트·몫). 줄이
   * 1~3개가 아니거나, 경매장 연마 옵션이 아닌 줄(고정값·파티원 효과 등)이 있거나, 세 줄인데 모두 상이 아니면(B 키에 없는 조합) UNSUPPORTED.
   */
  public PolishPrice polishPrice(String part, List<PolishLine> lines, PolishFill fill) {
    var result = lookupPolish(slotOf(part), lines, fill);
    metrics.polishFill(result.event());
    return result.price();
  }

  /** 연마 시세를 찾은 결과와 지표 사건 이름(MarketMetrics.polishFill). */
  private record PolishLookup(PolishPrice price, String event) {}

  private PolishLookup lookupPolish(Slot slot, List<PolishLine> lines, PolishFill fill) {
    var unsupported = new PolishLookup(PolishPrice.UNSUPPORTED, "unsupported");
    if (slot == null || lines == null || lines.isEmpty() || lines.size() > 3) return unsupported;
    var pairs = new ArrayList<java.util.Map.Entry<PolishOption, Level>>();
    for (var line : lines) {
      var option = line.percent() ? POLISH_OPTIONS.get(line.name()) : null;
      if (option == null || !AuctionCatalog.fits(slot, option)) return unsupported;
      BigDecimal value;
      try {
        value = new BigDecimal(line.value().replace("+", "").replace("%", "").strip());
      } catch (NumberFormatException exception) {
        return unsupported;
      }
      var level = option.levelOf(value).orElse(null);
      if (level == null) return unsupported;
      pairs.add(java.util.Map.entry(option, level));
    }
    if (pairs.size() == 3 && pairs.stream().anyMatch(pair -> pair.getValue() != Level.HIGH))
      return unsupported;
    // 보석·장신구 표 칸과 같은 키가 되게 표의 옵션 순서(딜러 → 서포터 → 코드)로 줄을 세운다.
    pairs.sort(java.util.Comparator.comparingInt(pair -> boardOrder(slot, pair.getKey())));
    var query =
        accessoryQuery(
            Grade.ANCIENT,
            slot,
            pairs.stream().map(java.util.Map.Entry::getKey).toList(),
            pairs.stream().map(java.util.Map.Entry::getValue).toList(),
            null,
            1);
    var cached = auctions.findCell(query.key());
    if (cached.isPresent()) return new PolishLookup(price(cached.get()), "cache_hit");
    if (fill == null || !fill.enabled()) return new PolishLookup(PolishPrice.MISSING, "fill_off");
    // 표 채우기와 같은 잠금: 동시에 같은 칸을 두 번 부르지 않게(잠금을 못 잡으면 이번엔 채우지 않음, 코드 리뷰 10-25). 몫은 잠금을 잡은 뒤에만
    // 쓴다(잠금을 못 잡아 몫만 버리지 않게).
    if (!fillLock.tryLock()) return new PolishLookup(PolishPrice.MISSING, "lock_busy");
    try {
      var again = auctions.findCell(query.key());
      if (again.isPresent()) return new PolishLookup(price(again.get()), "cache_hit");
      if (!fill.take()) return new PolishLookup(PolishPrice.MISSING, "no_budget");
      var cell = toCell(gate.withUserReserve(RESERVE, () -> search.search(query)));
      remember(query.key(), cell);
      return new PolishLookup(price(cell), "filled");
    } catch (BusinessException exception) {
      fill.stop();
      log.info(
          "event=polish_fill_failed code={} 연마 시세 칸을 채우지 못했습니다(이번 요청은 더 채우지 않음)",
          exception.errorCode().code());
      return new PolishLookup(PolishPrice.MISSING, "official_failed");
    } finally {
      fillLock.unlock();
    }
  }

  private static PolishPrice price(AuctionCell cell) {
    return new PolishPrice(PolishStatus.OK, cell.minBuyPrice(), cell.observedAt());
  }

  /** 연마 줄 이름(% 줄) → 경매장 연마 옵션. */
  private static final Map<String, PolishOption> POLISH_OPTIONS =
      Map.of(
          "추가 피해", PolishOption.ADDITIONAL_DAMAGE,
          "적에게 주는 피해", PolishOption.ENEMY_DAMAGE,
          "무기 공격력", PolishOption.WEAPON_ATTACK_PERCENT,
          "공격력", PolishOption.ATTACK_PERCENT,
          "치명타 피해", PolishOption.CRIT_DAMAGE,
          "치명타 적중률", PolishOption.CRIT_RATE,
          "낙인력", PolishOption.BRAND,
          "세레나데, 신앙, 조화 게이지 획득량", PolishOption.IDENTITY_GAUGE,
          "아군 피해량 강화 효과", PolishOption.ALLY_DAMAGE,
          "아군 공격력 강화 효과", PolishOption.ALLY_ATTACK);

  private static Slot slotOf(String part) {
    for (var slot : Slot.values()) if (slot.label().equals(part)) return slot;
    return null;
  }

  private static int boardOrder(Slot slot, PolishOption option) {
    int dealer = AuctionCatalog.options(Role.DEALER, slot).indexOf(option);
    if (dealer >= 0) return dealer;
    int support = AuctionCatalog.options(Role.SUPPORT, slot).indexOf(option);
    if (support >= 0) return 10 + support;
    return 100 + option.code();
  }

  private AuctionCell toCell(AuctionPage page) {
    return new AuctionCell(page.minBuyPrice(), page.totalCount(), page.observedAt());
  }

  /** 전날 마지막 최저가 대비 변화율(%, 소수 첫째 자리). 어느 한쪽이 없으면 null. */
  static BigDecimal changeRate(AuctionCell cell, Long previous) {
    if (cell == null || cell.minBuyPrice() == null || previous == null || previous <= 0)
      return null;
    return BigDecimal.valueOf(cell.minBuyPrice() - previous)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP);
  }

  static AuctionQuery gemQuery(String kind, int level, int page) {
    if (!AuctionCatalog.GEM_KINDS.contains(kind)
        || level < 1
        || level > AuctionCatalog.MAX_GEM_LEVEL)
      throw new BusinessException(MarketErrorCode.INVALID_AUCTION_LEVELS);
    return new AuctionQuery(
        AuctionCatalog.GEM_CATEGORY,
        AuctionCatalog.TIER,
        null,
        AuctionCatalog.gemName(kind, level),
        null,
        List.of(),
        page);
  }

  static AuctionQuery gemQuery(String kind, int level, int page, GemSkillCatalog.Filter filter) {
    var base = gemQuery(kind, level, page);
    return new AuctionQuery(
        base.categoryCode(),
        base.itemTier(),
        base.itemGrade(),
        base.itemName(),
        base.minQuality(),
        base.etcOptions(),
        base.pageNo(),
        filter.className(),
        filter.skill() == null
            ? List.of()
            : List.of(new AuctionPort.SkillFilter(filter.skill().code(), filter.secondOption())));
  }

  static AuctionQuery accessoryQuery(
      Grade grade,
      Slot slot,
      List<PolishOption> options,
      List<Level> levels,
      Integer minQuality,
      int page) {
    var filters = new ArrayList<EtcFilter>();
    for (int i = 0; i < options.size(); i++) {
      int value = options.get(i).value(levels.get(i));
      filters.add(new EtcFilter(AuctionCatalog.POLISH_OPTION, options.get(i).code(), value, value));
    }
    return new AuctionQuery(
        slot.categoryCode(),
        AuctionCatalog.TIER,
        grade.official(),
        null,
        minQuality == null || minQuality == 0 ? null : minQuality,
        filters,
        page);
  }

  private record GemSpec(String kind, int level, AuctionQuery query) {}

  private record AccessorySpec(
      Slot slot, List<PolishOption> options, List<Level> levels, AuctionQuery query) {}

  private record Filled(AuctionCell cell, BigDecimal previousDayChangeRate) {}

  /** cell이 null이면 아직 관측하지 못한 칸(PENDING)이다. */
  /** stale이면 cell은 3분 칸이 비어 대신 보이는 마지막 관측 값이다(observedAt이 그 시각). */
  public record GemCell(
      String kind,
      int level,
      String itemName,
      AuctionCell cell,
      BigDecimal previousDayChangeRate,
      Status status,
      boolean stale) {
    public GemCell(
        String kind,
        int level,
        String itemName,
        AuctionCell cell,
        BigDecimal previousDayChangeRate,
        Status status) {
      this(kind, level, itemName, cell, previousDayChangeRate, status, false);
    }
  }

  public record AccessoryCell(
      List<Level> levels,
      String label,
      List<String> optionLabels,
      AuctionCell cell,
      BigDecimal previousDayChangeRate,
      Status status) {}

  public record AccessorySlot(Slot slot, List<PolishOption> options, List<AccessoryCell> cells) {}
}
