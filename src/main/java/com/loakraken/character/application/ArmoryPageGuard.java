package com.loakraken.character.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 공식 전투정보실 페이지 요청의 안전장치(#531). 운영 API 1대 정책이라 프로세스 안 상태로 인스턴스 전체를 막는다(2대가 되면 Valkey로 옮김).
 *
 * <ul>
 *   <li>간격: 요청 사이 최소 {@code minInterval}. 기다림이 {@code maxWait}를 넘으면 요청하지 않는다(줄 세우지 않음).
 *   <li>쉬기: 공식 쪽 실패(429·5xx·3xx·시간 초과)마다 {@code backoffInitial}부터 두 배, {@code backoffMax}까지.
 *   <li>멈춤: 공식 쪽 연속 실패 {@code stopAfterFailures}번이면 {@code backoffMax} 동안 멈추고 그 뒤 한 번 시험한다(점검 시간
 *       대비). 페이지 구조를 연속 {@code stopAfterParseFailures}번 못 읽거나 401·403이면 재시작 전까지 멈춘다(구조 변경·접근 제어는
 *       자동으로 풀지 않음).
 * </ul>
 */
@Component
public class ArmoryPageGuard {
  private static final Logger log = LoggerFactory.getLogger(ArmoryPageGuard.class);

  /** 요청해도 되는지. ALLOWED가 아니면 공식 요청을 하지 않는다. 이름은 지표 라벨(skipped reason)로 쓴다. */
  public enum Decision {
    ALLOWED,
    THROTTLED,
    BACKOFF,
    STOPPED
  }

  /** 테스트에서 기다림을 시계로 바꾼다. */
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final ArmoryPagePolicy policy;
  private final Clock clock;
  private final Sleeper sleeper;
  private Instant nextSlot = Instant.EPOCH;
  private Instant pauseUntil = Instant.EPOCH;
  private int failures;
  private int parseFailures;
  private boolean stopped;
  private Instant resumeAt;
  private int codeParseFailures;
  private boolean codeStopped;

  @Autowired
  public ArmoryPageGuard(ArmoryPagePolicy policy, Clock clock, MeterRegistry registry) {
    this(policy, clock, registry, duration -> Thread.sleep(duration));
  }

  ArmoryPageGuard(ArmoryPagePolicy policy, Clock clock, MeterRegistry registry, Sleeper sleeper) {
    this.policy = policy;
    this.clock = clock;
    this.sleeper = sleeper;
    Gauge.builder("loakraken.armory_page.stopped", this, guard -> guard.stopped() ? 1 : 0)
        .description("전투정보실 페이지 요청이 멈춰 있으면 1")
        .register(registry);
    Gauge.builder(
            "loakraken.armory_page.skill_code.stopped", this, guard -> guard.skillCodeStopped() ? 1 : 0)
        .description("스킬 코드 요청만 멈춰 있으면 1(거절·형식 변경)")
        .register(registry);
  }

  /** 요청 자리를 잡는다. ALLOWED면 간격을 지키도록 기다린 뒤 돌아온다. */
  public Decision acquire() {
    Duration wait;
    synchronized (this) {
      var now = clock.instant();
      if (stopped) {
        if (resumeAt == null || now.isBefore(resumeAt)) return Decision.STOPPED;
        // 연속 실패로 멈춘 뒤 한 번 시험한다. 실패 수는 그대로라 또 실패하면 바로 다시 멈춘다.
        stopped = false;
        resumeAt = null;
        log.info("event=armory_page_resume_trial");
      }
      if (now.isBefore(pauseUntil)) return Decision.BACKOFF;
      var slot = nextSlot.isAfter(now) ? nextSlot : now;
      wait = Duration.between(now, slot);
      if (wait.compareTo(policy.maxWait()) > 0) return Decision.THROTTLED;
      nextSlot = slot.plus(policy.minInterval());
    }
    if (!wait.isZero()) {
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return Decision.THROTTLED;
      }
    }
    return Decision.ALLOWED;
  }

  public synchronized void success() {
    failures = 0;
    parseFailures = 0;
    pauseUntil = Instant.EPOCH;
  }

  public synchronized void failure(ArmoryPageFetchException.Kind kind) {
    var now = clock.instant();
    switch (kind) {
      case NOT_FOUND -> {
        // 그 캐릭터만의 일이라 장치 상태를 바꾸지 않는다.
      }
      case ACCESS_DENIED -> stop("access_denied", null);
      case PARSE_ERROR, TOO_LARGE -> {
        parseFailures++;
        if (parseFailures >= policy.stopAfterParseFailures()) stop("structure", null);
      }
      case HTTP_ERROR, TIMEOUT -> {
        failures++;
        var pause = policy.backoffInitial().multipliedBy(1L << Math.min(failures - 1, 20));
        if (pause.compareTo(policy.backoffMax()) > 0) pause = policy.backoffMax();
        pauseUntil = now.plus(pause);
        log.warn("event=armory_page_backoff seconds={} failures={}", pause.toSeconds(), failures);
        if (failures >= policy.stopAfterFailures()) stop("failures", now.plus(policy.backoffMax()));
      }
    }
  }

  /**
   * 스킬 코드 요청 실패(#532). 거절(401·403·응답 code≠0)이면 우회하지 않고 스킬 코드만 재시작 전까지 멈춘다. 형식 불일치가 연속
   * {@code stopAfterParseFailures}번이면 스킬 코드만 멈춘다. 공식 쪽 응답 문제(429·5xx·시간 초과)는 페이지와 같은 쉬기·멈춤을 쓴다.
   */
  public synchronized void skillCodeFailure(ArmoryPageFetchException.Kind kind) {
    switch (kind) {
      case ACCESS_DENIED -> stopSkillCode("access_denied");
      case PARSE_ERROR, TOO_LARGE -> {
        codeParseFailures++;
        if (codeParseFailures >= policy.stopAfterParseFailures()) stopSkillCode("structure");
      }
      default -> failure(kind);
    }
  }

  public synchronized void skillCodeSuccess() {
    codeParseFailures = 0;
    success();
  }

  public synchronized boolean skillCodeStopped() {
    return codeStopped;
  }

  private void stopSkillCode(String reason) {
    if (codeStopped) return;
    codeStopped = true;
    log.warn("event=armory_page_skill_code_stopped reason={} autoResume=none", reason);
  }

  public synchronized boolean stopped() {
    return stopped && (resumeAt == null || clock.instant().isBefore(resumeAt));
  }

  private void stop(String reason, Instant resume) {
    if (stopped && resumeAt == null) return;
    stopped = true;
    resumeAt = resume;
    log.warn(
        "event=armory_page_stopped reason={} autoResume={}",
        reason,
        resume == null ? "none" : policy.backoffMax().toMinutes() + "m");
  }
}
