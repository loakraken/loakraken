package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCache;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterMetrics;
import com.loakraken.character.application.CharacterMetrics.CacheOutcome;
import com.loakraken.character.application.CharacterResult;
import com.loakraken.common.lostark.LostArkProperties;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class ValkeyCharacterCache implements CharacterCache {
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
  public Optional<CharacterResult> find(String name) {
    try {
      String json = redis.opsForValue().get(key(name));
      if (json == null) {
        metrics.cache(CacheOutcome.MISS);
        return Optional.empty();
      }
      try {
        var result = mapper.readValue(json, CharacterResult.class);
        if (result != null && result.details() != null && result.fetchedAt() != null) {
          metrics.cache(CacheOutcome.HIT);
          return Optional.of(result);
        }
      } catch (JacksonException ignored) {
        // 손상되거나 이전 버전인 캐시는 제거한 뒤 정상 경로로 다시 조회한다.
      }
      metrics.cache(CacheOutcome.INVALID);
      redis.delete(key(name));
      metrics.cache(CacheOutcome.MISS);
      return Optional.empty();
    } catch (DataAccessException exception) {
      metrics.cache(CacheOutcome.READ_ERROR);
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  @Override
  public void save(String name, CharacterResult result) {
    try {
      redis
          .opsForValue()
          .set(key(name), mapper.writeValueAsString(result), properties.summaryTtl());
      metrics.cache(CacheOutcome.WRITE_SUCCESS);
    } catch (DataAccessException | JacksonException exception) {
      metrics.cache(CacheOutcome.WRITE_ERROR);
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  private String key(String name) {
    return "character:v3:" + name + ":summary";
  }
}
