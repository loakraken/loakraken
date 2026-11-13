package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래소 품목 일별 시세(market_price_daily). 변형(거래 가능 횟수·묶음 수, 없으면 -1)마다 완료일을 넣고, 같은 날짜는 더 늦은 관측이고 값이 바뀌었을
 * 때만 덮는다(완료일은 거의 바뀌지 않아, 예열마다 같은 행을 다시 쓰지 않게). 품목 하나를 한 번의 배치로 쓴다. 줄 하나가 표에 맞지 않으면(이름 길이·숫자 칸·
 * 음수·같은 변형 칸) 그 줄만 건너뛰고 로그·지표를 남긴다. 지우지 않는다.
 */
@Component
class PostgresMarketDailyStore implements MarketDailyStore {
  private static final Logger log = LoggerFactory.getLogger(PostgresMarketDailyStore.class);
  private static final int NONE = -1;
  static final int NAME_MAX = 128;

  /** NUMERIC(16, 4)의 정수 자리 한도. */
  private static final int INTEGER_DIGITS = 12;

  private static final String UPSERT =
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
      """;

  private final NamedParameterJdbcTemplate jdbc;
  private final MarketMetrics metrics;

  PostgresMarketDailyStore(NamedParameterJdbcTemplate jdbc, MarketMetrics metrics) {
    this.jdbc = jdbc;
    this.metrics = metrics;
  }

  @Override
  @Transactional(timeout = 5)
  public int upsert(MarketItemHistory history, LocalDate today, Instant observedAt) {
    var rows = new ArrayList<SqlParameterSource>();
    var keys = new HashSet<String>();
    var observed = observedAt.atOffset(ZoneOffset.UTC);
    for (var variant : history.variants()) {
      int remain = key(variant.tradeRemainCount());
      int bundle = key(variant.bundleCount());
      if (!keys.add(remain + "/" + bundle)) {
        skip(history.itemId(), "variant_key");
        continue;
      }
      var name = variant.name() == null ? "" : variant.name();
      if (name.length() > NAME_MAX) {
        skip(history.itemId(), "name_length");
        continue;
      }
      for (var day : variant.completeDays(today)) {
        var price = price(day.averagePrice());
        if (price == null || day.tradeCount() < 0) {
          skip(history.itemId(), "value");
          continue;
        }
        rows.add(
            new MapSqlParameterSource()
                .addValue("id", history.itemId())
                .addValue("remain", remain)
                .addValue("bundle", bundle)
                .addValue("day", day.date())
                .addValue("name", name)
                .addValue("avg", price)
                .addValue("trades", day.tradeCount())
                .addValue("observed", observed));
      }
    }
    if (rows.isEmpty()) return 0;
    var counts = jdbc.batchUpdate(UPSERT, rows.toArray(SqlParameterSource[]::new));
    return Arrays.stream(counts).map(count -> Math.max(count, 0)).sum();
  }

  /** 기간 읽기: 기본키(item_id, 변형, kst_date) 앞부분 범위 하나와 변형별 첫 날짜 하나. 둘 다 이름 있는 매개변수. */
  @Override
  public StoredRange findRange(long itemId, LocalDate from) {
    var days =
        jdbc.query(
            """
            SELECT trade_remain_key, bundle_key, kst_date, avg_price, trade_count, item_name
            FROM market_price_daily
            WHERE item_id = :id AND kst_date >= :from
            ORDER BY kst_date DESC, trade_remain_key, bundle_key
            """,
            new MapSqlParameterSource().addValue("id", itemId).addValue("from", from),
            (row, n) ->
                new StoredDay(
                    MarketDailyStore.variantKey(row.getInt(1), row.getInt(2)),
                    row.getObject(3, LocalDate.class),
                    row.getBigDecimal(4),
                    row.getLong(5),
                    row.getString(6)));
    var first = new java.util.HashMap<String, LocalDate>();
    jdbc.query(
        """
        SELECT trade_remain_key, bundle_key, min(kst_date)
        FROM market_price_daily
        WHERE item_id = :id
        GROUP BY trade_remain_key, bundle_key
        """,
        new MapSqlParameterSource().addValue("id", itemId),
        (java.sql.ResultSet row) -> {
          first.put(
              MarketDailyStore.variantKey(row.getInt(1), row.getInt(2)),
              row.getObject(3, LocalDate.class));
        });
    return new StoredRange(days, first);
  }

  /** NUMERIC(16, 4)에 맞게 소수 넷째 자리로 반올림한다. 음수·null·정수 12자리 초과는 null(건너뜀). */
  private static BigDecimal price(BigDecimal value) {
    if (value == null || value.signum() < 0) return null;
    var scaled = value.setScale(4, RoundingMode.HALF_UP);
    return scaled.precision() - scaled.scale() > INTEGER_DIGITS ? null : scaled;
  }

  private void skip(long itemId, String reason) {
    metrics.dailyRecord("skipped_" + reason);
    log.warn("event=market_daily_row_skipped itemId={} reason={}", itemId, reason);
  }

  private static int key(Integer value) {
    return value == null ? NONE : value;
  }
}
