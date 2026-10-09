package com.loakraken.common.lostark;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** `lostark:leader:{name}` = 인스턴스 ID. 얻기는 SET NX PX, 연장·해제는 "값이 내 ID일 때만" Lua로 한다. */
public class ValkeyLeaderLease implements LeaderLease {
  static final String PREFIX = "lostark:leader:";
  private static final DefaultRedisScript<Long> HOLD =
      new DefaultRedisScript<>(
          """
          local current = redis.call('GET', KEYS[1])
          if not current then
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
          end
          if current == ARGV[1] then
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
          end
          return 0
          """,
          Long.class);
  private static final DefaultRedisScript<Long> RELEASE =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
          end
          return 0
          """,
          Long.class);
  private final StringRedisTemplate redis;
  private final String instanceId;

  public ValkeyLeaderLease(StringRedisTemplate redis) {
    this(redis, UUID.randomUUID().toString());
  }

  ValkeyLeaderLease(StringRedisTemplate redis, String instanceId) {
    this.redis = redis;
    this.instanceId = instanceId;
  }

  @Override
  public boolean hold(String name, Duration ttl) {
    try {
      Long result =
          redis.execute(HOLD, List.of(PREFIX + name), instanceId, String.valueOf(ttl.toMillis()));
      return result != null && result == 1;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  @Override
  public void release(String name) {
    try {
      redis.execute(RELEASE, List.of(PREFIX + name), instanceId);
    } catch (RuntimeException ignored) {
      // 임대는 TTL로 끝난다.
    }
  }
}
