package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketDailyRecorder;
import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 일별 시세 저장을 요청 경로 밖에서 한다(보내고 잊음). 스레드 하나와 크기 QUEUE의 큐로 품목 하나씩 차례로 저장한다. 큐가 가득 차면 그 품목을 버리고 지표
 * (daily_record dropped)를 남긴다. 버린 날짜는 다음에 같은 품목의 상세를 받을 때 14일 덮어쓰기로 다시 채워진다. 로그는 첫 번째와 100번째마다만.
 *
 * <p>관측: 저장 성공은 daily_record saved와 바뀐 행 수(daily_record.rows), 마지막 성공 시각 게이지
 * (background.last_success{job=market_daily}), 큐 깊이 게이지(daily_record.queue). 넘긴 쪽의 MDC(requestId·entryPoint)를
 * 저장 스레드로 옮겨, 저장 실패·건너뜀 로그를 어느 요청·작업이 넘겼는지 찾을 수 있게 한다.
 */
@Component
class AsyncMarketDailyRecorder implements MarketDailyRecorder {
  private static final Logger log = LoggerFactory.getLogger(AsyncMarketDailyRecorder.class);
  static final int QUEUE = 512;
  private final MarketDailyStore store;
  private final MarketMetrics metrics;
  private final Clock clock;
  private final ThreadPoolExecutor executor;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong lastSuccess = new AtomicLong();

  @Autowired
  AsyncMarketDailyRecorder(
      MarketDailyStore store, MarketMetrics metrics, MeterRegistry registry, Clock clock) {
    this(store, metrics, registry, clock, QUEUE);
  }

  AsyncMarketDailyRecorder(
      MarketDailyStore store,
      MarketMetrics metrics,
      MeterRegistry registry,
      Clock clock,
      int queue) {
    this.store = store;
    this.metrics = metrics;
    this.clock = clock;
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queue),
            runnable -> {
              var thread = new Thread(runnable, "market-daily");
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    Gauge.builder("loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "market_daily")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
    Gauge.builder("loakraken.market.daily_record.queue", executor, e -> e.getQueue().size())
        .description("일별 시세 저장을 기다리는 품목 수(최대 " + queue + ", 가득 차면 dropped)")
        .register(registry);
  }

  @Override
  public void record(MarketItemHistory history, LocalDate today, Instant observedAt) {
    var context = MDC.getCopyOfContextMap();
    try {
      executor.execute(() -> run(context, history, today, observedAt));
    } catch (RejectedExecutionException exception) {
      metrics.dailyRecord("dropped");
      long count = dropped.incrementAndGet();
      if (count == 1 || count % 100 == 0)
        log.warn("event=market_daily_dropped itemId={} droppedTotal={}", history.itemId(), count);
    }
  }

  private void run(
      java.util.Map<String, String> context,
      MarketItemHistory history,
      LocalDate today,
      Instant observedAt) {
    if (context == null) MDC.clear();
    else MDC.setContextMap(context);
    try {
      save(history, today, observedAt);
    } finally {
      MDC.clear();
    }
  }

  private void save(MarketItemHistory history, LocalDate today, Instant observedAt) {
    try {
      int rows = store.upsert(history, today, observedAt);
      metrics.dailyRecord("saved");
      metrics.dailyRows(rows);
      lastSuccess.set(clock.instant().getEpochSecond());
    } catch (RuntimeException exception) {
      metrics.dailyRecord("failed");
      log.warn(
          "event=market_daily_save_failed itemId={} exceptionType={}",
          history.itemId(),
          exception.getClass().getSimpleName());
    }
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException exception) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
