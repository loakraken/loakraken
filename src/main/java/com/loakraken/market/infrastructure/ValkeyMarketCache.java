package com.loakraken.market.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.*;
import com.loakraken.market.application.MarketMetrics.Kind;
import com.loakraken.market.application.MarketMetrics.Outcome;
import com.loakraken.market.domain.MarketTab;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private static final String RELIC_BOOKS_KEY = "market:v1:relic-books";
  private static final String REFINING_MATERIALS_KEY = "market:v1:refining-materials";
  private static final String LIFE_MATERIALS_KEY = "market:v1:life-materials";
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

  @Override
  public Map<Long, MarketItemHistoryResult> findItemHistories(Collection<Long> itemIds) {
    var ids = itemIds.stream().distinct().toList();
    if (ids.isEmpty()) return Map.of();
    List<String> values;
    try {
      values =
          redis.opsForValue().multiGet(ids.stream().map(ValkeyMarketCache::historyKey).toList());
    } catch (DataAccessException exception) {
      metrics.cache(Kind.ITEM_HISTORY, Outcome.READ_ERROR);
      throw unavailable();
    }
    var result = new LinkedHashMap<Long, MarketItemHistoryResult>();
    for (int i = 0; i < ids.size(); i++) {
      var json = values == null ? null : values.get(i);
      if (json == null) {
        metrics.cache(Kind.ITEM_HISTORY, Outcome.MISS);
        continue;
      }
      try {
        var value = mapper.readValue(json, MarketItemHistoryResult.class);
        if (value != null
            && value.history() != null
            && value.history().itemId() == ids.get(i)
            && fresh(value.observedAt(), properties.historyTtl())) {
          metrics.cache(Kind.ITEM_HISTORY, Outcome.HIT);
          result.put(ids.get(i), value);
          continue;
        }
      } catch (JacksonException | IllegalArgumentException ignored) {
        // 보드 보강은 읽기 전용이다. 손상 항목은 단건 조회 경로가 정리한다.
      }
      metrics.cache(Kind.ITEM_HISTORY, Outcome.INVALID);
    }
    return result;
  }

  public Optional<MarketBoardScan> findBoardScan(MarketTab tab, Integer tier) {
    return read(
        boardKey(tab, tier),
        MarketBoardScan.class,
        Kind.BOARD_SCAN,
        scan ->
            scan.tab() == tab
                && Objects.equals(scan.tier(), tier)
                && fresh(scan.observedAt(), properties.boardScanTtl()));
  }

  public void saveBoardScan(MarketBoardScan scan) {
    write(boardKey(scan.tab(), scan.tier()), scan, properties.boardScanTtl(), Kind.BOARD_SCAN);
  }

  @Override
  public Optional<RelicBookScan> findRelicBooks() {
    // 만료는 키 TTL(예열 주기 × 3)로 정한다.
    return read(RELIC_BOOKS_KEY, RelicBookScan.class, Kind.RELIC_BOOKS, scan -> true);
  }

  @Override
  public void saveRelicBooks(RelicBookScan scan, Duration ttl) {
    write(RELIC_BOOKS_KEY, scan, ttl, Kind.RELIC_BOOKS);
  }

  @Override
  public Optional<com.loakraken.market.application.RefiningMaterialScan> findRefiningMaterials() {
    // 만료는 키 TTL(예열 주기 × 3)로 정한다.
    return read(
        REFINING_MATERIALS_KEY,
        com.loakraken.market.application.RefiningMaterialScan.class,
        Kind.REFINING_MATERIALS,
        scan -> true);
  }

  @Override
  public void saveRefiningMaterials(
      com.loakraken.market.application.RefiningMaterialScan scan, Duration ttl) {
    write(REFINING_MATERIALS_KEY, scan, ttl, Kind.REFINING_MATERIALS);
  }

  @Override
  public Optional<com.loakraken.market.application.LifeMaterialScan> findLifeMaterials() {
    // 만료는 키 TTL(예열 주기 × 3)로 정한다.
    return read(
        LIFE_MATERIALS_KEY,
        com.loakraken.market.application.LifeMaterialScan.class,
        Kind.LIFE_MATERIALS,
        scan -> true);
  }

  @Override
  public void saveLifeMaterials(
      com.loakraken.market.application.LifeMaterialScan scan, Duration ttl) {
    write(LIFE_MATERIALS_KEY, scan, ttl, Kind.LIFE_MATERIALS);
  }

  private static String boardKey(MarketTab tab, Integer tier) {
    return "market:v1:board-scan:" + tab.name() + ":" + (tier == null ? "all" : tier);
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
