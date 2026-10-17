package com.loakraken.character.infrastructure;

import com.loakraken.character.application.ArmoryPageStorePort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
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
 * armory-page:v1:{캐릭터 이름}(생활 스킬)과 armory-page:v1:code:{캐릭터 이름}(스킬 코드) — 전투정보실 페이지에서 뽑은 값만(#531·#532).
 * 원문 HTML과 페이지 식별 값은 저장하지 않는다.
 */
@Component
class ValkeyArmoryPageStore implements ArmoryPageStorePort {
  static final String PREFIX = "armory-page:v1:";
  static final String CODE_PREFIX = "armory-page:v1:code:";
  private static final Logger log = LoggerFactory.getLogger(ValkeyArmoryPageStore.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;

  ValkeyArmoryPageStore(StringRedisTemplate redis, JsonMapper mapper) {
    this.redis = redis;
    this.mapper = mapper;
  }

  @Override
  public Optional<Entry> find(String characterName) {
    String json;
    try {
      json = redis.opsForValue().get(PREFIX + characterName);
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
    if (json == null) return Optional.empty();
    try {
      return Optional.of(mapper.readValue(json, Entry.class));
    } catch (JacksonException exception) {
      // 읽지 못하는 옛 값은 없는 것으로 본다.
      return Optional.empty();
    }
  }

  @Override
  public void save(String characterName, Entry entry, Duration ttl) {
    write(PREFIX + characterName, entry, ttl);
  }

  @Override
  public Optional<CodeEntry> findCode(String characterName) {
    String json;
    try {
      json = redis.opsForValue().get(CODE_PREFIX + characterName);
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
    if (json == null) return Optional.empty();
    try {
      return Optional.of(mapper.readValue(json, CodeEntry.class));
    } catch (JacksonException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void saveCode(String characterName, CodeEntry entry, Duration ttl) {
    write(CODE_PREFIX + characterName, entry, ttl);
  }

  private void write(String key, Object value, Duration ttl) {
    try {
      redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
    } catch (DataAccessException | JacksonException exception) {
      log.warn(
          "event=armory_page_cache_write_failed type={}", exception.getClass().getSimpleName());
    }
  }
}
