package com.loakraken.market.infrastructure;

import com.loakraken.market.application.AuctionPort;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 경매장 캐시·일별 기록. 칸 캐시 `market:v1:auction-cell:{키}`(3분), 매물
 * `market:v1:auction-listings:{키}:{페이지}`(1분), 일별 최저가는 Postgres `auction_price_daily`. 캐시 장애는 조회를 막지
 * 않는다.
 */
@Component
class AuctionStore implements AuctionPort {
  static final String CELL_PREFIX = "market:v1:auction-cell:";
  static final String LISTINGS_PREFIX = "market:v1:auction-listings:";
  static final Duration CELL_TTL = Duration.ofMinutes(3);
  static final Duration LISTINGS_TTL = Duration.ofMinutes(1);
  private static final Logger log = LoggerFactory.getLogger(AuctionStore.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final JdbcClient jdbc;
  private final Clock clock;

  AuctionStore(StringRedisTemplate redis, JsonMapper mapper, JdbcClient jdbc, Clock clock) {
    this.redis = redis;
    this.mapper = mapper;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public Optional<AuctionCell> findCell(String key) {
    return read(CELL_PREFIX + key, AuctionCell.class);
  }

  @Override
  public void saveCell(String key, AuctionCell cell) {
    write(CELL_PREFIX + key, cell, CELL_TTL);
  }

  @Override
  public Optional<AuctionPage> findListings(String key) {
    return read(LISTINGS_PREFIX + key, AuctionPage.class);
  }

  @Override
  public void saveListings(String key, AuctionPage page) {
    write(LISTINGS_PREFIX + key, page, LISTINGS_TTL);
  }

  @Override
  public void recordDaily(String key, LocalDate day, AuctionCell cell) {
    jdbc.sql(
            """
        INSERT INTO auction_price_daily (cell_key, kst_date, min_buy_price, total_count, observed_at)
        VALUES (:key, :day, :price, :count, :at)
        ON CONFLICT (cell_key, kst_date) DO UPDATE SET
          min_buy_price = EXCLUDED.min_buy_price, total_count = EXCLUDED.total_count,
          observed_at = EXCLUDED.observed_at
        WHERE auction_price_daily.observed_at <= EXCLUDED.observed_at
        """)
        .param("key", key)
        .param("day", day)
        .param("price", cell.minBuyPrice())
        .param("count", cell.totalCount())
        .param("at", cell.observedAt().atOffset(ZoneOffset.UTC))
        .update();
  }

  @Override
  public Map<String, Long> previousDay(List<String> keys, LocalDate day) {
    var result = new HashMap<String, Long>();
    if (keys.isEmpty()) return result;
    jdbc.sql(
            "SELECT cell_key, min_buy_price FROM auction_price_daily WHERE kst_date = :day AND cell_key IN (:keys)")
        .param("day", day.minusDays(1))
        .param("keys", keys)
        .query(
            (java.sql.ResultSet row) -> {
              result.put(row.getString(1), row.getLong(2));
            });
    return result;
  }

  private <T> Optional<T> read(String key, Class<T> type) {
    try {
      var json = redis.opsForValue().get(key);
      return json == null ? Optional.empty() : Optional.ofNullable(mapper.readValue(json, type));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  private void write(String key, Object value, Duration ttl) {
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
    } catch (DataAccessException | JacksonException exception) {
      log.warn("경매장 캐시를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }
}
