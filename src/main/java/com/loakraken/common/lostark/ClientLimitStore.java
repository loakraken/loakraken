package com.loakraken.common.lostark;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** 고정 창 카운터. 키 하나가 창 하나다. */
interface ClientLimitStore {
  /** 키의 값을 1 올리고 올린 뒤 값을 돌려준다. 처음 만든 키에는 ttl을 건다. 저장소 장애면 {@link Unavailable}. */
  long increment(String key, Duration ttl);

  /** 키의 지금 값(없으면 0). 올리지 않는다. 저장소 장애면 {@link Unavailable}. 읽기를 못 하는 구현은 0(남은 칸 = 한도). */
  default long current(String key) {
    return 0;
  }

  final class Unavailable extends RuntimeException {
    Unavailable(Throwable cause) {
      super(cause);
    }
  }

  /** 게이트 전용 연결(명령 타임아웃 100ms)을 같이 쓴다. INCR과 PEXPIRE를 Lua 한 번으로 한다. */
  final class Valkey implements ClientLimitStore {
    private static final DefaultRedisScript<Long> INCREMENT =
        new DefaultRedisScript<>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return count
            """,
            Long.class);
    private final StringRedisTemplate redis;

    Valkey(StringRedisTemplate redis) {
      this.redis = redis;
    }

    @Override
    public long increment(String key, Duration ttl) {
      try {
        Long count =
            redis.execute(
                INCREMENT, java.util.List.of(key), String.valueOf(Math.max(1, ttl.toMillis())));
        if (count == null) throw new IllegalStateException("빈 응답");
        return count;
      } catch (RuntimeException exception) {
        throw new Unavailable(exception);
      }
    }

    @Override
    public long current(String key) {
      try {
        var value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
      } catch (RuntimeException exception) {
        throw new Unavailable(exception);
      }
    }
  }

  /** 단일 인스턴스(lostark.gate.store=memory). 창이 바뀌면 지난 창 키를 지운다. */
  final class Memory implements ClientLimitStore {
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private volatile String window = "";

    @Override
    public long increment(String key, Duration ttl) {
      String current = key.substring(key.lastIndexOf(':') + 1);
      if (!current.equals(window)) {
        window = current;
        counts.keySet().removeIf(existing -> !existing.endsWith(":" + current));
      }
      return counts.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public long current(String key) {
      var count = counts.get(key);
      return count == null ? 0 : count.get();
    }
  }
}
