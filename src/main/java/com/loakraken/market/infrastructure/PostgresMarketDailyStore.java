package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래소 품목 일별 시세(market_price_daily). 변형(거래 가능 횟수·묶음 수, 없으면 -1)마다 완료일을 넣고, 같은 날짜는 더 늦은 관측이고 값이 바뀌었을
 * 때만 덮는다(완료일은 거의 바뀌지 않아, 예열마다 같은 행을 다시 쓰지 않게). 지우지 않는다.
 */
@Component
class PostgresMarketDailyStore implements MarketDailyStore {
  private static final int NONE = -1;
  private final JdbcClient jdbc;

  PostgresMarketDailyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 5)
  public int upsert(MarketItemHistory history, LocalDate today, Instant observedAt) {
    int written = 0;
    for (var variant : history.variants()) {
      for (var day : variant.completeDays(today)) {
        if (day.averagePrice() == null) continue;
        written +=
            jdbc.sql(
                    """
                INSERT INTO market_price_daily (item_id, trade_remain_key, bundle_key, kst_date, item_name,
                  avg_price, trade_count, observed_at)
                VALUES (:id, :remain, :bundle, :day, :name, :avg, :trades, :observed)
                ON CONFLICT (item_id, trade_remain_key, bundle_key, kst_date) DO UPDATE SET
                  item_name = EXCLUDED.item_name, avg_price = EXCLUDED.avg_price,
                  trade_count = EXCLUDED.trade_count, observed_at = EXCLUDED.observed_at
                WHERE market_price_daily.observed_at <= EXCLUDED.observed_at
                  AND (market_price_daily.item_name, market_price_daily.avg_price,
                    market_price_daily.trade_count) IS DISTINCT FROM
                    (EXCLUDED.item_name, EXCLUDED.avg_price, EXCLUDED.trade_count)
                """)
                .param("id", history.itemId())
                .param("remain", key(variant.tradeRemainCount()))
                .param("bundle", key(variant.bundleCount()))
                .param("day", day.date())
                .param("name", variant.name() == null ? "" : variant.name())
                .param("avg", day.averagePrice())
                .param("trades", day.tradeCount())
                .param("observed", observedAt.atOffset(ZoneOffset.UTC))
                .update();
      }
    }
    return written;
  }

  private static int key(Integer value) {
    return value == null ? NONE : value;
  }
}
