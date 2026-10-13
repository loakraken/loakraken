package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketMinuteStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 거래소 분 단위 시세(K35). 같은 품목·같은 분은 나중 값으로 덮는다. */
@Component
class PostgresMarketMinuteStore implements MarketMinuteStore {
  private final JdbcClient jdbc;

  PostgresMarketMinuteStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(timeout = 5)
  public void save(List<Point> points) {
    for (var point : points)
      jdbc.sql(
              """
          INSERT INTO market_price_minute (item_id, minute, item_name, current_min_price, recent_price,
            sample_every_seconds)
          VALUES (:id, :minute, :name, :min, :recent, :every)
          ON CONFLICT (item_id, minute) DO UPDATE SET item_name = EXCLUDED.item_name,
            current_min_price = EXCLUDED.current_min_price, recent_price = EXCLUDED.recent_price,
            sample_every_seconds = EXCLUDED.sample_every_seconds
          """)
          .param("id", point.itemId())
          .param("minute", point.minute().atOffset(ZoneOffset.UTC))
          .param("name", point.itemName())
          .param("min", point.currentMinPrice())
          .param("recent", point.recentPrice())
          .param("every", point.sampleEverySeconds())
          .update();
  }

  @Override
  @Transactional(readOnly = true, timeout = 5)
  public List<Point> find(long itemId, Instant from) {
    return jdbc.sql(
            """
        SELECT item_id, minute, item_name, current_min_price, recent_price, sample_every_seconds
        FROM market_price_minute WHERE item_id = :id AND minute >= :from ORDER BY minute
        """)
        .param("id", itemId)
        .param("from", from.atOffset(ZoneOffset.UTC))
        .query(
            (row, index) ->
                new Point(
                    row.getLong("item_id"),
                    row.getObject("minute", OffsetDateTime.class).toInstant(),
                    row.getString("item_name"),
                    row.getBigDecimal("current_min_price"),
                    row.getBigDecimal("recent_price"),
                    row.getInt("sample_every_seconds")))
        .list();
  }

  @Override
  public int deleteBefore(Instant before) {
    return jdbc.sql("DELETE FROM market_price_minute WHERE minute < :before")
        .param("before", before.atOffset(ZoneOffset.UTC))
        .update();
  }
}
