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
import java.util.Set;
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
 * 음수·같은 변형 칸) 그 줄만 건너뛰고 지표는 매번, WARN 로그는 (품목, 이유)마다 하루(KST)에 한 번만 남긴다(예열이 같은 품목을 하루 여러 번 받아도
 * 로그가 쌓이지 않게). 지우지 않는다.
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

  /** 하루에 기억하는 (품목, 이유) 수 상한. 넘으면 그날 남은 건너뜀은 지표로만 센다. */
  static final int SKIP_LOG_KEYS = 10_000;

  private final NamedParameterJdbcTemplate jdbc;
  private final MarketMetrics metrics;
  private final Set<String> skipLogged = new HashSet<>();
  private LocalDate skipLogDay;

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
        skip(history.itemId(), "variant_key", today);
        continue;
      }
      var name = variant.name() == null ? "" : variant.name();
      if (name.length() > NAME_MAX) {
        skip(history.itemId(), "name_length", today);
        continue;
      }
      for (var day : variant.completeDays(today)) {
        var price = price(day.averagePrice());
        if (price == null || day.tradeCount() < 0) {
          skip(history.itemId(), "value", today);
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
    // 드라이버가 개수를 알려 주지 않은 줄(SUCCESS_NO_INFO, 배치 다시 쓰기 설정)은 세지 않는다. 지금 설정(reWriteBatchedInserts 없음)은
    // 줄마다 실제 개수를 준다. 이 값은 지표(daily_record.rows)에만 쓰고 저장 결과에는 영향이 없다.
    return Arrays.stream(counts).map(count -> Math.max(count, 0)).sum();
  }

  /** NUMERIC(16, 4)에 맞게 소수 넷째 자리로 반올림한다. 음수·null·정수 12자리 초과는 null(건너뜀). */
  private static BigDecimal price(BigDecimal value) {
    if (value == null || value.signum() < 0) return null;
    var scaled = value.setScale(4, RoundingMode.HALF_UP);
    return scaled.precision() - scaled.scale() > INTEGER_DIGITS ? null : scaled;
  }

  private void skip(long itemId, String reason, LocalDate today) {
    metrics.dailyRecord("skipped_" + reason);
    if (firstToday(itemId + "/" + reason, today))
      log.warn("event=market_daily_row_skipped itemId={} reason={}", itemId, reason);
  }

  /** 그날 처음 보는 (품목, 이유)인가. 날짜가 바뀌면 비운다. 상한을 넘으면 false(로그 없이 지표만). */
  private synchronized boolean firstToday(String key, LocalDate today) {
    if (!today.equals(skipLogDay)) {
      skipLogDay = today;
      skipLogged.clear();
    }
    if (skipLogged.size() >= SKIP_LOG_KEYS) return false;
    return skipLogged.add(key);
  }

  private static int key(Integer value) {
    return value == null ? NONE : value;
  }
}
