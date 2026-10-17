package com.loakraken.market.infrastructure;

import com.loakraken.market.application.AccessorySearch;
import com.loakraken.market.application.AccessorySearchStorePort;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 악세 경매 검색기(N13) 결과 캐시 `market:v2:auction-search:{키}`와 기능 몫 카운터
 * `market:v1:auction-search-quota:{분}`(1분 고정 창, 모든 인스턴스 공유). 캐시 장애는 검색을 막지 않고, 몫 카운터 장애는 공식 예산을
 * 지키려고 거절한다.
 */
@Component
class ValkeyAccessorySearchStore implements AccessorySearchStorePort {
  // v2: 매물 이미지·기본 효과가 더해져(#524) 옛 결과(v1)는 쓰지 않는다.
  static final String CACHE_PREFIX = "market:v2:auction-search:";
  static final String QUOTA_PREFIX = "market:v1:auction-search-quota:";
  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final Logger log = LoggerFactory.getLogger(ValkeyAccessorySearchStore.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final Clock clock;

  ValkeyAccessorySearchStore(StringRedisTemplate redis, JsonMapper mapper, Clock clock) {
    this.redis = redis;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  public Optional<AccessorySearch.Result> find(String key) {
    try {
      var json = redis.opsForValue().get(CACHE_PREFIX + key);
      return json == null
          ? Optional.empty()
          : Optional.ofNullable(mapper.readValue(json, AccessorySearch.Result.class));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void save(String key, AccessorySearch.Result result, Duration ttl) {
    try {
      redis.opsForValue().set(CACHE_PREFIX + key, mapper.writeValueAsString(result), ttl);
    } catch (DataAccessException | JacksonException exception) {
      log.warn("경매 검색 캐시를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  @Override
  public boolean tryAcquireQuota(int limit) {
    String key = QUOTA_PREFIX + clock.millis() / WINDOW.toMillis();
    try {
      Long count = redis.opsForValue().increment(key);
      if (count == null) return false;
      if (count == 1) redis.expire(key, WINDOW.plusSeconds(1));
      return count <= limit;
    } catch (DataAccessException exception) {
      log.warn("경매 검색 몫 카운터를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return false;
    }
  }
}
