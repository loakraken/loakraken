package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.application.MarketQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
  static final String BOARD_LEASE = "market-board-warmup";
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

  MarketHistoryWarmup(
      MarketQueryService markets,
      MarketBoardService boards,
      Clock clock,
      MarketWarmupProperties properties,
      LostArkProperties lostArk,
      MeterRegistry registry,
      LostArkRequestGate gate,
      LeaderLease lease) {
    this.markets = markets;
    this.boards = boards;
    this.clock = clock;
    this.properties = properties;
    this.lostArk = lostArk;
    this.registry = registry;
    this.gate = gate;
    this.lease = lease;
    gauge(registry, "warmup_history", lastHistorySuccess);
    gauge(registry, "warmup_board", lastBoardSuccess);
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
        record(Outcome.FAILED);
        log.warn("시세 이력 예열에 실패했습니다. itemId={}, code={}", itemId, exception.errorCode().code());
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
    if (!lease.hold(BOARD_LEASE, properties.boardInterval().plusSeconds(30))) return;
    for (var tab : properties.boardTabs()) {
      if (!allowCall()) {
        recordBoard(Outcome.CAPPED);
        return;
      }
      try {
        gate.withReserve(RESERVE, () -> boards.refreshDefaultPage(tab));
        recordBoard(Outcome.REFRESHED);
        lastBoardSuccess.set(clock.instant().getEpochSecond());
      } catch (BusinessException exception) {
        recordBoard(Outcome.FAILED);
        log.warn("시세 보드 예열에 실패했습니다. tab={}, code={}", tab, exception.errorCode().code());
        if (exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
            || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE) return;
      } catch (RuntimeException exception) {
        recordBoard(Outcome.FAILED);
        log.warn("시세 보드 예열에 실패했습니다. tab={}, exceptionType={}", tab, exception.getClass().getName());
      }
    }
  }

  /** 예열 전체 분당 공식 호출 상한(#154). 이력·보드 두 주기가 함께 센다. */
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

  enum Outcome {
    REFRESHED,
    NOT_FOUND,
    FAILED,
    CAPPED
  }
}
