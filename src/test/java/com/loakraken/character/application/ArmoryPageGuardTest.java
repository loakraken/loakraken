package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.ArmoryPageFetchException.Kind;
import com.loakraken.character.application.ArmoryPageGuard.Decision;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 전투정보실 페이지 요청 안전장치(#531): 간격·기다림 상한·쉬기·멈춤. 실제로 자지 않고 시계를 옮긴다. */
class ArmoryPageGuardTest {
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T13:00:00Z"));
  private final List<Duration> sleeps = new ArrayList<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ArmoryPageGuard guard =
      new ArmoryPageGuard(
          policy(),
          clock,
          registry,
          duration -> {
            sleeps.add(duration);
            clock.advance(duration);
          });

  private static ArmoryPagePolicy policy() {
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
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    assertThat(sleeps).isEmpty();
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    // 다음 자리는 1초 뒤 — 시계를 되돌려 자리 3개가 밀린 상황(기다림 2초 초과)을 만든다.
    clock.set(Instant.parse("2026-10-17T13:00:00Z"));
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED); // 2초 기다림
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    clock.set(Instant.parse("2026-10-17T13:00:00Z"));
    assertThat(guard.acquire()).isEqualTo(Decision.THROTTLED);
  }

  @Test
  void 공식_쪽_실패는_1분부터_두_배로_쉬고_5번이면_30분_멈춘_뒤_한_번_시험한다() {
    guard.failure(Kind.HTTP_ERROR);
    assertThat(guard.acquire()).isEqualTo(Decision.BACKOFF);
    clock.advance(Duration.ofSeconds(61));
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    guard.failure(Kind.TIMEOUT); // 2분
    clock.advance(Duration.ofSeconds(100));
    assertThat(guard.acquire()).isEqualTo(Decision.BACKOFF);
    clock.advance(Duration.ofSeconds(30));
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    guard.failure(Kind.HTTP_ERROR);
    guard.failure(Kind.HTTP_ERROR);
    assertThat(guard.stopped()).isFalse();
    guard.failure(Kind.HTTP_ERROR); // 5번째
    assertThat(guard.stopped()).isTrue();
    assertThat(registry.get("loakraken.armory_page.stopped").gauge().value()).isEqualTo(1.0);
    assertThat(guard.acquire()).isEqualTo(Decision.STOPPED);
    clock.advance(Duration.ofMinutes(31));
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    // 시험이 또 실패하면 바로 다시 멈춘다.
    guard.failure(Kind.HTTP_ERROR);
    assertThat(guard.acquire()).isEqualTo(Decision.STOPPED);
  }

  @Test
  void 성공하면_실패_수와_쉬기가_풀린다() {
    guard.failure(Kind.HTTP_ERROR);
    guard.failure(Kind.HTTP_ERROR);
    guard.success();
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    for (int i = 0; i < 4; i++) guard.failure(Kind.HTTP_ERROR);
    assertThat(guard.stopped()).isFalse();
  }

  @Test
  void 구조를_연속_3번_못_읽으면_재시작_전까지_멈춘다() {
    guard.failure(Kind.PARSE_ERROR);
    guard.failure(Kind.TOO_LARGE);
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    guard.failure(Kind.PARSE_ERROR);
    assertThat(guard.acquire()).isEqualTo(Decision.STOPPED);
    clock.advance(Duration.ofDays(1));
    assertThat(guard.acquire()).isEqualTo(Decision.STOPPED);
  }

  @Test
  void 접근_제어는_우회하지_않고_바로_멈추고_페이지_없음은_장치를_바꾸지_않는다() {
    guard.failure(Kind.NOT_FOUND);
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    guard.failure(Kind.ACCESS_DENIED);
    clock.advance(Duration.ofDays(1));
    assertThat(guard.acquire()).isEqualTo(Decision.STOPPED);
    // 시간 멈춤이 와도 영구 멈춤을 덮어쓰지 않는다.
    for (int i = 0; i < 5; i++) guard.failure(Kind.HTTP_ERROR);
    clock.advance(Duration.ofHours(1));
    assertThat(guard.acquire()).isEqualTo(Decision.STOPPED);
  }

  @Test
  void 스킬_코드_거절과_형식_변경은_스킬_코드만_멈추고_페이지는_계속_읽는다() {
    guard.skillCodeFailure(Kind.ACCESS_DENIED);
    assertThat(guard.skillCodeStopped()).isTrue();
    assertThat(guard.stopped()).isFalse();
    assertThat(guard.acquire()).isEqualTo(Decision.ALLOWED);
    assertThat(registry.get("loakraken.armory_page.skill_code.stopped").gauge().value())
        .isEqualTo(1.0);

    var other =
        new ArmoryPageGuard(policy(), clock, new SimpleMeterRegistry(), duration -> clock.advance(duration));
    other.skillCodeFailure(Kind.PARSE_ERROR);
    other.skillCodeFailure(Kind.PARSE_ERROR);
    other.skillCodeSuccess();
    other.skillCodeFailure(Kind.PARSE_ERROR);
    other.skillCodeFailure(Kind.PARSE_ERROR);
    assertThat(other.skillCodeStopped()).isFalse();
    other.skillCodeFailure(Kind.PARSE_ERROR);
    assertThat(other.skillCodeStopped()).isTrue();
    assertThat(other.stopped()).isFalse();
    // 공식 쪽 응답 문제는 페이지와 같은 쉬기를 쓴다.
    other.skillCodeFailure(Kind.HTTP_ERROR);
    assertThat(other.acquire()).isEqualTo(Decision.BACKOFF);
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
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
