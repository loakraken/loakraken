package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 일별 시세 저장은 요청 스레드 밖(한 줄 큐)에서 하고, 큐가 가득 차면 버리고 지표를 남기며, 저장 실패도 호출한 쪽에 오지 않는다. 성공도 지표(saved·rows)와
 * 마지막 성공 시각으로 보이고, 큐 깊이가 게이지로 보이며, 요청의 requestId·entryPoint가 저장 스레드 로그까지 따라간다.
 */
class AsyncMarketDailyRecorderTest {
  private static final LocalDate TODAY = LocalDate.parse("2026-11-13");
  private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
  private static final Instant CLOCK_NOW = Instant.parse("2026-11-13T03:00:00Z");
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MarketMetrics metrics = new MarketMetrics(registry);
  private final Clock clock = Clock.fixed(CLOCK_NOW, ZoneOffset.UTC);

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
    var recorder = recorder(store, 1);
    try {
      long before = System.nanoTime();
      recorder.record(history(1), TODAY, NOW); // 실행 중(막힘)
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      recorder.record(history(2), TODAY, NOW); // 큐 1칸
      // 큐 깊이 게이지: 기다리는 품목 1
      var queueGauge = registry.find("loakraken.market.daily_record.queue").gauge();
      assertThat(queueGauge).as("큐 깊이 게이지").isNotNull();
      assertThat(queueGauge.value()).isEqualTo(1);
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
    var recorder = recorder(store, 4);
    try {
      recorder.record(history(1), TODAY, NOW);
      assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
      recorder.shutdown();
      assertThat(registry.counter("loakraken.market.daily_record", "event", "failed").count())
          .isEqualTo(1);
      assertThat(registry.counter("loakraken.market.daily_record", "event", "saved").count())
          .isZero();
      assertThat(lastSuccess()).isZero();
    } finally {
      recorder.shutdown();
    }
  }

  @Test
  void 저장_성공은_saved_지표_바뀐_행_수_마지막_성공_시각으로_보인다() {
    var store = mock(MarketDailyStore.class);
    when(store.upsert(any(), any(), any())).thenReturn(3, 0);
    var recorder = recorder(store, 4);
    recorder.record(history(1), TODAY, NOW);
    recorder.record(history(2), TODAY, NOW); // 같은 값이라 바뀐 행 0이어도 저장은 성공
    recorder.shutdown();

    assertThat(registry.counter("loakraken.market.daily_record", "event", "saved").count())
        .isEqualTo(2);
    assertThat(registry.counter("loakraken.market.daily_record.rows").count()).isEqualTo(3);
    assertThat(lastSuccess()).isEqualTo(CLOCK_NOW.getEpochSecond());
  }

  @Test
  void 요청의_requestId와_entryPoint가_저장_스레드_로그까지_따라가고_끝나면_지운다() throws Exception {
    var store = mock(MarketDailyStore.class);
    var seen = new AtomicReference<String>();
    var leftover = new AtomicReference<String>("unset");
    when(store.upsert(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              var item = ((MarketItemHistory) invocation.getArgument(0)).itemId();
              if (item == 1) seen.set(MDC.get("requestId") + "/" + MDC.get("entryPoint"));
              else leftover.set(MDC.get("requestId") + "/" + MDC.get("entryPoint"));
              return 0;
            });
    var recorder = recorder(store, 4);
    try {
      MDC.put("requestId", "req-1");
      MDC.put("entryPoint", "volume_warmup");
      recorder.record(history(1), TODAY, NOW);
      MDC.clear();
      recorder.record(history(2), TODAY, NOW); // 문맥 없는 호출
      recorder.shutdown();
      assertThat(seen.get()).isEqualTo("req-1/volume_warmup");
      // 앞 작업의 문맥이 다음 작업에 남지 않는다.
      assertThat(leftover.get()).isEqualTo("null/null");
    } finally {
      MDC.clear();
      recorder.shutdown();
    }
  }

  private AsyncMarketDailyRecorder recorder(MarketDailyStore store, int queue) {
    return new AsyncMarketDailyRecorder(store, metrics, registry, clock, queue);
  }

  private double lastSuccess() {
    var gauge =
        registry.find("loakraken.background.last_success").tag("job", "market_daily").gauge();
    assertThat(gauge).as("마지막 성공 시각 게이지(job=market_daily)").isNotNull();
    return gauge.value();
  }

  private static MarketItemHistory history(long itemId) {
    return new MarketItemHistory(itemId, List.of());
  }
}
