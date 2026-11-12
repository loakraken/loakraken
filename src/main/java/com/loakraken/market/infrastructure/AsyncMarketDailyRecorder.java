package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketDailyRecorder;
import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 일별 시세 저장을 요청 경로 밖에서 한다(보내고 잊음). 스레드 하나와 크기 QUEUE의 큐로 품목 하나씩 차례로 저장한다. 큐가 가득 차면 그 품목을 버리고 지표
 * (daily_record dropped)를 남긴다. 버린 날짜는 다음에 같은 품목의 상세를 받을 때 14일 덮어쓰기로 다시 채워진다. 로그는 첫 번째와 100번째마다만.
 */
@Component
class AsyncMarketDailyRecorder implements MarketDailyRecorder {
  private static final Logger log = LoggerFactory.getLogger(AsyncMarketDailyRecorder.class);
  static final int QUEUE = 512;
  private final MarketDailyStore store;
  private final MarketMetrics metrics;
  private final ThreadPoolExecutor executor;
  private final AtomicLong dropped = new AtomicLong();

  @Autowired
  AsyncMarketDailyRecorder(MarketDailyStore store, MarketMetrics metrics) {
    this(store, metrics, QUEUE);
  }

  AsyncMarketDailyRecorder(MarketDailyStore store, MarketMetrics metrics, int queue) {
    this.store = store;
    this.metrics = metrics;
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
  }

  @Override
  public void record(MarketItemHistory history, LocalDate today, Instant observedAt) {
    try {
      executor.execute(() -> save(history, today, observedAt));
    } catch (RejectedExecutionException exception) {
      metrics.dailyRecord("dropped");
      long count = dropped.incrementAndGet();
      if (count == 1 || count % 100 == 0)
        log.warn("event=market_daily_dropped itemId={} droppedTotal={}", history.itemId(), count);
    }
  }

  private void save(MarketItemHistory history, LocalDate today, Instant observedAt) {
    try {
      store.upsert(history, today, observedAt);
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
