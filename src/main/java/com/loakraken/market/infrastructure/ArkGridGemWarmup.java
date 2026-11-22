package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.domain.MarketTab;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 젬 교체 순위 예열(PM 총괄 2026-11-22): 젬 교체 순위 스위치(combat-power-sim.gem-ranking.enabled)가 켜져 있을 때만, 주기(기본
 * 10분)마다 아크 그리드 탭 전체 스캔을 새로 받아 저장한다 — 저장이 젬 순위용 스냅숏(15분)도 갱신해 순위가 시세 없이 비지 않는다. 순위 요청은 캐시만 읽는다.
 *
 * <p>스위치는 틱마다 읽는다(꺼져 있으면 공식 호출 0). 다른 시세 예열과 같은 켜짐 조건(market.warmup.enabled)과 리더 임대를 써서 한 인스턴스에서만
 * 돈다. 스캔은 보드 방문과 같은 합치기·한 번에 하나이고, 공식 호출은 사용자 몫(reserve)을 남기도록 공유 예산 안에서 판정한다. 이 탭은 2쪽이라 10분에 약
 * 2번(하루 약 290번).
 */
@Component
@ConditionalOnProperty(prefix = "market.warmup", name = "enabled", havingValue = "true")
class ArkGridGemWarmup {
  private static final Logger log = LoggerFactory.getLogger(ArkGridGemWarmup.class);
  static final String SWITCH = "combat-power-sim.gem-ranking.enabled";

  private final MarketBoardService boards;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;
  private final LostArkProperties lostArk;
  private final Environment environment;
  private final MeterRegistry registry;
  private final Clock clock;
  private final Duration interval;
  private final AtomicLong lastSuccess = new AtomicLong();

  ArkGridGemWarmup(
      MarketBoardService boards,
      LostArkRequestGate gate,
      LeaderLease lease,
      LostArkProperties lostArk,
      Environment environment,
      MeterRegistry registry,
      Clock clock,
      @Value("${market.warmup.ark-grid-interval:10m}") Duration interval) {
    this.boards = boards;
    this.gate = gate;
    this.lease = lease;
    this.lostArk = lostArk;
    this.environment = environment;
    this.registry = registry;
    this.clock = clock;
    this.interval = interval;
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "warmup_ark_grid_gems")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
  }

  @Scheduled(
      fixedDelayString = "${market.warmup.ark-grid-interval:10m}",
      initialDelayString = "${market.warmup.initial-delay:20s}")
  void warm() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmArkGrid();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  private void warmArkGrid() {
    if (!environment.getProperty(SWITCH, Boolean.class, false)) return;
    if (lostArk.apiKey().isBlank()) return;
    // 다른 시세 예열과 같은 임대(같은 인스턴스가 잡고 있으면 연장된다). 주기보다 길게, 다른 예열이 잡는 길이보다 짧지 않게.
    var hold = interval.plusMinutes(1);
    if (hold.compareTo(Duration.ofMinutes(10)) < 0) hold = Duration.ofMinutes(10);
    if (!lease.hold(MarketHistoryWarmup.LEASE, hold)) return;
    try {
      gate.withReserve(MarketHistoryWarmup.RESERVE, () -> boards.refreshScan(MarketTab.ARK_GRID));
      lastSuccess.set(clock.instant().getEpochSecond());
      record("refreshed");
    } catch (BusinessException exception) {
      boolean limited =
          exception.errorCode() == LostArkErrorCode.LOST_ARK_RATE_LIMITED
              || exception.errorCode() == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE;
      record(limited ? "deferred" : "failed");
      log.warn("event=ark_grid_gem_warmup_failed errorCode={}", exception.errorCode().code());
    } catch (RuntimeException exception) {
      record("failed");
      log.warn(
          "event=ark_grid_gem_warmup_failed exceptionType={}",
          exception.getClass().getSimpleName());
    }
  }

  private void record(String outcome) {
    registry
        .counter(
            "loakraken.market.warmup",
            "outcome",
            outcome.toLowerCase(Locale.ROOT),
            "target",
            "ark_grid_gems")
        .increment();
  }
}
