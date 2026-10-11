package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.RosterRefreshCooldownPort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** roster-cp:v1:refresh:{원정대 해시}. SET NX PX로 잡는다(카드 불러오기와 같은 방식, 키는 따로). */
@Component
class ValkeyRosterRefreshCooldown implements RosterRefreshCooldownPort {
  static final String PREFIX = "roster-cp:v1:refresh:";
  private final StringRedisTemplate redis;

  ValkeyRosterRefreshCooldown(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Optional<Duration> acquire(String rosterKey, Duration cooldown) {
    try {
      String key = PREFIX + rosterKey;
      var acquired = redis.opsForValue().setIfAbsent(key, "1", cooldown);
      if (Boolean.TRUE.equals(acquired)) return Optional.empty();
      return Optional.of(left(key).orElse(Duration.ofSeconds(1)));
    } catch (DataAccessException exception) {
      // 쿨다운을 확인할 수 없으면 공식 호출 예산을 지키기 위해 받지 않는다.
      throw new CharacterException(CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE);
    }
  }

  @Override
  public Optional<Duration> remaining(String rosterKey) {
    try {
      return left(PREFIX + rosterKey);
    } catch (DataAccessException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void release(String rosterKey) {
    try {
      redis.delete(PREFIX + rosterKey);
    } catch (DataAccessException ignored) {
      // 풀지 못하면 쿨다운이 끝날 때 풀린다.
    }
  }

  private Optional<Duration> left(String key) {
    Long millis = redis.getExpire(key, TimeUnit.MILLISECONDS);
    return millis == null || millis <= 0
        ? Optional.empty()
        : Optional.of(Duration.ofMillis(millis));
  }
}
