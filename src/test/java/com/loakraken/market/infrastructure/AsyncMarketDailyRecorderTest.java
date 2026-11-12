package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 일별 시세 저장은 요청 스레드 밖(한 줄 큐)에서 하고, 큐가 가득 차면 버리고 지표를 남기며, 저장 실패도 호출한 쪽에 오지 않는다. */
class AsyncMarketDailyRecorderTest {
  private static final LocalDate TODAY = LocalDate.parse("2026-11-13");
  private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MarketMetrics metrics = new MarketMetrics(registry);

  @Test
  void 요청_스레드를_막지_않고_큐가_가득_차면_버리고_지표를_남긴다() throws Exception {
    var store = mock(MarketDailyStore.class);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var done = new CountDownLatch(2);
    when(store.upsert(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              started.countDown();
              release.await(5, TimeUnit.SECONDS);
              done.countDown();
              return 0;
            });
    var recorder = new AsyncMarketDailyRecorder(store, metrics, 1);
    try {
      long before = System.nanoTime();
      recorder.record(history(1), TODAY, NOW); // 실행 중(막힘)
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      recorder.record(history(2), TODAY, NOW); // 큐 1칸
      recorder.record(history(3), TODAY, NOW); // 큐 가득 → 버림
      // 저장이 막혀 있어도 호출은 바로 돌아온다.
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)).isLessThan(2_000);
      assertThat(registry.counter("loakraken.market.daily_record", "event", "dropped").count())
          .isEqualTo(1);
      release.countDown();
      assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
      verify(store).upsert(history(1), TODAY, NOW);
      verify(store).upsert(history(2), TODAY, NOW);
      verify(store, never()).upsert(history(3), TODAY, NOW);
    } finally {
      release.countDown();
      recorder.shutdown();
    }
  }

  @Test
  void 저장_실패는_호출한_쪽에_오지_않고_지표만_남긴다() throws Exception {
    var store = mock(MarketDailyStore.class);
    var called = new CountDownLatch(1);
    when(store.upsert(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              called.countDown();
              throw new IllegalStateException("db down");
            });
    var recorder = new AsyncMarketDailyRecorder(store, metrics, 4);
    try {
      recorder.record(history(1), TODAY, NOW);
      assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
      recorder.shutdown();
      assertThat(registry.counter("loakraken.market.daily_record", "event", "failed").count())
          .isEqualTo(1);
    } finally {
      recorder.shutdown();
    }
  }

  private static MarketItemHistory history(long itemId) {
    return new MarketItemHistory(itemId, List.of());
  }
}
