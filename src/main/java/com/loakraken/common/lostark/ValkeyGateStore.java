package com.loakraken.common.lostark;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 같은 키를 쓰는 모든 인스턴스의 합산 게이트. 키는 `lostark:gate:{keyId}:window`(정렬 집합)와 `:blocked-until`이다.
 *
 * <p>판정과 기록은 Lua 한 번이다: 서버 TIME → 대기 확인 → 60초 밖 정리 → ZCARD 판정 → ZADD. 서버 시각을 쓰므로 인스턴스 시계 차이에 흔들리지
 * 않는다. API 키 원문은 넣지 않는다.
 *
 * <p>두 키 모두 만료 시간을 두지 않는다. 캐시와 같은 Valkey를 쓰고 메모리 정책이 만료 시간이 있는 키를 내보내므로(volatile-lru), 만료 시간이 있으면
 * 메모리가 찰 때 창이 지워져 예산이 초기화될 수 있다. 창은 매번 60초 밖 기록을 지워 예산 수만큼만 남고, 대기 시각은 값(시각)으로 비교하므로 지난 값이 남아도 막지
 * 않는다. 예전 버전이 둔 만료 시간은 기록할 때 지운다(PERSIST).
 */
public class ValkeyGateStore implements GateStore {
  static final String PREFIX = "lostark:gate:";
  private static final DefaultRedisScript<Long> ADMIT =
      new DefaultRedisScript<>(
          """
          local t = redis.call('TIME')
          local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
          local blocked = tonumber(redis.call('GET', KEYS[2]) or '0')
          if now < blocked then return -1 end
          redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
          local limit = tonumber(ARGV[1]) - tonumber(ARGV[2])
          if redis.call('ZCARD', KEYS[1]) >= limit then return -2 end
          if ARGV[4] == '1' then
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PERSIST', KEYS[1])
          end
          return 1
          """,
          Long.class);
  private static final DefaultRedisScript<Long> BLOCK =
      new DefaultRedisScript<>(
          """
          local current = tonumber(redis.call('GET', KEYS[1]) or '0')
          local next = tonumber(ARGV[1])
          if next > current then
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            if next > now then redis.call('SET', KEYS[1], ARGV[1]) end
          end
          return 1
          """,
          Long.class);
  private final StringRedisTemplate redis;
  private final List<String> keys;
  private final String blockedKey;

  public ValkeyGateStore(StringRedisTemplate redis, String keyId) {
    this.redis = redis;
    this.keys = List.of(PREFIX + keyId + ":window", PREFIX + keyId + ":blocked-until");
    this.blockedKey = keys.get(1);
  }

  @Override
  public Admission admit(int budget, int reserve) {
    return admission(run(budget, reserve, true));
  }

  @Override
  public boolean available(int budget, int reserve) {
    return admission(run(budget, reserve, false)) == Admission.ADMITTED;
  }

  @Override
  public void blockUntil(Instant until) {
    try {
      redis.execute(BLOCK, List.of(blockedKey), String.valueOf(until.toEpochMilli()));
    } catch (RuntimeException exception) {
      throw new GateUnavailableException(exception);
    }
  }

  private long run(int budget, int reserve, boolean record) {
    try {
      Long result =
          redis.execute(
              ADMIT,
              keys,
              String.valueOf(budget),
              String.valueOf(Math.max(0, reserve)),
              UUID.randomUUID().toString(),
              record ? "1" : "0");
      if (result == null) throw new IllegalStateException("게이트 스크립트 결과가 없습니다.");
      return result;
    } catch (RuntimeException exception) {
      throw new GateUnavailableException(exception);
    }
  }

  private static Admission admission(long result) {
    if (result == -1) return Admission.BACKOFF;
    if (result == -2) return Admission.BUDGET;
    return Admission.ADMITTED;
  }

  /** 테스트용: 남은 대기 시간(저장한 대기 시각 − 지금). */
  Duration blockedFor() {
    var value = redis.opsForValue().get(blockedKey);
    if (value == null) return Duration.ZERO;
    long left = Long.parseLong(value) - System.currentTimeMillis();
    return left <= 0 ? Duration.ZERO : Duration.ofMillis(left);
  }

  /** 테스트용: 창·대기 키의 남은 만료 시간(ms, 만료 없음은 -1, 키 없음은 -2). */
  List<Long> expiries() {
    return keys.stream()
        .map(key -> redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS))
        .toList();
  }
}
