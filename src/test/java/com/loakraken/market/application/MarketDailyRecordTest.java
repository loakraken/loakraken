package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.domain.MarketItemHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 거래소 일별 시세: 공식 상세를 이미 받는 곳(상세 화면·예열)에서만 저장을 넘기고(요청 경로 밖), 캐시 적중이나 넘기기 실패는 응답을 바꾸지 않는다. */
class MarketDailyRecordTest {
  private final MarketItemHistoryPort port = mock(MarketItemHistoryPort.class);
  private final MarketCache cache = mock(MarketCache.class);
  private final MarketDailyRecorder daily = mock(MarketDailyRecorder.class);
  // 2026-11-12 02:00 KST
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T17:00:00Z"), ZoneOffset.UTC);
  private final MarketQueryService service =
      new MarketQueryService(
          mock(MarketPricesPort.class), mock(MarketOptionsPort.class), cache, clock, port, daily);
  private final MarketItemHistory history = new MarketItemHistory(7L, List.of());

  @Test
  void 예열_강제_갱신은_받은_상세를_KST_오늘_기준으로_저장한다() {
    when(port.fetchHistory(7L)).thenReturn(Optional.of(history));
    service.refreshItemHistory(7L);
    verify(daily).record(history, LocalDate.parse("2026-11-12"), clock.instant());
  }

  @Test
  void 상세_화면은_캐시가_없을_때만_받은_상세를_저장한다() {
    when(cache.findItemHistory(7L)).thenReturn(Optional.empty());
    when(port.fetchHistory(7L)).thenReturn(Optional.of(history));
    service.itemHistory(7L);
    verify(daily).record(history, LocalDate.parse("2026-11-12"), clock.instant());

    reset(daily);
    when(cache.findItemHistory(8L))
        .thenReturn(
            Optional.of(
                new MarketItemHistoryResult(
                    clock.instant(), new MarketItemHistory(8L, List.of()))));
    service.itemHistory(8L);
    verify(daily, never()).record(any(), any(), any());
  }

  @Test
  void 저장이_실패해도_상세는_그대로_돌려준다() {
    when(port.fetchHistory(7L)).thenReturn(Optional.of(history));
    doThrow(new java.util.concurrent.RejectedExecutionException("full"))
        .when(daily)
        .record(any(), any(), any());
    assertThat(service.refreshItemHistory(7L)).isPresent();
    verify(daily).record(history, LocalDate.parse("2026-11-12"), clock.instant());
  }
}
