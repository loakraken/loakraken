package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.ArmoryPageFetchException.Kind;
import com.loakraken.character.application.ArmoryPageGuard.Decision;
import com.loakraken.character.application.ArmoryPageGuard.Phase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 전투정보실 페이지 요청 안전장치(#531): 간격·기다림 상한·쉬기·멈춤과 동시 요청(기다리는 사이 멈춤, 늦은 성공, 시험 요청 하나). 실제로 자지 않고 시계를
 * 옮긴다(기다리는 동안 다른 요청의 결과를 끼워 넣을 수 있다).
 */
class ArmoryPageGuardTest {
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T13:00:00Z"));
  private final List<Duration> sleeps = new ArrayList<>();
  private final AtomicReference<Runnable> duringSleep = new AtomicReference<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ArmoryPageGuard guard =
      new ArmoryPageGuard(
          policy(),
          clock,
          registry,
          duration -> {
            sleeps.add(duration);
            clock.advance(duration);
            var hook = duringSleep.getAndSet(null);
            if (hook != null) hook.run();
          });

  static ArmoryPagePolicy policy() {
    return new ArmoryPagePolicy(
        true,
        URI.create("https://lostark.game.onstove.com"),
        "LOAKraken",
        "",
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        3_000_000,
        Duration.ofHours(6),
        Duration.ofMinutes(10),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofMinutes(1),
        Duration.ofMinutes(30),
        5,
        3,
        new ArmoryPagePolicy.SkillCode(true));
  }

  @Test
  void 요청_사이는_1초를_띄우고_2초_넘게_기다려야_하면_요청하지_않는다() {
    assertThat(guard.acquire().decision()).isEqualTo(Decision.ALLOWED);
    assertThat(sleeps).isEmpty();
    assertThat(guard.acquire().decision()).isEqualTo(Decision.ALLOWED);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    clock.set(Instant.parse("2026-10-17T13:00:00Z"));
    assertThat(guard.acquire().decision()).isEqualTo(Decision.ALLOWED); // 2초 기다림
    assertThat(guard.acquire().decision()).isEqualTo(Decision.ALLOWED);
    clock.set(Instant.parse("2026-10-17T13:00:00Z"));
    assertThat(guard.acquire().decision()).isEqualTo(Decision.THROTTLED);
  }

  @Test
  void 기다리는_사이_다른_요청이_멈추게_하면_기다린_요청도_나가지_않는다() {
    // A가 자리를 잡고(바로 나감), B는 1초 뒤 자리. B가 기다리는 동안 A가 403을 받아 전체가 멈춘다.
    var a = guard.acquire();
    assertThat(a.allowed()).isTrue();
    duringSleep.set(() -> guard.failure(a, Kind.ACCESS_DENIED));
    var b = guard.acquire();
    assertThat(b.decision()).isEqualTo(Decision.STOPPED);
  }

  @Test
  void 기다리는_사이_다른_요청이_429로_쉬게_하면_기다린_요청도_쉰다() {
    var a = guard.acquire();
    duringSleep.set(() -> guard.failure(a, Kind.HTTP_ERROR));
    assertThat(guard.acquire().decision()).isEqualTo(Decision.BACKOFF);
  }

  @Test
  void 먼저_시작한_요청의_늦은_성공은_뒤에_온_실패의_쉬기를_지우지_못한다() {
    var slow = guard.acquire(); // 느린 요청 A
    clock.advance(Duration.ofSeconds(1));
    var b = guard.acquire(); // B
    guard.failure(b, Kind.HTTP_ERROR); // B가 429 → 1분 쉬기
    guard.success(slow); // A가 늦게 성공
    assertThat(guard.acquire().decision()).isEqualTo(Decision.BACKOFF);
    clock.advance(Duration.ofSeconds(61));
    var c = guard.acquire();
    assertThat(c.allowed()).isTrue();
    guard.success(c); // 쉬기 뒤 시작한 요청의 성공은 실패 수를 지운다
    for (int i = 0; i < 4; i++) {
      clock.advance(Duration.ofMinutes(31));
      guard.failure(guard.acquire(), Kind.HTTP_ERROR);
    }
    assertThat(guard.stopped()).isFalse();
  }

  @Test
  void 멈춘_뒤에는_시험_요청_하나만_나가고_성공해야_풀린다() {
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofMinutes(31));
      guard.failure(guard.acquire(), Kind.HTTP_ERROR);
    }
    assertThat(guard.stopped()).isTrue();
    assertThat(registry.get("loakraken.armory_page.stopped").gauge().value()).isEqualTo(1.0);
    assertThat(guard.acquire().decision()).isEqualTo(Decision.STOPPED);
    clock.advance(Duration.ofMinutes(31));
    var trial = guard.acquire();
    assertThat(trial.allowed()).isTrue();
    assertThat(trial.trial()).isTrue();
    // 시험이 끝나기 전 다른 요청은 거절
    clock.advance(Duration.ofSeconds(5));
    assertThat(guard.acquire().decision()).isEqualTo(Decision.STOPPED);
    guard.success(trial);
    assertThat(guard.stopped()).isFalse();
    assertThat(guard.acquire().allowed()).isTrue();
  }

  @Test
  void 시험_요청이_실패하면_바로_다시_멈춘다() {
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofMinutes(31));
      guard.failure(guard.acquire(), Kind.HTTP_ERROR);
    }
    clock.advance(Duration.ofMinutes(31));
    var trial = guard.acquire();
    guard.failure(trial, Kind.TIMEOUT);
    assertThat(guard.acquire().decision()).isEqualTo(Decision.STOPPED);
    clock.advance(Duration.ofMinutes(31));
    assertThat(guard.acquire().trial()).isTrue();
  }

  @Test
  void 동시에_여러_요청이_와도_시험_요청은_하나뿐이다() throws Exception {
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofMinutes(31));
      guard.failure(guard.acquire(), Kind.HTTP_ERROR);
    }
    clock.advance(Duration.ofMinutes(31));
    int threads = 8;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var allowed = new java.util.concurrent.atomic.AtomicInteger();
    for (int i = 0; i < threads; i++)
      new Thread(
              () -> {
                try {
                  start.await();
                  if (guard.acquire().allowed()) allowed.incrementAndGet();
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              })
          .start();
    start.countDown();
    done.await();
    assertThat(allowed.get()).isEqualTo(1);
  }

  @Test
  void 구조를_연속_3번_못_읽거나_접근이_거부되면_재시작_전까지_멈춘다() {
    guard.failure(guard.acquire(), Kind.PARSE_ERROR);
    clock.advance(Duration.ofSeconds(2));
    guard.failure(guard.acquire(), Kind.TOO_LARGE);
    clock.advance(Duration.ofSeconds(2));
    assertThat(guard.acquire().allowed()).isTrue();
    guard.failure(
        new ArmoryPageGuard.Permit(Decision.ALLOWED, Phase.PAGE, 0, false), Kind.PARSE_ERROR);
    clock.advance(Duration.ofDays(1));
    assertThat(guard.acquire().decision()).isEqualTo(Decision.STOPPED);

    var other = new ArmoryPageGuard(policy(), clock, new SimpleMeterRegistry(), clock::advance);
    other.failure(other.acquire(), Kind.NOT_FOUND);
    clock.advance(Duration.ofSeconds(2));
    assertThat(other.acquire().allowed()).isTrue();
    other.failure(
        new ArmoryPageGuard.Permit(Decision.ALLOWED, Phase.PAGE, 0, false), Kind.ACCESS_DENIED);
    clock.advance(Duration.ofDays(1));
    assertThat(other.acquire().decision()).isEqualTo(Decision.STOPPED);
  }

  @Test
  void 우리_쪽_사정으로_쉬면_그동안_요청하지_않고_진행_중인_요청의_성공이_지우지_못한다() {
    var running = guard.acquire();
    guard.pause(Duration.ofMinutes(10), "cache_write");
    guard.success(running);
    clock.advance(Duration.ofMinutes(5));
    assertThat(guard.acquire().decision()).isEqualTo(Decision.BACKOFF);
    clock.advance(Duration.ofMinutes(6));
    assertThat(guard.acquire().allowed()).isTrue();
  }

  @Test
  void 스킬_코드_거절과_형식_변경은_스킬_코드만_멈추고_페이지는_계속_읽는다() {
    guard.failure(guard.acquire(Phase.CODE), Kind.ACCESS_DENIED);
    assertThat(guard.skillCodeStopped()).isTrue();
    assertThat(guard.stopped()).isFalse();
    assertThat(guard.acquire(Phase.CODE).decision()).isEqualTo(Decision.STOPPED);
    assertThat(guard.acquire().allowed()).isTrue();
    assertThat(registry.get("loakraken.armory_page.skill_code.stopped").gauge().value())
        .isEqualTo(1.0);

    // 형식 변경: 코드 성공으로만 센 수가 지워지고, 연속 3번이면 코드만 멈춘다.
    var other = new ArmoryPageGuard(policy(), clock, new SimpleMeterRegistry(), clock::advance);
    other.structureFailure(Phase.CODE);
    other.structureFailure(Phase.CODE);
    other.success(other.acquire(Phase.CODE));
    other.structureFailure(Phase.CODE);
    other.structureFailure(Phase.CODE);
    other.success(other.acquire(Phase.PAGE)); // 페이지 성공은 코드 형식 실패 수를 지우지 않는다
    assertThat(other.skillCodeStopped()).isFalse();
    other.failure(other.acquire(Phase.CODE), Kind.PARSE_ERROR);
    assertThat(other.skillCodeStopped()).isTrue();
    assertThat(other.stopped()).isFalse();
  }

  @Test
  void 페이지_성공이_코드_요청의_연속_실패_수를_지우지_않아_5번이면_멈춘다() {
    // 페이지 GET 성공 → 코드 POST 500을 쉬기가 끝날 때마다 되풀이.
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofMinutes(31));
      guard.success(guard.acquire(Phase.PAGE));
      guard.failure(guard.acquire(Phase.CODE), Kind.HTTP_ERROR);
    }
    assertThat(guard.stopped()).isTrue();
    assertThat(guard.acquire().decision()).isEqualTo(Decision.STOPPED);
  }

  @Test
  void 코드_요청이_기다리는_사이_다른_코드_요청이_거절되면_나가지_않는다() {
    var a = guard.acquire(Phase.CODE);
    assertThat(a.allowed()).isTrue();
    duringSleep.set(() -> guard.failure(a, Kind.ACCESS_DENIED));
    assertThat(guard.acquire(Phase.CODE).decision()).isEqualTo(Decision.STOPPED);
    // 페이지 요청은 계속 나간다.
    clock.advance(Duration.ofSeconds(2));
    assertThat(guard.acquire(Phase.PAGE).allowed()).isTrue();
  }

  @Test
  void 페이지와_코드는_같은_간격과_쉬기를_쓴다() {
    assertThat(guard.acquire(Phase.PAGE).allowed()).isTrue();
    assertThat(guard.acquire(Phase.CODE).allowed()).isTrue();
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    var code = guard.acquire(Phase.CODE);
    guard.failure(code, Kind.TIMEOUT);
    assertThat(guard.acquire(Phase.PAGE).decision()).isEqualTo(Decision.BACKOFF);
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    synchronized void advance(Duration duration) {
      now = now.plus(duration);
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
