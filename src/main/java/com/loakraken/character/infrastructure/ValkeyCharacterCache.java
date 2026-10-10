package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCache;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterMetrics;
import com.loakraken.character.application.CharacterMetrics.CacheOutcome;
import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CharacterSiblingsResult;
import com.loakraken.common.lostark.LostArkProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class ValkeyCharacterCache implements CharacterCache {
  /** 없는 캐릭터 기억 시간(#108). 새로 만든 캐릭터는 최대 이만큼 늦게 보인다. */
  static final Duration MISSING_TTL = Duration.ofMinutes(5);

  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final LostArkProperties properties;
  private final CharacterMetrics metrics;

  ValkeyCharacterCache(
      StringRedisTemplate redis,
      JsonMapper mapper,
      LostArkProperties properties,
      CharacterMetrics metrics) {
    this.redis = redis;
    this.mapper = mapper;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Override
  public Duration ttl() {
    return properties.summaryTtl();
  }

  @Override
  public Optional<CharacterResult> find(String name) {
    return read(
        summaryKey(name),
        CharacterResult.class,
        result ->
            result.details() != null
                && result.fetchedAt() != null
                && result.details().siblings() != null
                && result.details().coverage().siblings() != null);
  }

  @Override
  public void save(String name, CharacterResult result) {
    write(summaryKey(name), result);
  }

  @Override
  public Optional<CharacterResult> findArmory(String name) {
    return read(
        armoryKey(name),
        CharacterResult.class,
        result ->
            result.details() != null
                && result.fetchedAt() != null
                && result.details().siblings() == null);
  }

  @Override
  public void saveArmory(String name, CharacterResult result) {
    write(armoryKey(name), result);
  }

  @Override
  public Optional<CharacterSiblingsResult> findSiblings(String name) {
    return read(siblingsKey(name), CharacterSiblingsResult.class, result -> true);
  }

  @Override
  public void saveSiblings(String name, CharacterSiblingsResult result) {
    write(siblingsKey(name), result);
  }

  @Override
  public boolean isMissing(String name) {
    try {
      boolean missing = Boolean.TRUE.equals(redis.hasKey(missingKey(name)));
      if (missing) metrics.cache(CacheOutcome.NOT_FOUND_HIT);
      return missing;
    } catch (DataAccessException exception) {
      metrics.cache(CacheOutcome.READ_ERROR);
      return false;
    }
  }

  @Override
  public void markMissing(String name) {
    try {
      redis.opsForValue().set(missingKey(name), "1", MISSING_TTL);
    } catch (DataAccessException exception) {
      metrics.cache(CacheOutcome.WRITE_ERROR);
    }
  }

  private <T> Optional<T> read(String key, Class<T> type, Predicate<T> complete) {
    try {
      String json = redis.opsForValue().get(key);
      if (json == null) {
        metrics.cache(CacheOutcome.MISS);
        return Optional.empty();
      }
      try {
        var result = mapper.readValue(json, type);
        if (result != null && complete.test(result)) {
          metrics.cache(CacheOutcome.HIT);
          return Optional.of(result);
        }
      } catch (JacksonException ignored) {
        // 손상되거나 이전 버전인 캐시는 제거한 뒤 정상 경로로 다시 조회한다.
      }
      metrics.cache(CacheOutcome.INVALID);
      redis.delete(key);
      metrics.cache(CacheOutcome.MISS);
      return Optional.empty();
    } catch (DataAccessException exception) {
      metrics.cache(CacheOutcome.READ_ERROR);
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  private void write(String key, Object result) {
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(result), properties.summaryTtl());
      metrics.cache(CacheOutcome.WRITE_SUCCESS);
    } catch (DataAccessException | JacksonException exception) {
      metrics.cache(CacheOutcome.WRITE_ERROR);
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  private String summaryKey(String name) {
    return "character:v11:" + name + ":summary";
  }

  private String armoryKey(String name) {
    return "character:v11:" + name + ":armory";
  }

  private String missingKey(String name) {
    return "character:v1:" + name + ":missing";
  }

  private String siblingsKey(String name) {
    return "character:v1:" + name + ":siblings";
  }
}
