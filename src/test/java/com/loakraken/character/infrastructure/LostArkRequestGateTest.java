package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.character.application.CharacterException;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class LostArkRequestGateTest {
  @Test
  void 동시_호출_제한을_초과하면_대기열을_늘리지_않는다() {
    var gate = gate(Clock.systemUTC(), 90, 1);
    gate.acquire();
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(CharacterException.class);
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
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(CharacterException.class);
    clock.now = clock.now.plusSeconds(60);
    gate.acquire();
    gate.release();
  }

  @Test
  void 남은_할당량이_0이면_서버의_리셋_시각까지_차단한다() {
    var clock = new MutableClock();
    var gate = gate(clock, 90, 1);
    var headers = new HttpHeaders();
    headers.set("X-RateLimit-Remaining", "0");
    headers.set("X-RateLimit-Reset", Long.toString(clock.now.plusSeconds(30).getEpochSecond()));
    gate.observe(headers, false);
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(CharacterException.class);
    clock.now = clock.now.plusSeconds(30);
    gate.acquire();
    gate.release();
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
            Duration.ofMinutes(5)));
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
