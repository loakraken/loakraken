package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 거래소 품목 일별 시세(market_price_daily). 시험 먼저 올리는 칸(아직 저장하지 않음). */
@Component
class PostgresMarketDailyStore implements MarketDailyStore {
  private final JdbcClient jdbc;

  PostgresMarketDailyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int upsert(MarketItemHistory history, LocalDate today, Instant observedAt) {
    return 0;
  }
}
