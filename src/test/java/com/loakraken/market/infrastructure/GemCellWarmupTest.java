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
import org.junit.jupiter.api.Test;

/** 보석 칸 예열: 10분 한 바퀴면 1분에 2칸, 꺼져 있거나 임대가 없으면 부르지 않는다(PM 총괄 10-25). */
class GemCellWarmupTest {
  private final MarketAuctionService auctions = mock(MarketAuctionService.class);
  private final LeaderLease lease = mock(LeaderLease.class);
  private final MarketWarmupProperties warmupProperties = mock(MarketWarmupProperties.class);

  private GemCellWarmup warmup(boolean enabled, String apiKey) {
    when(warmupProperties.interval()).thenReturn(Duration.ofMinutes(9));
    when(auctions.warmGems(anyInt(), any(), any()))
        .thenReturn(new MarketAuctionService.GemWarm(2, "refreshed"));
    return new GemCellWarmup(
        auctions,
        mock(MarketHistoryWarmup.class),
        new GemWarmupProperties(enabled, Duration.ofMinutes(10)),
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
        new SimpleMeterRegistry(),
        Clock.fixed(Instant.parse("2026-10-25T09:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void 열분_한_바퀴면_1분에_두_칸을_다른_예열과_같은_임대로_받는다() {
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(true);
    warmup(true, "key").warmGemCells();
    verify(auctions).warmGems(eq(2), any(), any());
    verify(lease).hold(MarketHistoryWarmup.LEASE, Duration.ofMinutes(10));
  }

  @Test
  void 꺼져_있거나_키가_없거나_임대가_없으면_부르지_않는다() {
    when(lease.hold(any(), any())).thenReturn(true);
    warmup(false, "key").warmGemCells();
    warmup(true, "").warmGemCells();
    when(lease.hold(any(), any())).thenReturn(false);
    warmup(true, "key").warmGemCells();
    verify(auctions, never()).warmGems(anyInt(), any(), any());
  }
}
