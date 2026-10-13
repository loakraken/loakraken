package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class LostArkRequestGateTest {
  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private final LostArkMetrics metrics = new LostArkMetrics(registry);

  @Test
  void 동시_호출_제한을_초과하면_대기열을_늘리지_않는다() {
    var gate = gate(Clock.systemUTC(), 90, 1);
    gate.acquire();
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
    assertThat(
            registry
                .get("loakraken.lostark.gate")
                .tag("event", "concurrency_reject")
                .counter()
                .count())
        .isEqualTo(1);
    gate.release();
    gate.acquire();
    gate.release();
  }

  @Test
  void 최근_1분의_호출_예산을_넘지_않는다() {
    var clock = new MutableClock();
    var gate = gate(clock, 1, 1);
    gate.acquire();
    gate.release();
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
    assertThat(
            registry.get("loakraken.lostark.gate").tag("event", "budget_reject").counter().count())
        .isEqualTo(1);
    clock.now = clock.now.plusSeconds(60);
    gate.acquire();
    gate.release();
  }

  @Test
  void 공식_100회_한도보다_낮은_90회_예산에서_후속_호출을_차단한다() {
    var gate = gate(new MutableClock(), 90, 1);
    for (int i = 0; i < 90; i++) {
      gate.acquire();
      gate.release();
    }
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
  }

  @Test
  void 남은_할당량이_0이면_서버의_리셋_시각까지_차단한다() {
    var clock = new MutableClock();
    var gate = gate(clock, 90, 1);
    var headers = new HttpHeaders();
    headers.set("X-RateLimit-Remaining", "0");
    headers.set("X-RateLimit-Reset", Long.toString(clock.now.plusSeconds(30).getEpochSecond()));
    gate.observe(headers, false);
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
    clock.now = clock.now.plusSeconds(30);
    gate.acquire();
    gate.release();
  }

  @Test
  void 백그라운드는_사용자_몫을_남기고_한도_대기_중에는_양보한다() {
    var clock = new MutableClock();
    var gate = gate(clock, 10, 4);
    assertThat(gate.backgroundAvailable(4)).isTrue();
    for (int i = 0; i < 6; i++) {
      gate.acquire();
      gate.release();
    }
    assertThat(gate.backgroundAvailable(4)).isFalse();
    assertThat(gate.backgroundAvailable(3)).isTrue();
    clock.now = clock.now.plusSeconds(61);
    assertThat(gate.backgroundAvailable(4)).isTrue();
    var headers = new HttpHeaders();
    headers.set("X-RateLimit-Remaining", "0");
    headers.set("X-RateLimit-Reset", Long.toString(clock.now.plusSeconds(30).getEpochSecond()));
    gate.observe(headers, false);
    assertThat(gate.backgroundAvailable(0)).isFalse();
  }

  @Test
  void 사용자_몫_호출은_클라이언트_공식_한도에도_매기고_백그라운드는_매기지_않는다() {
    // 보안 감사 10-12: reserve 안 호출이 클라이언트 OFFICIAL 한도를 벗어나 원정대 불러오기가 분당 24회까지 가능했다.
    var clock = new MutableClock();
    var limiter =
        new ClientLimiter(
            new ClientLimitProperties(true, 2, 10, 10, 10, true, 2, 6, 20, 5, 5),
            new ClientLimitStore.Memory(),
            clock,
            registry,
            "key");
    var gate =
        new LostArkRequestGate(
            clock,
            new LostArkProperties(
                URI.create("https://example.com"),
                "test",
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                90,
                4,
                Duration.ofMinutes(5)),
            metrics,
            new MemoryGateStore(clock),
            limiter);
    limiter.enter("client-a");
    try {
      Runnable call =
          () -> {
            gate.acquire();
            gate.release();
          };
      // 백그라운드형(withReserve)은 클라이언트 한도에 매기지 않는다.
      for (int i = 0; i < 3; i++)
        gate.withReserve(
            30,
            () -> {
              call.run();
              return null;
            });
      // 사용자 몫형(withUserReserve)은 분당 2회까지, 세 번째는 CLIENT_RATE_LIMITED.
      for (int i = 0; i < 2; i++)
        gate.withUserReserve(
            30,
            () -> {
              call.run();
              return null;
            });
      assertThatThrownBy(
              () ->
                  gate.withUserReserve(
                      30,
                      () -> {
                        call.run();
                        return null;
                      }))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(LostArkErrorCode.CLIENT_RATE_LIMITED);
    } finally {
      limiter.exit();
    }
  }

  private LostArkRequestGate gate(Clock clock, int budget, int concurrency) {
    return new LostArkRequestGate(
        clock,
        new LostArkProperties(
            URI.create("https://example.com"),
            "test",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            budget,
            concurrency,
            Duration.ofMinutes(5)),
        metrics);
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-04T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
