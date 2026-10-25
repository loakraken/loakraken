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
 * 보석 시세 표 칸 예열(스펙업 보석 비용, PM 총괄 10-25). 1분마다 오래된 칸부터 몇 칸을 공식 경매장에서 다시 받는다(칸 3분 캐시 + 마지막 값 저장은 표와
 * 같은 경로). 시세 예열과 같은 게이트·예열 전체 분당 상한·리더 잠금을 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "market.warmup", name = "enabled", havingValue = "true")
class GemCellWarmup {
  private final MarketAuctionService auctions;
  private final MarketHistoryWarmup warmup;
  private final GemWarmupProperties properties;
  private final LostArkProperties lostArk;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;
  private final MeterRegistry registry;
  private final Clock clock;
  private final AtomicLong lastSuccess = new AtomicLong();

  GemCellWarmup(
      MarketAuctionService auctions,
      MarketHistoryWarmup warmup,
      GemWarmupProperties properties,
      LostArkProperties lostArk,
      LostArkRequestGate gate,
      LeaderLease lease,
      MeterRegistry registry,
      Clock clock) {
    this.auctions = auctions;
    this.warmup = warmup;
    this.properties = properties;
    this.lostArk = lostArk;
    this.gate = gate;
    this.lease = lease;
    this.registry = registry;
    this.clock = clock;
    registry.gauge("loakraken.market.warmup_gems", lastSuccess);
  }

  @Scheduled(fixedDelayString = "60s", initialDelayString = "${market.warmup.initial-delay:20s}")
  void warmGems() {
    org.slf4j.MDC.put("entryPoint", "warmup");
    try {
      warmGemCells();
    } finally {
      org.slf4j.MDC.remove("entryPoint");
    }
  }

  void warmGemCells() {
    if (!properties.enabled() || lostArk.apiKey().isBlank()) return;
    if (!lease.hold(MarketHistoryWarmup.LEASE, java.time.Duration.ofMinutes(2))) return;
    int count = properties.cellsPerMinute(MarketAuctionService.GEM_CELLS);
    var result =
        auctions.warmGems(
            count,
            warmup::allowCall,
            search -> gate.withReserve(MarketHistoryWarmup.RESERVE, search));
    registry
        .counter("loakraken.market.warmup", "outcome", result.outcome(), "target", "gems")
        .increment();
    if (result.refreshed() > 0) lastSuccess.set(clock.instant().getEpochSecond());
  }
}
