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
  private static final String RELIC_BOOK_ICONS_KEY = "market:v1:relic-book-icons";
  private static final String REFINING_MATERIALS_KEY = "market:v1:refining-materials";

  /** 뒤에 분류 코드를 붙인다(생활 90000·전투 용품 60000). */
  private static final String LIFE_MATERIALS_KEY = "market:v1:category-scan:";

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
                && fresh(result.observedAt(), pricesTtl(search)));
  }

  public void savePrices(MarketResult result) {
    write(
        priceKey(result.source(), result.search()),
        result,
        pricesTtl(result.search()),
        Kind.PRICES);
  }

  /** 천천히 바뀌는 분류(아바타·장비 상자)는 가격 캐시를 길게 둔다(#11 공식 호출 예산). */
  private Duration pricesTtl(MarketSearch search) {
    return MarketTab.slowCategory(search.categoryCode())
        ? properties.slowPricesTtl()
        : properties.pricesTtl();
  }

  /** 천천히 바뀌는 탭은 탭 전체 스캔도 그 가격 캐시와 같은 시간 둔다. */
  private Duration boardScanTtl(MarketTab tab) {
    return tab.slowMoving() ? properties.slowPricesTtl() : properties.boardScanTtl();
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
                && fresh(scan.observedAt(), boardScanTtl(tab)));
  }

  public void saveBoardScan(MarketBoardScan scan) {
    write(boardKey(scan.tab(), scan.tier()), scan, boardScanTtl(scan.tab()), Kind.BOARD_SCAN);
  }

  /**
   * 거래량 지도 TTL: 보드가 거슬러 찾는 7일 + 하루 여유. 돌림 탭(아바타)은 한 바퀴에 약 5일이라 그 사이 값을 날짜와 함께 보이려고 늘렸다(PM 11-13,
   * 11-08의 ≤ 36시간에서 바꿈). 같은 공식 일별 거래량은 이미 일별 시세 표(market_price_daily)에 지우지 않고 남는다.
   */
  static final Duration VOLUME_TTL = Duration.ofDays(8);

  /** 하루 공식 호출 카운터 TTL(그날 + 여유). */
  static final Duration VOLUME_CALLS_TTL = Duration.ofHours(36);

  /** 돌림 탭 위치 TTL. 한 바퀴보다 넉넉히. 없어지면 1페이지부터 다시 돈다. */
  static final Duration VOLUME_CURSOR_TTL = Duration.ofDays(30);

  private static String volumeKey(java.time.LocalDate day) {
    return "market:v1:volume:" + day;
  }

  private static String volumeCallsKey(java.time.LocalDate day) {
    return "market:v1:volume-calls:" + day;
  }

  @Override
  public Map<Long, com.loakraken.market.domain.MarketVolume> findVolumes(
      java.time.LocalDate day, Collection<Long> itemIds) {
    if (itemIds.isEmpty()) return Map.of();
    var ids = List.copyOf(itemIds);
    try {
      var values =
          redis
              .<String, String>opsForHash()
              .multiGet(volumeKey(day), ids.stream().map(String::valueOf).toList());
      var result = new LinkedHashMap<Long, com.loakraken.market.domain.MarketVolume>();
      for (int i = 0; i < ids.size(); i++) {
        var value = values.get(i);
        if (value == null) continue;
        var parts = value.split(",");
        if (parts.length != 3) continue;
        try {
          result.put(
              ids.get(i),
              new com.loakraken.market.domain.MarketVolume(
                  ids.get(i),
                  java.time.LocalDate.parse(parts[0]),
                  Long.parseLong(parts[1]),
                  Long.parseLong(parts[2])));
        } catch (RuntimeException ignored) {
          // 모양이 다른 값은 없는 것으로 본다.
        }
      }
      return result;
    } catch (DataAccessException exception) {
      metrics.cache(Kind.VOLUME, Outcome.READ_ERROR);
      return Map.of();
    }
  }

  @Override
  public void saveVolumes(
      java.time.LocalDate day, Collection<com.loakraken.market.domain.MarketVolume> volumes) {
    if (volumes.isEmpty()) return;
    var fields = new LinkedHashMap<String, String>();
    for (var volume : volumes)
      fields.put(
          String.valueOf(volume.itemId()),
          volume.volumeDate() + "," + volume.yesterday() + "," + volume.sevenDay());
    try {
      redis.opsForHash().putAll(volumeKey(day), fields);
      redis.expire(volumeKey(day), VOLUME_TTL);
    } catch (DataAccessException exception) {
      metrics.cache(Kind.VOLUME, Outcome.WRITE_ERROR);
    }
  }

  @Override
  public long addVolumeCalls(java.time.LocalDate day, int calls) {
    try {
      Long count = redis.opsForValue().increment(volumeCallsKey(day), calls);
      redis.expire(volumeCallsKey(day), VOLUME_CALLS_TTL);
      return count == null ? Long.MAX_VALUE : count;
    } catch (DataAccessException exception) {
      metrics.cache(Kind.VOLUME, Outcome.WRITE_ERROR);
      return Long.MAX_VALUE;
    }
  }

  private static String volumeCursorKey(com.loakraken.market.domain.MarketTab tab) {
    return "market:v1:volume-cursor:" + tab.name();
  }

  @Override
  public Optional<com.loakraken.market.application.MarketVolumeCursor> findVolumeCursor(
      com.loakraken.market.domain.MarketTab tab) {
    try {
      var value = redis.opsForValue().get(volumeCursorKey(tab));
      if (value == null) return Optional.empty();
      var parts = value.split(",");
      if (parts.length != 2) return Optional.empty();
      return Optional.of(
          new com.loakraken.market.application.MarketVolumeCursor(
              Integer.parseInt(parts[0]), java.time.LocalDate.parse(parts[1])));
    } catch (DataAccessException exception) {
      metrics.cache(Kind.VOLUME, Outcome.READ_ERROR);
      return Optional.empty();
    } catch (RuntimeException exception) {
      // 모양이 다른 값은 없는 것으로 본다(1페이지부터).
      return Optional.empty();
    }
  }

  @Override
  public void saveVolumeCursor(
      com.loakraken.market.domain.MarketTab tab,
      com.loakraken.market.application.MarketVolumeCursor cursor) {
    try {
      redis
          .opsForValue()
          .set(
              volumeCursorKey(tab),
              cursor.page() + "," + cursor.cycleStartedOn(),
              VOLUME_CURSOR_TTL);
    } catch (DataAccessException exception) {
      metrics.cache(Kind.VOLUME, Outcome.WRITE_ERROR);
    }
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

  /** 만료 없는 해시(volatile-lru에서 지워지지 않음). 읽지 못하면 빈 값. */
  @Override
  public java.util.Map<String, String> findRelicBookIcons() {
    try {
      var result = new java.util.HashMap<String, String>();
      redis
          .opsForHash()
          .entries(RELIC_BOOK_ICONS_KEY)
          .forEach((name, icon) -> result.put(name.toString(), icon.toString()));
      return result;
    } catch (DataAccessException exception) {
      return java.util.Map.of();
    }
  }

  @Override
  public void saveRelicBookIcons(java.util.Map<String, String> icons) {
    if (icons.isEmpty()) return;
    try {
      redis.opsForHash().putAll(RELIC_BOOK_ICONS_KEY, icons);
    } catch (DataAccessException exception) {
      // 아이콘 저장 실패는 시세에 영향이 없다(다음 스캔에서 다시 씀).
    }
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
  public Optional<com.loakraken.market.application.LifeMaterialScan> findLifeMaterials(
      int category) {
    // 만료는 키 TTL(예열 주기 × 3)로 정한다.
    return read(
        LIFE_MATERIALS_KEY + category,
        com.loakraken.market.application.LifeMaterialScan.class,
        Kind.LIFE_MATERIALS,
        scan -> true);
  }

  @Override
  public void saveLifeMaterials(
      int category, com.loakraken.market.application.LifeMaterialScan scan, Duration ttl) {
    write(LIFE_MATERIALS_KEY + category, scan, ttl, Kind.LIFE_MATERIALS);
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
