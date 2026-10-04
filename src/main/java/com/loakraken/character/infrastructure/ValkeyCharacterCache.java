package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCache;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterResult;
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

  ValkeyCharacterCache(StringRedisTemplate redis, JsonMapper mapper, LostArkProperties properties) {
    this.redis = redis;
    this.mapper = mapper;
    this.properties = properties;
  }

  @Override
  public Optional<CharacterResult> find(String name) {
    try {
      String json = redis.opsForValue().get(key(name));
      if (json == null) return Optional.empty();
      try {
        var result = mapper.readValue(json, CharacterResult.class);
        if (result != null && result.details() != null && result.fetchedAt() != null)
          return Optional.of(result);
      } catch (JacksonException ignored) {
        // 손상되거나 이전 버전인 캐시는 제거한 뒤 정상 경로로 다시 조회한다.
      }
      redis.delete(key(name));
      return Optional.empty();
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  @Override
  public void save(String name, CharacterResult result) {
    try {
      redis
          .opsForValue()
          .set(key(name), mapper.writeValueAsString(result), properties.summaryTtl());
    } catch (DataAccessException | JacksonException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  private String key(String name) {
    return "character:v3:" + name + ":summary";
  }
}
