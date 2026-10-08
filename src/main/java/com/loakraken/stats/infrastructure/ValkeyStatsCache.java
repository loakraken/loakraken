package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.StatsCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** stats:v1:{sha256(조건)}. 조건 문자열에 서버·직업명이 들어가므로 해시로 키 길이를 고정한다. */
@Component
class ValkeyStatsCache implements StatsCache {
  static final String PREFIX = "stats:v1:";
  private static final Logger log = LoggerFactory.getLogger(ValkeyStatsCache.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final Duration ttl;

  ValkeyStatsCache(
      StringRedisTemplate redis, JsonMapper mapper, @Value("${stats.cache.ttl:10m}") Duration ttl) {
    this.redis = redis;
    this.mapper = mapper;
    this.ttl = ttl;
  }

  @Override
  public <T> Optional<T> find(String key, Class<T> type) {
    try {
      var json = redis.opsForValue().get(PREFIX + hash(key));
      if (json == null) return Optional.empty();
      return Optional.ofNullable(mapper.readValue(json, type));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      // 캐시가 없거나 이전 형식이면 다시 집계한다.
      log.debug("통계 캐시를 읽지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  @Override
  public void save(String key, Object value) {
    try {
      redis.opsForValue().set(PREFIX + hash(key), mapper.writeValueAsString(value), ttl);
    } catch (DataAccessException | JacksonException exception) {
      log.warn("통계 캐시를 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  private static String hash(String key) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
