package com.loakraken.receipt.infrastructure;

import com.loakraken.receipt.application.ReceiptInputStore;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 입력 `receipt:v1:input:{캐릭터}`. 보관 시간은 receipt.input-ttl(기본 5분 = 개인정보 처리방침의 원정대 불러오기 임시 보관 시간).
 * 더 길게 두려면 처리방침 문구가 먼저 바뀌어야 한다.
 */
@Component
class ValkeyReceiptInputStore implements ReceiptInputStore {
  static final String PREFIX = "receipt:v1:input:";
  private static final Logger log = LoggerFactory.getLogger(ValkeyReceiptInputStore.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final Duration ttl;

  ValkeyReceiptInputStore(
      StringRedisTemplate redis,
      JsonMapper mapper,
      @Value("${receipt.input-ttl:PT5M}") Duration ttl) {
    this.redis = redis;
    this.mapper = mapper;
    this.ttl = ttl;
  }

  @Override
  public Optional<Input> find(String characterName) {
    try {
      var json = redis.opsForValue().get(PREFIX + characterName);
      return json == null
          ? Optional.empty()
          : Optional.ofNullable(mapper.readValue(json, Input.class));
    } catch (DataAccessException | JacksonException | IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void delete(java.util.Collection<String> characterNames) {
    if (characterNames.isEmpty()) return;
    try {
      redis.delete(characterNames.stream().map(name -> PREFIX + name).toList());
    } catch (DataAccessException exception) {
      log.warn("영수증 입력을 지우지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  @Override
  public void save(String characterName, Input input) {
    try {
      redis.opsForValue().set(PREFIX + characterName, mapper.writeValueAsString(input), ttl);
    } catch (DataAccessException | JacksonException exception) {
      log.warn("영수증 입력을 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }
}
