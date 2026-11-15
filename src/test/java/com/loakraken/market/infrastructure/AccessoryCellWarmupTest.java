package com.loakraken.market.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketAuctionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** 장신구 칸 예열(PM 총괄 11-16): 1분에 한 칸, 60분보다 오래된 칸만, 꺼져 있거나 키·임대가 없으면 부르지 않는다. */
class AccessoryCellWarmupTest {
  private final MarketAuctionService auctions = mock(MarketAuctionService.class);
  private final LeaderLease lease = mock(LeaderLease.class);
  private final MarketWarmupProperties warmupProperties = mock(MarketWarmupProperties.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private AccessoryCellWarmup warmup(boolean enabled, String apiKey) {
    when(warmupProperties.interval()).thenReturn(Duration.ofMinutes(9));
    when(auctions.warmAccessories(anyInt(), any(), any(), any()))
        .thenReturn(new MarketAuctionService.GemWarm(1, "refreshed"));
    return new AccessoryCellWarmup(
        auctions,
        mock(MarketHistoryWarmup.class),
        new AccessoryWarmupProperties(enabled, Duration.ofMinutes(60)),
        warmupProperties,
        new LostArkProperties(
            java.net.URI.create("https://example.com"),
            apiKey,
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            90,
            4,
            Duration.ofMinutes(5)),
        mock(LostArkRequestGate.class),
        lease,
        registry,
        Clock.fixed(Instant.parse("2026-11-16T09:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void 일분에_한_칸을_60분보다_오래된_칸만_다른_예열과_같은_임대로_받고_지표를_남긴다() {
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(true);
    warmup(true, "key").warmAccessoryCells();
    verify(auctions).warmAccessories(eq(1), eq(Duration.ofMinutes(60)), any(), any());
    verify(lease).hold(MarketHistoryWarmup.LEASE, Duration.ofMinutes(10));
    Assertions.assertThat(
            registry
                .get("loakraken.market.warmup")
                .tag("target", "accessories")
                .tag("outcome", "refreshed")
                .counter()
                .count())
        .isEqualTo(1.0);
    Assertions.assertThat(registry.get("loakraken.market.warmup_accessories").gauge().value())
        .isEqualTo(Instant.parse("2026-11-16T09:00:00Z").getEpochSecond());
  }

  @Test
  void 꺼져_있거나_키가_없거나_임대가_없으면_부르지_않는다() {
    when(lease.hold(any(), any())).thenReturn(true);
    warmup(false, "key").warmAccessoryCells();
    warmup(true, "").warmAccessoryCells();
    when(lease.hold(any(), any())).thenReturn(false);
    warmup(true, "key").warmAccessoryCells();
    verify(auctions, never()).warmAccessories(anyInt(), any(), any(), any());
  }
}
