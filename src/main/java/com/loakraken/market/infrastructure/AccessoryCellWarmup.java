package com.loakraken.market.infrastructure;

import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketAuctionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 장신구 시세 표 기본 필터 칸 예열(PM 총괄 11-16). 1분마다 마지막 관측이 주기(기본 60분)보다 오래된 칸 하나를 공식 경매장에서 다시 받는다 — 표 기본 필터
 * 24칸이면 한 시간에 24회(하루 약 576회). 보석 칸 예열과 같은 게이트 예약분·예열 전체 분당 상한·리더 잠금을 쓰므로 사용자 요청에 양보한다. 받은 값만 칸·마지막
 * 값·일별 기록에 남긴다.
 */
@Component
@ConditionalOnProperty(prefix = "market.warmup", name = "enabled", havingValue = "true")
class AccessoryCellWarmup {
  private final MarketAuctionService auctions;
  private final MarketHistoryWarmup warmup;
  private final AccessoryWarmupProperties properties;
  private final MarketWarmupProperties warmupProperties;
  private final LostArkProperties lostArk;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;
  private final MeterRegistry registry;
  private final Clock clock;
  private final AtomicLong lastSuccess = new AtomicLong();

  AccessoryCellWarmup(
      MarketAuctionService auctions,
      MarketHistoryWarmup warmup,
      AccessoryWarmupProperties properties,
      MarketWarmupProperties warmupProperties,
      LostArkProperties lostArk,
      LostArkRequestGate gate,
      LeaderLease lease,
      MeterRegistry registry,
      Clock clock) {
    this.auctions = auctions;
    this.warmup = warmup;
    this.properties = properties;
    this.warmupProperties = warmupProperties;
    this.lostArk = lostArk;
    this.gate = gate;
    this.lease = lease;
    this.registry = registry;
    this.clock = clock;
    registry.gauge("loakraken.market.warmup_accessories", lastSuccess);
  }

  @Scheduled(fixedDelayString = "60s", initialDelayString = "${market.warmup.initial-delay:20s}")
  void warmAccessories() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmAccessoryCells();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  void warmAccessoryCells() {
    if (!properties.enabled() || lostArk.apiKey().isBlank()) return;
    // 다른 예열과 같은 임대·같은 길이(보석 칸 예열과 같음).
    if (!lease.hold(MarketHistoryWarmup.LEASE, warmupProperties.interval().plusMinutes(1))) return;
    var result =
        auctions.warmAccessories(
            1,
            properties.interval(),
            warmup::allowCall,
            search -> gate.withReserve(MarketHistoryWarmup.RESERVE, search));
    registry
        .counter("loakraken.market.warmup", "outcome", result.outcome(), "target", "accessories")
        .increment();
    if (result.refreshed() > 0) lastSuccess.set(clock.instant().getEpochSecond());
  }
}
