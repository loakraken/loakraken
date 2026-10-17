package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.market.application.RelicBookScan;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 핵심 품목의 14일 이력을 주기적으로 강제 갱신해 시세 보드의 changeWeek·spark7을 첫 화면부터 채운다. 홈 요약·시세 기본 탭이 쓰는 기본 보드(재련 탭
 * 1페이지)도 가격 캐시 TTL 주기로 다시 받아 1분 안의 값을 유지한다(#154).
 *
 * <p>기본은 꺼짐이고 지정한 인스턴스 한 곳에서만 켠다. 켜진 인스턴스끼리도 리더 임대로 한 곳만 돈다(#107). 호출은 사용자 몫(reserve)을 남기도록 판정한다.
 * API 키가 없으면 아무것도 하지 않는다. 품목별 실패는 격리하고, 호출 한도·게이트 장애를 만나면 이번 주기를 멈춘다.
 */
@Component
@ConditionalOnProperty(prefix = "market.warmup", name = "enabled", havingValue = "true")
class MarketHistoryWarmup {
  private static final Logger log = LoggerFactory.getLogger(MarketHistoryWarmup.class);
  static final String LEASE = "market-warmup";
  static final int RESERVE = 40;
  private final MarketQueryService markets;
  private final MarketBoardService boards;
  private final Clock clock;
  private final MarketWarmupProperties properties;
  private final java.util.concurrent.atomic.AtomicLong lastHistorySuccess =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong lastBoardSuccess =
      new java.util.concurrent.atomic.AtomicLong();
  // 리더 임대로 한 인스턴스만 돌므로 분당 상한은 인스턴스 안에서 센다.
  private long callWindow = -1;
  private int callsInWindow;
  private final LostArkProperties lostArk;
  private final MeterRegistry registry;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;
  private final RelicBookPriceService relicBooks;
  private final RelicBookWarmupProperties relicBookProperties;
  private final java.util.concurrent.atomic.AtomicLong lastRelicBookSuccess =
      new java.util.concurrent.atomic.AtomicLong();
  // 각인서 스캔 진행 상태(리더 한 곳에서만 돈다). 분당 상한에 걸리면 다음 틱에 이어서 받는다.
  private int relicPage = 0;
  private int relicTotal = 0;
  private Instant relicObservedAt;
  private final java.util.List<com.loakraken.market.domain.MarketPrice> relicItems =
      new java.util.ArrayList<>();
  private Instant relicDueAt = Instant.EPOCH;
  private final com.loakraken.market.application.RefiningMaterialPriceService refiningMaterials;
  private final RefiningMaterialWarmupProperties refiningMaterialProperties;
  private final java.util.concurrent.atomic.AtomicLong lastRefiningMaterialSuccess =
      new java.util.concurrent.atomic.AtomicLong();
  private Instant refiningMaterialDueAt = Instant.EPOCH;
  private final com.loakraken.market.application.MaterialUnitPriceService lifeMaterials;
  private final LifeMaterialWarmupProperties lifeMaterialProperties;
  private final java.util.concurrent.atomic.AtomicLong lastLifeMaterialSuccess =
      new java.util.concurrent.atomic.AtomicLong();
  // 분류 코드 → 다음 스캔 시각(리더 한 곳에서만 돈다).
  private final java.util.Map<Integer, Instant> lifeMaterialDueAt = new java.util.HashMap<>();
  private final org.springframework.context.ApplicationEventPublisher events;

  MarketHistoryWarmup(
      MarketQueryService markets,
      MarketBoardService boards,
      Clock clock,
      MarketWarmupProperties properties,
      LostArkProperties lostArk,
      MeterRegistry registry,
      LostArkRequestGate gate,
      LeaderLease lease,
      RelicBookPriceService relicBooks,
      RelicBookWarmupProperties relicBookProperties,
      com.loakraken.market.application.RefiningMaterialPriceService refiningMaterials,
      RefiningMaterialWarmupProperties refiningMaterialProperties,
      com.loakraken.market.application.MaterialUnitPriceService lifeMaterials,
      LifeMaterialWarmupProperties lifeMaterialProperties,
      org.springframework.context.ApplicationEventPublisher events) {
    this.events = events;
    this.lifeMaterials = lifeMaterials;
    this.lifeMaterialProperties = lifeMaterialProperties;
    this.markets = markets;
    this.boards = boards;
    this.clock = clock;
    this.properties = properties;
    this.lostArk = lostArk;
    this.registry = registry;
    this.gate = gate;
    this.lease = lease;
    this.relicBooks = relicBooks;
    this.relicBookProperties = relicBookProperties;
    this.refiningMaterials = refiningMaterials;
    this.refiningMaterialProperties = refiningMaterialProperties;
    gauge(registry, "warmup_history", lastHistorySuccess);
    gauge(registry, "warmup_board", lastBoardSuccess);
    gauge(registry, "warmup_relic_books", lastRelicBookSuccess);
    gauge(registry, "warmup_refining_materials", lastRefiningMaterialSuccess);
    gauge(registry, "warmup_life_materials", lastLifeMaterialSuccess);
  }

  /**
   * 받은 가격을 시세 알림(M33)·분 단위 차트(K35)에 알린다. sampleEvery는 이 품목들을 다시 받는 예열 주기다. 듣는 쪽의 실패가 예열을 멈추지 않게 예외를
   * 삼키고 종류만 남긴다(내용·주소는 남기지 않음).
   */
  private void publish(
      Instant observedAt,
      java.time.Duration sampleEvery,
      java.util.List<com.loakraken.market.domain.MarketPrice> prices) {
    if (prices.isEmpty()) return;
    try {
      events.publishEvent(
          new com.loakraken.market.application.MarketPricesObserved(
              observedAt, sampleEvery, prices));
    } catch (RuntimeException exception) {
      log.warn("시세 관측 알림에 실패했습니다. exceptionType={}", exception.getClass().getName());
    }
  }

  /** 마지막 성공 시각(epoch 초) 게이지(#143). 0이면 이 인스턴스에서 아직 성공하지 않았다. */
  private static void gauge(
      MeterRegistry registry, String job, java.util.concurrent.atomic.AtomicLong value) {
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.background.last_success",
            value,
            java.util.concurrent.atomic.AtomicLong::doubleValue)
        .tag("job", job)
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
  }

  @Scheduled(
      fixedDelayString = "${market.warmup.interval:9m}",
      initialDelayString = "${market.warmup.initial-delay:20s}")
  void warm() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmHistory();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  private void warmHistory() {
    if (lostArk.apiKey().isBlank()) return;
    // 임대는 주기보다 길게 잡아 다음 주기까지 유지한다.
    if (!lease.hold(LEASE, properties.interval().plusMinutes(1))) return;
    for (long itemId : properties.itemIds()) {
      if (!allowCall()) {
        record(Outcome.CAPPED);
        return;
      }
      try {
        record(
            gate.withReserve(RESERVE, () -> markets.refreshItemHistory(itemId)).isPresent()
                ? Outcome.REFRESHED
                : Outcome.NOT_FOUND);
      } catch (BusinessException exception) {
        record(failure("시세 이력", "itemId=" + itemId, exception));
        if (exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
            || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE) return;
      } catch (RuntimeException exception) {
        record(Outcome.FAILED);
        log.warn(
            "시세 이력 예열에 실패했습니다. itemId={}, exceptionType={}",
            itemId,
            exception.getClass().getName());
      }
    }
  }

  @Scheduled(
      fixedDelayString = "${market.warmup.board-interval:30s}",
      initialDelayString = "${market.warmup.initial-delay:20s}")
  void warmBoards() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmBoardTabs();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  private void warmBoardTabs() {
    if (lostArk.apiKey().isBlank()) return;
    // 이력 예열과 같은 임대를 쓴다. 두 주기가 한 인스턴스에서 돌아야 분당 상한(인스턴스 안 카운터)이 합산을 지킨다(#158 P2).
    if (!lease.hold(LEASE, properties.interval().plusMinutes(1))) return;
    for (var tab : properties.boardTabs()) {
      if (!allowCall()) {
        recordBoard(Outcome.CAPPED);
        return;
      }
      try {
        var refreshed = gate.withReserve(RESERVE, () -> boards.refreshDefaultPage(tab));
        recordBoard(Outcome.REFRESHED);
        if (refreshed != null)
          publish(refreshed.observedAt(), properties.boardInterval(), refreshed.page().items());
        lastBoardSuccess.set(clock.instant().getEpochSecond());
      } catch (BusinessException exception) {
        recordBoard(failure("시세 보드", "tab=" + tab, exception));
        if (exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
            || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE) return;
      } catch (RuntimeException exception) {
        recordBoard(Outcome.FAILED);
        log.warn("시세 보드 예열에 실패했습니다. tab={}, exceptionType={}", tab, exception.getClass().getName());
      }
    }
  }

  /**
   * 유물 각인서 시세(L30): 주기(기본 30분)마다 유물 각인서 전체(2026-10-12 43종, 5페이지)를 받아 캐시(TTL = 주기 × 3)에 둔다. 1분마다
   * 확인하고, 예열 분당 상한(이력·보드와 함께)에 걸리면 다음 틱에 남은 페이지를 이어 받는다.
   */
  @Scheduled(fixedDelayString = "60s", initialDelayString = "${market.warmup.initial-delay:20s}")
  void warmRelicBooks() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmRelicBookPages();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  synchronized void warmRelicBookPages() {
    if (!relicBookProperties.enabled() || lostArk.apiKey().isBlank()) return;
    var now = clock.instant();
    if (relicPage == 0 && now.isBefore(relicDueAt)) return;
    if (!lease.hold(LEASE, properties.interval().plusMinutes(1))) return;
    while (true) {
      int page = relicPage + 1;
      if (relicPage > 0
          && (page > relicBookProperties.maxPages() || (page - 1) * 10 >= relicTotal)) {
        relicBooks.save(
            new RelicBookScan(relicObservedAt, relicTotal, java.util.List.copyOf(relicItems)),
            relicBookProperties.cacheTtl());
        publish(relicObservedAt, relicBookProperties.interval(), java.util.List.copyOf(relicItems));
        recordRelic(Outcome.REFRESHED);
        lastRelicBookSuccess.set(now.getEpochSecond());
        relicDueAt = now.plus(relicBookProperties.interval());
        resetRelic();
        return;
      }
      if (!allowCall()) {
        recordRelic(Outcome.CAPPED);
        return;
      }
      try {
        var result = gate.withReserve(RESERVE, () -> relicBooks.fetchPage(page));
        relicTotal = result.page().totalCount();
        relicItems.addAll(result.page().items());
        if (relicObservedAt == null || result.observedAt().isBefore(relicObservedAt))
          relicObservedAt = result.observedAt();
        relicPage = page;
        if (relicTotal == 0) relicObservedAt = result.observedAt();
      } catch (BusinessException exception) {
        recordRelic(failure("각인서 시세", "page=" + page, exception));
        // 다음 틱에 같은 페이지부터 다시 받는다.
        return;
      } catch (RuntimeException exception) {
        recordRelic(Outcome.FAILED);
        log.warn(
            "각인서 시세 예열에 실패했습니다. page={}, exceptionType={}", page, exception.getClass().getName());
        resetRelic();
        relicDueAt = now.plus(java.time.Duration.ofMinutes(5));
        return;
      }
    }
  }

  /**
   * T4 재련 재료 시세(L30 후속): 주기(기본 10분)마다 재련 재료·추가 재료 탭을 모두 받아 캐시(TTL = 주기 × 3)에 둔다. 1분마다 확인하고, 예열 분당
   * 상한에 걸리거나 실패하면 이번 스캔을 버리고 다음 틱에 처음부터 다시 받는다(한 번에 5~6회라 이어 받기를 두지 않는다).
   */
  @Scheduled(fixedDelayString = "60s", initialDelayString = "${market.warmup.initial-delay:20s}")
  void warmRefiningMaterials() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmRefiningMaterialPages();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  synchronized void warmRefiningMaterialPages() {
    if (!refiningMaterialProperties.enabled() || lostArk.apiKey().isBlank()) return;
    var now = clock.instant();
    if (now.isBefore(refiningMaterialDueAt)) return;
    if (!lease.hold(LEASE, properties.interval().plusMinutes(1))) return;
    var items = new java.util.ArrayList<com.loakraken.market.domain.MarketPrice>();
    Instant observedAt = null;
    for (var tab : com.loakraken.market.application.RefiningMaterialPriceService.TABS) {
      for (int page = 1; page <= refiningMaterialProperties.maxPagesPerTab(); page++) {
        if (!allowCall()) {
          recordRefiningMaterials(Outcome.CAPPED);
          return;
        }
        int pageNo = page;
        try {
          var result = gate.withReserve(RESERVE, () -> refiningMaterials.fetchPage(tab, pageNo));
          items.addAll(result.page().items());
          if (observedAt == null || result.observedAt().isBefore(observedAt))
            observedAt = result.observedAt();
          if ((long) pageNo * 10 >= result.page().totalCount()) break;
        } catch (BusinessException exception) {
          recordRefiningMaterials(
              failure("재련 재료 시세", "tab=" + tab + ", page=" + pageNo, exception));
          return;
        }
      }
    }
    refiningMaterials.save(
        new com.loakraken.market.application.RefiningMaterialScan(
            observedAt == null ? now : observedAt, items),
        refiningMaterialProperties.cacheTtl());
    publish(
        observedAt == null ? now : observedAt,
        refiningMaterialProperties.interval(),
        java.util.List.copyOf(items));
    recordRefiningMaterials(Outcome.REFRESHED);
    lastRefiningMaterialSuccess.set(now.getEpochSecond());
    refiningMaterialDueAt = now.plus(refiningMaterialProperties.interval());
  }

  /**
   * 생활 분류 시세(재료 시세 P0): 주기(기본 10분)마다 생활 분류 전체(2026-10-14 34종, 4페이지)를 받아 캐시(TTL = 주기 × 3)에 둔다. 재료 시세
   * API가 목재·철광석·유물 같은 생활 재료를 공식 호출 없이 답한다. 분당 상한에 걸리거나 실패하면 이번 스캔을 버리고 다음 틱에 처음부터 받는다.
   */
  @Scheduled(fixedDelayString = "60s", initialDelayString = "${market.warmup.initial-delay:20s}")
  void warmLifeMaterials() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmLifeMaterialPages();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  /**
   * 예열 분류(생활 90000·전투 용품 60000)마다 따로 받고 따로 저장한다(분류마다 다음 시각). 한 분류를 끝까지 받지 못하면(분당 상한·실패) 그 분류만 다음 틱에
   * 처음부터 다시 받는다. TotalCount만큼 다 받으면 그 분류를 '다 받음'으로 저장해, 재료 시세가 거기 없는 이름을 다시 검색하지 않게 한다.
   */
  synchronized void warmLifeMaterialPages() {
    if (!lifeMaterialProperties.enabled() || lostArk.apiKey().isBlank()) return;
    var now = clock.instant();
    if (com.loakraken.market.application.MaterialUnitPriceService.SCANNED_CATEGORIES.stream()
        .allMatch(
            category -> now.isBefore(lifeMaterialDueAt.getOrDefault(category, Instant.EPOCH))))
      return;
    if (!lease.hold(LEASE, properties.interval().plusMinutes(1))) return;
    for (var category :
        com.loakraken.market.application.MaterialUnitPriceService.SCANNED_CATEGORIES) {
      if (now.isBefore(lifeMaterialDueAt.getOrDefault(category, Instant.EPOCH))) continue;
      if (!warmCategory(category, now)) return;
    }
  }

  /** 한 분류를 끝까지 받아 저장한다. 멈췄으면 false(이번 틱의 나머지 분류도 건너뜀). */
  private boolean warmCategory(int category, Instant now) {
    var items = new java.util.ArrayList<com.loakraken.market.domain.MarketPrice>();
    Instant observedAt = null;
    int total = -1;
    for (int page = 1; page <= lifeMaterialProperties.maxPages(); page++) {
      if (!allowCall()) {
        recordLifeMaterials(Outcome.CAPPED);
        return false;
      }
      int pageNo = page;
      try {
        var result =
            gate.withReserve(RESERVE, () -> lifeMaterials.fetchCategoryPage(category, pageNo));
        items.addAll(result.page().items());
        total = result.page().totalCount();
        if (observedAt == null || result.observedAt().isBefore(observedAt))
          observedAt = result.observedAt();
        if ((long) pageNo * 10 >= total) break;
      } catch (BusinessException exception) {
        recordLifeMaterials(
            failure("분류 시세", "category=" + category + ", page=" + pageNo, exception));
        return false;
      } catch (RuntimeException exception) {
        recordLifeMaterials(Outcome.FAILED);
        log.warn(
            "분류 시세 예열에 실패했습니다. category={}, page={}, exceptionType={}",
            category,
            pageNo,
            exception.getClass().getName());
        return false;
      }
    }
    boolean complete = total >= 0 && items.size() >= total;
    lifeMaterials.saveCategoryScan(
        category,
        new com.loakraken.market.application.LifeMaterialScan(
            observedAt == null ? now : observedAt,
            items,
            complete ? java.util.List.of(category) : java.util.List.of()),
        lifeMaterialProperties.cacheTtl());
    recordLifeMaterials(Outcome.REFRESHED);
    lastLifeMaterialSuccess.set(now.getEpochSecond());
    lifeMaterialDueAt.put(category, now.plus(lifeMaterialProperties.interval()));
    return true;
  }

  private void recordLifeMaterials(Outcome outcome) {
    registry
        .counter(
            "loakraken.market.warmup",
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT),
            "target",
            "life_materials")
        .increment();
  }

  private void recordRefiningMaterials(Outcome outcome) {
    registry
        .counter(
            "loakraken.market.warmup",
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT),
            "target",
            "refining_materials")
        .increment();
  }

  private void resetRelic() {
    relicPage = 0;
    relicTotal = 0;
    relicObservedAt = null;
    relicItems.clear();
  }

  private void recordRelic(Outcome outcome) {
    registry
        .counter(
            "loakraken.market.warmup",
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT),
            "target",
            "relic_books")
        .increment();
  }

  /** 예열 전체 분당 공식 호출 상한(#154). 이력·보드·각인서가 함께 센다. */
  synchronized boolean allowCall() {
    long window = clock.millis() / 60_000;
    if (window != callWindow) {
      callWindow = window;
      callsInWindow = 0;
    }
    if (callsInWindow >= properties.maxCallsPerMinute()) return false;
    callsInWindow++;
    return true;
  }

  private void recordBoard(Outcome outcome) {
    registry
        .counter(
            "loakraken.market.warmup",
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT),
            "target",
            "board")
        .increment();
  }

  private void record(Outcome outcome) {
    if (outcome == Outcome.REFRESHED) lastHistorySuccess.set(clock.instant().getEpochSecond());
    registry
        .counter(
            "loakraken.market.warmup",
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT),
            "target",
            "history")
        .increment();
  }

  /**
   * 예열 실패를 로그로 남기고 결과를 고른다. 우리 관문이 사용자 몫을 남기려고·공식 한도 대기로·동시 상한으로 미룬 것은 실패가 아니라 DEFERRED(INFO)이고, 공식
   * 서버의 429와 그 밖의 오류는 FAILED(WARN)다. 둘 다 다음 주기에 다시 받는다. 로그에는 이유(reason)를 남긴다.
   */
  private static Outcome failure(String job, String detail, BusinessException exception) {
    var reason = com.loakraken.common.lostark.LostArkRateLimitedException.reasonOf(exception);
    boolean deferred =
        exception instanceof com.loakraken.common.lostark.LostArkRateLimitedException limited
            && limited.limitCause()
                != com.loakraken.common.lostark.LostArkRateLimitedException.Cause.UPSTREAM_429;
    if (deferred) {
      log.info("{} 예열을 관문이 미뤘습니다(다음 주기에 다시). {}, reason={}", job, detail, reason);
      return Outcome.DEFERRED;
    }
    log.warn(
        "{} 예열에 실패했습니다. {}, code={}, reason={}", job, detail, exception.errorCode().code(), reason);
    return Outcome.FAILED;
  }

  enum Outcome {
    REFRESHED,
    NOT_FOUND,
    FAILED,
    DEFERRED,
    CAPPED
  }
}
