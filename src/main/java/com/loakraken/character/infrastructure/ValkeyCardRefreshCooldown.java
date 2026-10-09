package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CardRefreshCooldownPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** cards:v1:refresh:{원정대 해시}. SET NX PX로 잡는다. */
@Component
class ValkeyCardRefreshCooldown implements CardRefreshCooldownPort {
  static final String PREFIX = "cards:v1:refresh:";
  private final StringRedisTemplate redis;

  ValkeyCardRefreshCooldown(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Optional<Duration> acquire(String rosterKey, Duration cooldown) {
    try {
      String key = PREFIX + rosterKey;
      var acquired = redis.opsForValue().setIfAbsent(key, "1", cooldown);
      if (Boolean.TRUE.equals(acquired)) return Optional.empty();
      Long millis = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);
      return Optional.of(
          millis == null || millis <= 0 ? Duration.ofSeconds(1) : Duration.ofMillis(millis));
    } catch (DataAccessException exception) {
      // 쿨다운을 확인할 수 없으면 공식 호출 예산을 지키기 위해 받지 않는다.
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  @Override
  public void release(String rosterKey) {
    try {
      redis.delete(PREFIX + rosterKey);
    } catch (DataAccessException ignored) {
      // 풀지 못하면 쿨다운(10분)이 끝날 때 풀린다.
    }
  }
}
