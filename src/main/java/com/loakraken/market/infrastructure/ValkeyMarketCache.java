package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.*;
import com.loakraken.market.application.MarketMetrics.Kind;
import com.loakraken.market.application.MarketMetrics.Outcome;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class ValkeyMarketCache implements MarketCache {
  private static final String OPTIONS_KEY = "market:v1:options";
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final MarketCacheProperties properties;
  private final MarketMetrics metrics;
  private final Clock clock;

  ValkeyMarketCache(
      StringRedisTemplate redis,
      JsonMapper mapper,
      MarketCacheProperties properties,
      MarketMetrics metrics,
      Clock clock) {
    this.redis = redis;
    this.mapper = mapper;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  public Optional<MarketResult> findPrices(MarketSource source, MarketSearch search) {
    return read(
        priceKey(source, search),
        MarketResult.class,
        Kind.PRICES,
        result ->
            result.source() == source
                && result.search().equals(search)
                && fresh(result.observedAt(), properties.pricesTtl()));
  }

  public void savePrices(MarketResult result) {
    write(priceKey(result.source(), result.search()), result, properties.pricesTtl(), Kind.PRICES);
  }

  public Optional<MarketOptionsResult> findOptions() {
    return read(
        OPTIONS_KEY,
        MarketOptionsResult.class,
        Kind.OPTIONS,
        result -> fresh(result.observedAt(), properties.optionsTtl()));
  }

  public void saveOptions(MarketOptionsResult result) {
    write(OPTIONS_KEY, result, properties.optionsTtl(), Kind.OPTIONS);
  }

  public Optional<MarketItemHistoryResult> findItemHistory(long itemId) {
    return read(
        historyKey(itemId),
        MarketItemHistoryResult.class,
        Kind.ITEM_HISTORY,
        result ->
            result.history() != null
                && result.history().itemId() == itemId
                && fresh(result.observedAt(), properties.historyTtl()));
  }

  public void saveItemHistory(MarketItemHistoryResult result) {
    write(
        historyKey(result.history().itemId()), result, properties.historyTtl(), Kind.ITEM_HISTORY);
  }

  private static String historyKey(long itemId) {
    return "market:v1:item-history:" + itemId;
  }

  private boolean fresh(Instant observedAt, Duration ttl) {
    var now = clock.instant();
    return !observedAt.isAfter(now) && observedAt.isAfter(now.minus(ttl));
  }

  private <T> Optional<T> read(String key, Class<T> type, Kind kind, Predicate<T> valid) {
    try {
      var json = redis.opsForValue().get(key);
      if (json == null) {
        metrics.cache(kind, Outcome.MISS);
        return Optional.empty();
      }
      try {
        var value = mapper.readValue(json, type);
        if (value != null && valid.test(value)) {
          metrics.cache(kind, Outcome.HIT);
          return Optional.of(value);
        }
      } catch (JacksonException | IllegalArgumentException ignored) {
        // 손상 데이터는 삭제 성공 후에만 미적중으로 처리한다.
      }
      metrics.cache(kind, Outcome.INVALID);
      redis.delete(key);
      metrics.cache(kind, Outcome.MISS);
      return Optional.empty();
    } catch (DataAccessException exception) {
      metrics.cache(kind, Outcome.READ_ERROR);
      throw unavailable();
    }
  }

  private void write(String key, Object result, Duration ttl, Kind kind) {
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(result), ttl);
      metrics.cache(kind, Outcome.WRITE_SUCCESS);
    } catch (DataAccessException | JacksonException exception) {
      metrics.cache(kind, Outcome.WRITE_ERROR);
      throw unavailable();
    }
  }

  private String priceKey(MarketSource source, MarketSearch search) {
    try {
      var bytes = mapper.writeValueAsBytes(new PriceKey(source, search));
      return "market:v1:prices:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
    }
  }

  private BusinessException unavailable() {
    return new BusinessException(MarketErrorCode.MARKET_CACHE_UNAVAILABLE);
  }

  private record PriceKey(MarketSource source, MarketSearch search) {}
}
