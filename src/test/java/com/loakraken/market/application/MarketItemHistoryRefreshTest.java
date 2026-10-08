package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.domain.MarketItemHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketItemHistoryRefreshTest {
  private final MarketItemHistoryPort port = mock(MarketItemHistoryPort.class);
  private final MarketCache cache = mock(MarketCache.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC);
  private final MarketQueryService service =
      new MarketQueryService(
          mock(MarketPricesPort.class), mock(MarketOptionsPort.class), cache, clock, port);

  @Test
  void 강제_갱신은_캐시를_읽지_않고_공식_상세를_다시_받아_저장한다() {
    when(port.fetchHistory(7L)).thenReturn(Optional.of(new MarketItemHistory(7L, List.of())));
    var result = service.refreshItemHistory(7L).orElseThrow();
    assertThat(result.observedAt()).isEqualTo(clock.instant());
    verify(cache, never()).findItemHistory(anyLong());
    verify(cache).saveItemHistory(result);
  }

  @Test
  void 모르는_상품은_저장하지_않는다() {
    when(port.fetchHistory(8L)).thenReturn(Optional.empty());
    assertThat(service.refreshItemHistory(8L)).isEmpty();
    verify(cache, never()).saveItemHistory(any());
  }

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
  }
}
