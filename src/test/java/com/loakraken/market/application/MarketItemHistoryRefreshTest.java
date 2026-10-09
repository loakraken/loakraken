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
  void 검색_강제_갱신은_캐시를_읽지_않고_공식_검색을_다시_받아_저장한다() {
    var prices = mock(MarketPricesPort.class);
    var refreshing =
        new MarketQueryService(prices, mock(MarketOptionsPort.class), cache, clock, port);
    var search =
        new MarketSearch(
            50010, "", 4, "", "", 1, MarketSearch.Sort.GRADE, MarketSearch.SortCondition.DESC);
    var page = new MarketPage(1, 10, 0, List.of());
    when(prices.fetch(MarketSource.ACTIVE_LISTING, search)).thenReturn(page);
    var result = refreshing.refreshSearch(MarketSource.ACTIVE_LISTING, search);
    assertThat(result.observedAt()).isEqualTo(clock.instant());
    assertThat(result.page()).isSameAs(page);
    verify(cache, never()).findPrices(any(), any());
    verify(cache).savePrices(result);
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
