package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 핵심 품목의 14일 이력을 주기적으로 강제 갱신해 시세 보드의 changeWeek·spark7을 첫 화면부터 채운다.
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
  private final MarketWarmupProperties properties;
  private final LostArkProperties lostArk;
  private final MeterRegistry registry;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;

  MarketHistoryWarmup(
      MarketQueryService markets,
      MarketWarmupProperties properties,
      LostArkProperties lostArk,
      MeterRegistry registry,
      LostArkRequestGate gate,
      LeaderLease lease) {
    this.markets = markets;
    this.properties = properties;
    this.lostArk = lostArk;
    this.registry = registry;
    this.gate = gate;
    this.lease = lease;
  }

  @Scheduled(
      fixedDelayString = "${market.warmup.interval:9m}",
      initialDelayString = "${market.warmup.initial-delay:20s}")
  void warm() {
    if (lostArk.apiKey().isBlank()) return;
    // 임대는 주기보다 길게 잡아 다음 주기까지 유지한다.
    if (!lease.hold(LEASE, properties.interval().plusMinutes(1))) return;
    for (long itemId : properties.itemIds()) {
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

  private void record(Outcome outcome) {
    registry
        .counter("loakraken.market.warmup", "outcome", outcome.name().toLowerCase(Locale.ROOT))
        .increment();
  }

  enum Outcome {
    REFRESHED,
    NOT_FOUND,
    FAILED
  }
}
