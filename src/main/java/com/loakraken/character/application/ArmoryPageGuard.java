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
 * 공식 전투정보실 페이지 요청의 안전장치(#531). 운영 API 1대 정책이라 프로세스 안 상태로 인스턴스 전체를 막는다(2대가 되면 Valkey로 옮김). 모든 상태
 * 바꿈은 이 객체의 잠금 하나 안에서 한다(동시 요청에서도 '정지했으면 나가지 않음').
 *
 * <ul>
 *   <li>간격: 요청 사이 최소 {@code minInterval}. 기다림이 {@code maxWait}를 넘으면 요청하지 않는다. <b>기다린 뒤 정지·쉬기를 다시 확인</b>한다.
 *   <li>쉬기: 공식 쪽 실패(429·5xx·3xx·시간 초과)마다 {@code backoffInitial}부터 두 배, {@code backoffMax}까지. 실패마다 세대 번호를
 *       올리고, 그보다 먼저 시작한 요청의 늦은 성공은 쉬기·실패 수를 지우지 못한다.
 *   <li>멈춤: 공식 쪽 연속 실패 {@code stopAfterFailures}번이면 {@code backoffMax} 동안 멈추고, 그 뒤 <b>시험 요청 하나만</b> 내보낸다(시험이
 *       끝나기 전에는 다른 요청 거절). 페이지 구조를 연속 {@code stopAfterParseFailures}번 못 읽거나 401·403이면 재시작 전까지 멈춘다.
 *   <li>캐시를 쓰지 못하면 {@link #pause}로 실패 캐시 시간만큼 쉰다(같은 캐릭터를 거듭 부르지 않게).
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

  /** 허가. generation은 허가 시점의 실패 세대, trial은 멈춤 뒤 시험 요청인지. */
  public record Permit(Decision decision, long generation, boolean trial) {
    public boolean allowed() {
      return decision == Decision.ALLOWED;
    }
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
  private long generation;
  private int failures;
  private int parseFailures;
  private boolean stopped;
  private Instant resumeAt;
  private boolean trialInFlight;

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
  }

  /** 요청 자리를 잡는다. ALLOWED면 간격을 지키도록 기다린 뒤, 그동안 바뀐 정지·쉬기를 다시 확인하고 돌아온다. */
  public Permit acquire() {
    Duration wait;
    boolean trial = false;
    long issued;
    synchronized (this) {
      var now = clock.instant();
      if (stopped) {
        if (resumeAt == null || now.isBefore(resumeAt) || trialInFlight) return denied(Decision.STOPPED);
        trial = true;
        trialInFlight = true;
        log.info("event=armory_page_resume_trial");
      }
      if (now.isBefore(pauseUntil)) return release(trial, Decision.BACKOFF);
      var slot = nextSlot.isAfter(now) ? nextSlot : now;
      wait = Duration.between(now, slot);
      if (wait.compareTo(policy.maxWait()) > 0) return release(trial, Decision.THROTTLED);
      nextSlot = slot.plus(policy.minInterval());
      issued = generation;
    }
    if (!wait.isZero()) {
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        synchronized (this) {
          return release(trial, Decision.THROTTLED);
        }
      }
    }
    synchronized (this) {
      // 기다리는 동안 다른 요청이 멈춤·쉬기를 걸었으면 나가지 않는다.
      if (stopped && !trial) return denied(Decision.STOPPED);
      if (trial && stopped && resumeAt == null) return release(true, Decision.STOPPED);
      if (clock.instant().isBefore(pauseUntil)) return release(trial, Decision.BACKOFF);
      return new Permit(Decision.ALLOWED, issued, trial);
    }
  }

  public synchronized void success(Permit permit) {
    if (permit.trial()) {
      trialInFlight = false;
      if (stopped && resumeAt != null) {
        stopped = false;
        resumeAt = null;
        log.info("event=armory_page_resumed");
      }
    }
    // 이 요청이 나간 뒤 다른 실패가 있었으면(세대가 바뀜) 그 실패의 쉬기·실패 수를 지우지 않는다.
    if (permit.generation() != generation) return;
    failures = 0;
    parseFailures = 0;
    pauseUntil = Instant.EPOCH;
  }

  public synchronized void failure(Permit permit, ArmoryPageFetchException.Kind kind) {
    var now = clock.instant();
    if (permit.trial()) trialInFlight = false;
    switch (kind) {
      case NOT_FOUND -> {
        // 그 캐릭터만의 일이다. 시험 요청이었으면 공식 쪽은 응답한 것이라 멈춤을 푼다.
        if (permit.trial() && stopped && resumeAt != null) {
          stopped = false;
          resumeAt = null;
        }
      }
      case ACCESS_DENIED -> stop("access_denied", null);
      case PARSE_ERROR, TOO_LARGE -> {
        parseFailures++;
        if (parseFailures >= policy.stopAfterParseFailures()) stop("structure", null);
        else if (permit.trial()) stop("trial_failed", now.plus(policy.backoffMax()));
      }
      case HTTP_ERROR, TIMEOUT -> {
        generation++;
        failures++;
        var pause = policy.backoffInitial().multipliedBy(1L << Math.min(failures - 1, 20));
        if (pause.compareTo(policy.backoffMax()) > 0) pause = policy.backoffMax();
        extendPause(now.plus(pause));
        log.warn("event=armory_page_backoff seconds={} failures={}", pause.toSeconds(), failures);
        if (failures >= policy.stopAfterFailures() || permit.trial())
          stop("failures", now.plus(policy.backoffMax()));
      }
    }
  }

  /** 캐시를 쓰지 못하는 등 우리 쪽 사정으로 잠시 쉰다. 세대를 올려 진행 중인 요청의 늦은 성공이 지우지 못하게 한다. */
  public synchronized void pause(Duration duration, String reason) {
    generation++;
    extendPause(clock.instant().plus(duration));
    log.warn("event=armory_page_paused reason={} seconds={}", reason, duration.toSeconds());
  }

  public synchronized boolean stopped() {
    return stopped && (resumeAt == null || clock.instant().isBefore(resumeAt) || trialInFlight);
  }

  private void extendPause(Instant until) {
    if (until.isAfter(pauseUntil)) pauseUntil = until;
  }

  private Permit denied(Decision decision) {
    return new Permit(decision, generation, false);
  }

  private Permit release(boolean trial, Decision decision) {
    if (trial) trialInFlight = false;
    return new Permit(decision, generation, false);
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
