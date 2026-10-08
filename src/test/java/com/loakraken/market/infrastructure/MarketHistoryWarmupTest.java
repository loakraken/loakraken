package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.market.application.MarketItemHistoryResult;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.domain.MarketItemHistory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketHistoryWarmupTest {
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void 키가_없으면_아무것도_하지_않는다() {
    warmup("", List.of(1L, 2L)).warm();
    verifyNoInteractions(markets);
  }

  @Test
  void 품목별_실패를_격리하고_결과를_지표로_남긴다() {
    when(markets.refreshItemHistory(1L)).thenReturn(Optional.of(result(1L)));
    when(markets.refreshItemHistory(2L)).thenReturn(Optional.empty());
    when(markets.refreshItemHistory(3L))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_API_ERROR));
    when(markets.refreshItemHistory(4L)).thenThrow(new IllegalStateException("x"));
    when(markets.refreshItemHistory(5L)).thenReturn(Optional.of(result(5L)));
    warmup("key", List.of(1L, 2L, 3L, 4L, 5L)).warm();
    verify(markets, times(5)).refreshItemHistory(anyLong());
    assertThat(count("refreshed")).isEqualTo(2);
    assertThat(count("not_found")).isEqualTo(1);
    assertThat(count("failed")).isEqualTo(2);
  }

  @Test
  void 호출_한도_초과를_만나면_이번_주기를_멈춘다() {
    when(markets.refreshItemHistory(1L))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED));
    warmup("key", List.of(1L, 2L, 3L)).warm();
    verify(markets).refreshItemHistory(1L);
    verify(markets, never()).refreshItemHistory(2L);
  }

  @Test
  void 기본_목록은_프론트_합의_19개이고_잘못된_설정은_거절한다() {
    var defaults =
        new MarketWarmupProperties(
            true,
            Duration.ofMinutes(9),
            Duration.ofSeconds(20),
            List.of(
                6861012L, 6861013L, 66102006L, 66102007L, 66102106L, 66102107L, 66110225L,
                66110226L, 66130141L, 66130142L, 66130143L, 66111131L, 66111132L, 66112543L,
                66112551L, 66112553L, 66112546L, 66112552L, 66112554L, 66102006L));
    assertThat(defaults.itemIds()).hasSize(19);
    assertThatThrownBy(
            () -> new MarketWarmupProperties(true, Duration.ZERO, Duration.ZERO, List.of(1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MarketWarmupProperties(true, Duration.ofMinutes(9), Duration.ZERO, List.of(0L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private MarketHistoryWarmup warmup(String key, List<Long> ids) {
    var lostArk =
        new LostArkProperties(
            URI.create("http://127.0.0.1:1"),
            key,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            90,
            4,
            Duration.ofMinutes(5));
    return new MarketHistoryWarmup(
        markets,
        new MarketWarmupProperties(true, Duration.ofMinutes(9), Duration.ZERO, ids),
        lostArk,
        registry);
  }

  private double count(String outcome) {
    var counter = registry.find("loakraken.market.warmup").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  private static MarketItemHistoryResult result(long id) {
    return new MarketItemHistoryResult(Instant.now(), new MarketItemHistory(id, List.of()));
  }
}
