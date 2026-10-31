package com.loakraken.receipt.infrastructure;

import com.loakraken.receipt.application.ReceiptInputStore;
import java.time.Clock;
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
 *
 * <p>보관 시간은 공식에서 받은 시각(관측 시각)부터 센다. 다시 저장해도(캐시 적중 등) 늘어나지 않고, 이미 지난 입력은 저장하지 않는다.
 */
@Component
class ValkeyReceiptInputStore implements ReceiptInputStore {
  static final String PREFIX = "receipt:v1:input:";

  /** 지우기 시도 횟수(처리정지 목록에 들기 전 입력). */
  static final int DELETE_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(ValkeyReceiptInputStore.class);
  private final StringRedisTemplate redis;
  private final JsonMapper mapper;
  private final Duration ttl;
  private final Clock clock;

  ValkeyReceiptInputStore(
      StringRedisTemplate redis,
      JsonMapper mapper,
      @Value("${receipt.input-ttl:PT5M}") Duration ttl,
      Clock clock) {
    this.redis = redis;
    this.mapper = mapper;
    this.ttl = ttl;
    this.clock = clock;
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

  /** 실패하면 몇 번 다시 시도하고, 끝내 실패하면 예외 유형만 남긴다(캐릭터 이름 없음). 남은 입력도 보관 시간이 지나면 사라진다. */
  @Override
  public void delete(java.util.Collection<String> characterNames) {
    if (characterNames.isEmpty()) return;
    var keys = characterNames.stream().map(name -> PREFIX + name).toList();
    for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
      try {
        redis.delete(keys);
        return;
      } catch (DataAccessException exception) {
        if (attempt == DELETE_ATTEMPTS)
          log.warn(
              "event=receipt_input_delete_failed attempts={} type={}",
              DELETE_ATTEMPTS,
              exception.getClass().getSimpleName());
      }
    }
  }

  @Override
  public void save(String characterName, Input input) {
    var now = clock.instant();
    var observedAt = input.observedAt() == null ? now : input.observedAt();
    var remaining = Duration.between(now, observedAt.plus(ttl));
    if (remaining.compareTo(ttl) > 0) remaining = ttl;
    if (remaining.isNegative() || remaining.isZero()) return;
    try {
      redis.opsForValue().set(PREFIX + characterName, mapper.writeValueAsString(input), remaining);
    } catch (DataAccessException | JacksonException exception) {
      log.warn("영수증 입력을 쓰지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }
}
