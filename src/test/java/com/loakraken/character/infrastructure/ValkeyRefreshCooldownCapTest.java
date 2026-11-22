package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.common.infrastructure.GlobalExceptionHandler;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;

/**
 * 불러오기 쿨다운의 남은 시간은 Valkey가 키에 남은 만료(PTTL)다. 저장소 시계가 SET과 PTTL 사이에 뒤로 가면 방금 건 쿨다운보다 길게 나올 수 있어, 남은
 * 시간은 건 쿨다운을 넘지 않게 자른다(Retry-After가 10분 = 600초를 넘지 않게).
 */
class ValkeyRefreshCooldownCapTest {
  private static final Duration COOLDOWN = Duration.ofMinutes(10);

  @SuppressWarnings("unchecked")
  private static StringRedisTemplate redisWithLeft(long millis) {
    var redis = mock(StringRedisTemplate.class);
    var ops = (ValueOperations<String, String>) mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);
    when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenReturn(millis);
    return redis;
  }

  private static String retryAfter(Duration wait) {
    return new GlobalExceptionHandler()
        .handleBusinessException(
            new CharacterException(CharacterErrorCode.CARD_COLLECTION_REFRESH_COOLDOWN, wait))
        .getHeaders()
        .getFirst(HttpHeaders.RETRY_AFTER);
  }

  @Test
  void 카드_불러오기_남은_시간은_건_쿨다운을_넘지_않는다() {
    var cooldown = new ValkeyCardRefreshCooldown(redisWithLeft(600_001));
    var wait = cooldown.acquire("roster", COOLDOWN).orElseThrow();
    assertThat(wait).isEqualTo(COOLDOWN);
    assertThat(retryAfter(wait)).isEqualTo("600");
    // 경계 안쪽은 그대로(올림).
    var inside = new ValkeyCardRefreshCooldown(redisWithLeft(599_001)).acquire("roster", COOLDOWN);
    assertThat(retryAfter(inside.orElseThrow())).isEqualTo("600");
  }

  @Test
  void 원정대_전투력_불러오기_남은_시간도_건_쿨다운을_넘지_않는다() {
    var cooldown = new ValkeyRosterRefreshCooldown(redisWithLeft(600_001));
    var wait = cooldown.acquire("roster", COOLDOWN).orElseThrow();
    assertThat(wait).isEqualTo(COOLDOWN);
    assertThat(retryAfter(wait)).isEqualTo("600");
  }
}
