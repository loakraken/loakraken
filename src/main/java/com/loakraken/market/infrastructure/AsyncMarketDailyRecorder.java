package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketDailyRecorder;
import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 일별 시세 저장을 요청 경로 밖에서 한다. 시험 먼저 올리는 칸(아직 넘기지 않음). */
@Component
class AsyncMarketDailyRecorder implements MarketDailyRecorder {
  static final int QUEUE = 512;
  private final MarketDailyStore store;
  private final MarketMetrics metrics;

  @Autowired
  AsyncMarketDailyRecorder(MarketDailyStore store, MarketMetrics metrics) {
    this(store, metrics, QUEUE);
  }

  AsyncMarketDailyRecorder(MarketDailyStore store, MarketMetrics metrics, int queue) {
    this.store = store;
    this.metrics = metrics;
  }

  @Override
  public void record(MarketItemHistory history, LocalDate today, Instant observedAt) {}

  void shutdown() {}
}
