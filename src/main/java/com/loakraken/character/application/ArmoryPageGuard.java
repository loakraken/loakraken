package com.loakraken.character.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 공식 전투정보실 요청의 안전장치(#531·#532). 운영 API 1대 정책이라 프로세스 안 상태로 인스턴스 전체를 막는다(2대가 되면 Valkey로 옮김). 페이지 GET과
 * 스킬 코드 POST가 <b>같은 간격·쉬기·멈춤·시험 요청 상태</b>를 쓰고, 모든 상태 바꿈은 이 객체의 잠금 하나 안에서 한다(동시 요청에서도 '정지했으면 나가지
 * 않음').
 *
 * <ul>
 *   <li>간격: 요청 사이 최소 {@code minInterval}(GET·POST 합산). 기다림이 {@code maxWait}를 넘으면 요청하지 않는다. <b>기다린 뒤
 *       정지·쉬기를 다시 확인</b>한다.
 *   <li>쉬기: 공식 쪽 실패(429·5xx·3xx·시간 초과)마다 {@code backoffInitial}부터 두 배, {@code backoffMax}까지. 실패마다
 *       세대 번호를 올리고, 그보다 먼저 시작한 요청의 늦은 성공은 쉬기·실패 수를 지우지 못한다. 실패 수는 단계(페이지·코드)별로 세고 같은 단계의 성공으로만 지운다.
 *   <li>멈춤: 한 단계의 공식 쪽 연속 실패 {@code stopAfterFailures}번이면 {@code backoffMax} 동안 전체가 멈추고, 그 뒤 <b>시험
 *       요청 하나만</b> 내보낸다. 페이지 구조를 연속 {@code stopAfterParseFailures}번 못 읽거나 페이지가 401·403이면 재시작 전까지
 *       전체가 멈춘다. 스킬 코드 요청이 거절(401·403·code≠0)되거나 형식이 연속 바뀌면 <b>스킬 코드만</b> 재시작 전까지 멈춘다(우회하지 않음).
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

  /** 요청 단계: 페이지 GET(#531), 스킬 코드 POST(#532). */
  public enum Phase {
    PAGE,
    CODE;

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** 허가. generation은 허가 시점의 실패 세대, trial은 멈춤 뒤 시험 요청인지. */
  public record Permit(Decision decision, Phase phase, long generation, boolean trial) {
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
  private int pageFailures;
  private int pageParseFailures;
  private int codeFailures;
  private int codeParseFailures;
  private boolean stopped;
  private Instant resumeAt;
  private boolean trialInFlight;
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
            "loakraken.armory_page.skill_code.stopped",
            this,
            guard -> guard.skillCodeStopped() ? 1 : 0)
        .description("스킬 코드 요청만 멈춰 있으면 1(거절·형식 변경)")
        .register(registry);
  }

  /** 페이지 GET 자리. */
  public Permit acquire() {
    return acquire(Phase.PAGE);
  }

  /** 요청 자리를 잡는다. ALLOWED면 간격을 지키도록 기다린 뒤, 그동안 바뀐 정지·쉬기를 다시 확인하고 돌아온다. */
  public Permit acquire(Phase phase) {
    Duration wait;
    boolean trial = false;
    long issued;
    synchronized (this) {
      var now = clock.instant();
      if (phase == Phase.CODE && codeStopped) return denied(phase, Decision.STOPPED);
      if (stopped) {
        if (resumeAt == null || now.isBefore(resumeAt) || trialInFlight)
          return denied(phase, Decision.STOPPED);
        trial = true;
        trialInFlight = true;
        log.info("event=armory_page_resume_trial phase={}", phase.label());
      }
      if (now.isBefore(pauseUntil)) return release(phase, trial, Decision.BACKOFF);
      var slot = nextSlot.isAfter(now) ? nextSlot : now;
      wait = Duration.between(now, slot);
      if (wait.compareTo(policy.maxWait()) > 0) return release(phase, trial, Decision.THROTTLED);
      nextSlot = slot.plus(policy.minInterval());
      issued = generation;
    }
    if (!wait.isZero()) {
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        synchronized (this) {
          return release(phase, trial, Decision.THROTTLED);
        }
      }
    }
    synchronized (this) {
      // 기다리는 동안 다른 요청이 멈춤·쉬기를 걸었으면 나가지 않는다.
      if (phase == Phase.CODE && codeStopped) return release(phase, trial, Decision.STOPPED);
      if (stopped && !trial) return denied(phase, Decision.STOPPED);
      if (trial && stopped && resumeAt == null) return release(phase, true, Decision.STOPPED);
      if (clock.instant().isBefore(pauseUntil)) return release(phase, trial, Decision.BACKOFF);
      return new Permit(Decision.ALLOWED, phase, issued, trial);
    }
  }

  public synchronized void success(Permit permit) {
    if (permit.trial()) {
      trialInFlight = false;
      if (stopped && resumeAt != null) {
        stopped = false;
        resumeAt = null;
        log.info("event=armory_page_resumed phase={}", permit.phase().label());
      }
    }
    // 이 요청이 나간 뒤 다른 실패가 있었으면(세대가 바뀜) 그 실패의 쉬기·실패 수를 지우지 않는다.
    if (permit.generation() != generation) return;
    if (permit.phase() == Phase.PAGE) {
      pageFailures = 0;
      pageParseFailures = 0;
    } else {
      codeFailures = 0;
      codeParseFailures = 0;
    }
    pauseUntil = Instant.EPOCH;
  }

  public synchronized void failure(Permit permit, ArmoryPageFetchException.Kind kind) {
    var now = clock.instant();
    boolean code = permit.phase() == Phase.CODE;
    if (permit.trial()) trialInFlight = false;
    switch (kind) {
      case NOT_FOUND -> {
        // 그 캐릭터만의 일이다. 시험 요청이었으면 공식 쪽은 응답한 것이라 멈춤을 푼다.
        if (permit.trial() && stopped && resumeAt != null) {
          stopped = false;
          resumeAt = null;
        }
      }
      case ACCESS_DENIED -> {
        // 스킬 코드 거절은 코드만 멈춘다(페이지는 계속 읽음).
        if (code) stopSkillCode("access_denied");
        else stop("access_denied", null);
      }
      case PARSE_ERROR, TOO_LARGE -> structureFailure(permit.phase(), permit.trial());
      case HTTP_ERROR, TIMEOUT -> {
        generation++;
        int count = code ? ++codeFailures : ++pageFailures;
        var pause = policy.backoffInitial().multipliedBy(1L << Math.min(count - 1, 20));
        if (pause.compareTo(policy.backoffMax()) > 0) pause = policy.backoffMax();
        extendPause(now.plus(pause));
        log.warn(
            "event=armory_page_backoff phase={} seconds={} failures={}",
            permit.phase().label(),
            pause.toSeconds(),
            count);
        if (count >= policy.stopAfterFailures() || permit.trial())
          stop("failures", now.plus(policy.backoffMax()));
      }
    }
  }

  /** 요청 없이 알게 된 구조 변경(예: 페이지에 스킬 코드 식별 값이 없음)을 그 단계의 형식 실패로 센다. */
  public synchronized void structureFailure(Phase phase) {
    structureFailure(phase, false);
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

  public synchronized boolean skillCodeStopped() {
    return codeStopped;
  }

  private void structureFailure(Phase phase, boolean trial) {
    boolean code = phase == Phase.CODE;
    int count = code ? ++codeParseFailures : ++pageParseFailures;
    if (count >= policy.stopAfterParseFailures()) {
      if (code) stopSkillCode("structure");
      else stop("structure", null);
    } else if (trial) stop("trial_failed", clock.instant().plus(policy.backoffMax()));
  }

  private void extendPause(Instant until) {
    if (until.isAfter(pauseUntil)) pauseUntil = until;
  }

  private Permit denied(Phase phase, Decision decision) {
    return new Permit(decision, phase, generation, false);
  }

  private Permit release(Phase phase, boolean trial, Decision decision) {
    if (trial) trialInFlight = false;
    return new Permit(decision, phase, generation, false);
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

  private void stopSkillCode(String reason) {
    if (codeStopped) return;
    codeStopped = true;
    log.warn("event=armory_page_skill_code_stopped reason={} autoResume=none", reason);
  }
}
