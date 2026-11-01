package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 사용자 키(BYOK) 게이트: 키별 분당·동시 상한, 방문자 칸, 공식 거절 변환. 서버 키로 대신 부르지 않는다. */
class UserKeyGateTest {
  private static final String KEY_A = "a.a.a";
  private static final String KEY_B = "b.b.b";
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T03:00:10Z"), ZoneOffset.UTC);

  private UserKeyGate gate(ClientLimiter limiter, ClientLimitStore store) {
    return new UserKeyGate(
        new ByokProperties(true, "", 3, 2), limiter, store, new LostArkMetrics(registry), clock);
  }

  private static <T> T withKey(String key, Supplier<T> work) {
    UserKeyContext.enter(key, "id-" + key.charAt(0));
    try {
      return UserKeyContext.apply(work);
    } finally {
      UserKeyContext.exit();
    }
  }

  private static BusinessException rejected(Runnable call) {
    try {
      call.run();
    } catch (BusinessException exception) {
      return exception;
    }
    throw new AssertionError("거절되지 않았습니다.");
  }

  @Test
  void 키마다_분당_상한을_따로_세고_넘으면_서버_키로_대신_부르지_않고_거절한다() {
    var gate = gate(ClientLimiter.DISABLED, new ClientLimitStore.Memory());
    withKey(
        KEY_A,
        () -> {
          for (int i = 0; i < 3; i++) {
            gate.acquire();
            gate.release();
          }
          var exception = rejected(gate::acquire);
          assertThat(exception.errorCode()).isEqualTo(LostArkErrorCode.USER_KEY_RATE_LIMITED);
          // 창이 끝나는 03:01:00까지 50초.
          assertThat(exception.retryAfter()).contains(Duration.ofSeconds(50));
          return null;
        });
    // 다른 키는 영향 없음.
    withKey(
        KEY_B,
        () -> {
          gate.acquire();
          gate.release();
          return null;
        });
    assertThat(registry.get("loakraken.lostark.calls").tag("origin", "user_key").counter().count())
        .isEqualTo(4);
    assertThat(registry.find("loakraken.lostark.calls").tag("origin", "user").counter()).isNull();
  }

  @Test
  void 키마다_동시_호출_상한이_있고_끝나면_돌려준다() {
    var gate = gate(ClientLimiter.DISABLED, new ClientLimitStore.Memory());
    withKey(
        KEY_A,
        () -> {
          gate.acquire();
          gate.acquire();
          assertThat(rejected(gate::acquire).errorCode())
              .isEqualTo(LostArkErrorCode.USER_KEY_RATE_LIMITED);
          gate.release();
          assertThat(gate.running("id-a")).isEqualTo(1);
          gate.release();
          assertThat(gate.running("id-a")).isZero();
          return null;
        });
  }

  @Test
  void 방문자의_분당_공식_호출_칸도_센다() {
    var limiter =
        new ClientLimiter(
            new ClientLimitProperties(true, 2, 2, 3, 3, true, 2, 6, 20, 5, 5),
            new ClientLimitStore.Memory(),
            clock,
            registry,
            "key");
    var gate = gate(limiter, new ClientLimitStore.Memory());
    limiter.enter(limiter.clientKey("203.0.113.7"));
    try {
      withKey(
          KEY_A,
          () -> {
            gate.acquire();
            gate.release();
            gate.acquire();
            gate.release();
            assertThat(rejected(gate::acquire).errorCode())
                .isEqualTo(LostArkErrorCode.CLIENT_RATE_LIMITED);
            assertThat(gate.running("id-a")).isZero();
            return null;
          });
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 저장소_장애면_부르지_않는다() {
    ClientLimitStore broken =
        new ClientLimitStore() {
          @Override
          public long increment(String key, Duration ttl) {
            throw new ClientLimitStore.Unavailable(new RuntimeException("down"));
          }

          @Override
          public long current(String key) {
            throw new ClientLimitStore.Unavailable(new RuntimeException("down"));
          }

          @Override
          public void decrement(String key) {}
        };
    var gate = gate(ClientLimiter.DISABLED, broken);
    withKey(
        KEY_A,
        () -> {
          assertThat(rejected(gate::acquire).errorCode())
              .isEqualTo(LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE);
          assertThat(gate.running("id-a")).isZero();
          return null;
        });
  }

  @Test
  void 공식_거절을_사용자_키_오류로_바꾸고_Retry_After는_120초까지다() {
    var gate = gate(ClientLimiter.DISABLED, new ClientLimitStore.Memory());
    assertThat(gate.upstream(401, new HttpHeaders()).errorCode())
        .isEqualTo(LostArkErrorCode.USER_KEY_INVALID);
    assertThat(gate.upstream(403, new HttpHeaders()).errorCode())
        .isEqualTo(LostArkErrorCode.USER_KEY_INVALID);
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "500");
    var limited = gate.upstream(429, headers);
    assertThat(limited.errorCode()).isEqualTo(LostArkErrorCode.USER_KEY_RATE_LIMITED);
    assertThat(limited.retryAfter()).contains(Duration.ofSeconds(120));
    headers.set(HttpHeaders.RETRY_AFTER, "7");
    assertThat(gate.upstream(429, headers).retryAfter()).contains(Duration.ofSeconds(7));
    assertThat(gate.upstream(200, new HttpHeaders())).isNull();
    assertThat(gate.upstream(404, new HttpHeaders())).isNull();
  }

  @Test
  void 키_없이_부르면_예외다() {
    var gate = gate(ClientLimiter.DISABLED, new ClientLimitStore.Memory());
    assertThatThrownBy(gate::acquire).isInstanceOf(IllegalStateException.class);
  }
}
