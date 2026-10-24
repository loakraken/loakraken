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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 경매장 캐시·일별 기록. 칸 캐시 `market:v1:auction-cell:{키}`(3분), 보석 칸 마지막 값 `market:v1:auction-last:{키}`(만료
 * 없음), 매물 `market:v1:auction-listings:{키}:{페이지}`(1분), 일별 최저가는 Postgres `auction_price_daily`. 캐시
 * 장애는 조회를 막지 않는다.
 */
@Component
class AuctionStore implements AuctionPort {
  static final String CELL_PREFIX = "market:v1:auction-cell:";
  static final String LISTINGS_PREFIX = "market:v1:auction-listings:";
  static final String LAST_PREFIX = "market:v1:auction-last:";
  static final String GEM_FILTER_QUOTA_PREFIX = "market:v1:auction-gem-filter-quota:";
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

  /** 마지막 값은 해시 하나: at = 관측 시각(epoch ms), v = 칸 JSON. */
  @Override
  public Optional<AuctionCell> findLastCell(String key) {
    try {
      Object json = redis.opsForHash().get(LAST_PREFIX + key, "v");
      return json == null
          ? Optional.empty()
          : Optional.ofNullable(mapper.readValue(json.toString(), AuctionCell.class));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  /**
   * 저장된 값보다 새것이거나 같은 시각일 때만 바꾼다(ARGV[1] = 관측 epoch ms). 늦게 끝난 옛 관측이 새 값을 덮으면 3분 칸이 비었을 때 옛 시세가
   * 보였다(코드 리뷰 10-25).
   */
  private static final DefaultRedisScript<Long> LAST_CELL_SET =
      new DefaultRedisScript<>(
          """
          local at = redis.call('HGET', KEYS[1], 'at')
          if at and tonumber(at) > tonumber(ARGV[1]) then return 0 end
          redis.call('HSET', KEYS[1], 'at', ARGV[1], 'v', ARGV[2])
          return 1
          """,
          Long.class);

  /** 만료 없이 둔다: volatile-lru에서 만료 키만 지워지므로 메모리가 모자라도 마지막 값은 남는다. 관측 시각이 없는 칸은 남기지 않는다. */
  @Override
  public void saveLastCell(String key, AuctionCell cell) {
    if (cell == null || cell.observedAt() == null) return;
    try {
      redis.execute(
          LAST_CELL_SET,
          List.of(LAST_PREFIX + key),
          String.valueOf(cell.observedAt().toEpochMilli()),
          mapper.writeValueAsString(cell));
    } catch (DataAccessException | JacksonException exception) {
      log.warn("경매장 마지막 값을 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  /**
   * 한도(ARGV[2]) 안일 때만 올리고 첫 만료를 한 번에 건다(#553). 이미 한도면 올리지 않고 -1(보안 점검 10-18: 거절된 요청까지 올리면 카운터가 부풀어,
   * 공식 호출 전에 거절돼 몫을 돌려줘도 실제 자리가 비지 않았다).
   */
  private static final DefaultRedisScript<Long> QUOTA_INCREMENT =
      new DefaultRedisScript<>(
          """
          local count = tonumber(redis.call('GET', KEYS[1]) or '0')
          if count >= tonumber(ARGV[2]) then return -1 end
          count = redis.call('INCR', KEYS[1])
          if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
          return count
          """,
          Long.class);

  /** 0보다 클 때만 1 내린다(키가 이미 사라졌으면 아무것도 하지 않음). */
  private static final DefaultRedisScript<Long> QUOTA_RELEASE =
      new DefaultRedisScript<>(
          """
          local count = tonumber(redis.call('GET', KEYS[1]) or '0')
          if count > 0 then return redis.call('DECR', KEYS[1]) end
          return 0
          """,
          Long.class);

  static final Duration GEM_FILTER_QUOTA_TTL = Duration.ofSeconds(61);

  @Override
  public Optional<String> acquireGemFilterQuota(int limit) {
    String key = GEM_FILTER_QUOTA_PREFIX + clock.millis() / 60_000;
    try {
      Long count =
          redis.execute(
              QUOTA_INCREMENT,
              java.util.List.of(key),
              String.valueOf(GEM_FILTER_QUOTA_TTL.toMillis()),
              String.valueOf(limit));
      return count != null && count > 0 && count <= limit ? Optional.of(key) : Optional.empty();
    } catch (DataAccessException exception) {
      log.warn("보석 필터 몫 카운터를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public void releaseGemFilterQuota(String key) {
    if (key == null || !key.startsWith(GEM_FILTER_QUOTA_PREFIX)) return;
    try {
      redis.execute(QUOTA_RELEASE, java.util.List.of(key));
    } catch (DataAccessException exception) {
      log.warn("보석 필터 몫을 돌려주지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
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
