package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.MarketTestFixture;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarketQueryServiceTest {
  private final MarketPricesPort prices = mock(MarketPricesPort.class);
  private final MarketOptionsPort options = mock(MarketOptionsPort.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
  private final MarketSearch search = MarketTestFixture.search();
  private final MarketPage page = new MarketPage(1, 10, 0, List.of());

  @Test
  void 캐시_적중은_수집시각을_보존하고_외부_호출하지_않는다() {
    var cache = mock(MarketCache.class);
    var result =
        new MarketResult(
            MarketSource.ACTIVE_LISTING, clock.instant().minusSeconds(10), search, page);
    var metadata =
        new MarketOptionsResult(clock.instant().minusSeconds(60), MarketTestFixture.options());
    when(cache.findPrices(MarketSource.ACTIVE_LISTING, search)).thenReturn(Optional.of(result));
    when(cache.findOptions()).thenReturn(Optional.of(metadata));
    var service = new MarketQueryService(prices, options, cache, clock);
    assertThat(service.search(MarketSource.ACTIVE_LISTING, search)).isSameAs(result);
    assertThat(service.options()).isSameAs(metadata);
    verifyNoInteractions(prices, options);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void 동일한_가격_또는_옵션_동시_요청은_한번만_수집한다(boolean requestOptions) throws Exception {
    var stored = new ConcurrentHashMap<String, MarketResult>();
    var storedOptions = new AtomicReference<MarketOptionsResult>();
    var cache =
        new MarketCache() {
          public Optional<MarketResult> findPrices(MarketSource source, MarketSearch query) {
            return Optional.ofNullable(stored.get(source + query.toString()));
          }

          public void savePrices(MarketResult value) {
            stored.put(value.source() + value.search().toString(), value);
          }

          public Optional<MarketOptionsResult> findOptions() {
            return Optional.ofNullable(storedOptions.get());
          }

          public void saveOptions(MarketOptionsResult value) {
            storedOptions.set(value);
          }
        };
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    org.mockito.stubbing.Answer<Object> blocked =
        invocation -> {
          entered.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("대기 초과");
          return requestOptions ? MarketTestFixture.options() : page;
        };
    if (requestOptions) when(options.fetchOptions()).thenAnswer(blocked);
    else when(prices.fetch(MarketSource.ACTIVE_LISTING, search)).thenAnswer(blocked);
    var service = new MarketQueryService(prices, options, cache, clock);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<Object>>();
      try {
        for (int i = 0; i < 16; i++)
          tasks.add(
              executor.submit(
                  () ->
                      requestOptions
                          ? service.options()
                          : service.search(MarketSource.ACTIVE_LISTING, search)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        release.countDown();
      }
      Object expected = tasks.getFirst().get(5, TimeUnit.SECONDS);
      for (var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo(expected);
    }
    if (requestOptions) {
      verify(options, times(1)).fetchOptions();
      verifyNoInteractions(prices);
    } else {
      verify(prices, times(1)).fetch(MarketSource.ACTIVE_LISTING, search);
      verifyNoInteractions(options);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void 수집_실패는_캐시하지_않고_다음_요청에서_복구한다(boolean requestOptions) {
    var cache = mock(MarketCache.class);
    var exception = new BusinessException(LostArkErrorCode.LOST_ARK_API_ERROR);
    var service = new MarketQueryService(prices, options, cache, clock);
    if (requestOptions) {
      when(options.fetchOptions()).thenThrow(exception).thenReturn(MarketTestFixture.options());
      assertThatThrownBy(service::options).isSameAs(exception);
      verify(cache, never()).saveOptions(any());
      assertThat(service.options().options()).isEqualTo(MarketTestFixture.options());
      verify(options, times(2)).fetchOptions();
      verify(cache).saveOptions(any());
    } else {
      when(prices.fetch(any(), any())).thenThrow(exception).thenReturn(page);
      assertThatThrownBy(() -> service.search(MarketSource.ACTIVE_LISTING, search))
          .isSameAs(exception);
      verify(cache, never()).savePrices(any());
      assertThat(service.search(MarketSource.ACTIVE_LISTING, search).page()).isEqualTo(page);
      verify(prices, times(2)).fetch(any(), any());
      verify(cache).savePrices(any());
    }
  }

  @Test
  void 캐시_읽기_장애는_외부_호출로_우회하지_않는다() {
    var cache = mock(MarketCache.class);
    var error = new BusinessException(MarketErrorCode.MARKET_CACHE_UNAVAILABLE);
    when(cache.findOptions()).thenThrow(error);
    when(cache.findPrices(any(), any())).thenThrow(error);
    var service = new MarketQueryService(prices, options, cache, clock);
    assertThatThrownBy(service::options).isSameAs(error);
    assertThatThrownBy(() -> service.search(MarketSource.ACTIVE_LISTING, search)).isSameAs(error);
    verifyNoInteractions(prices, options);
  }

  @Test
  void 캐시_쓰기_실패도_전파하고_다음_요청을_막지_않는다() {
    var cache = mock(MarketCache.class);
    var error = new BusinessException(MarketErrorCode.MARKET_CACHE_UNAVAILABLE);
    when(prices.fetch(any(), any())).thenReturn(page);
    doThrow(error).doNothing().when(cache).savePrices(any());
    var service = new MarketQueryService(prices, options, cache, clock);
    assertThatThrownBy(() -> service.search(MarketSource.ACTIVE_LISTING, search)).isSameAs(error);
    assertThat(service.search(MarketSource.ACTIVE_LISTING, search).page()).isEqualTo(page);
    verify(prices, times(2)).fetch(any(), any());
  }
}
