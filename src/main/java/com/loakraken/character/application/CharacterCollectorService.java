package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 통계 표본 수집기(B-3·F-7 1단계). 한 번 호출(tick)에 공식 호출을 최대 1회 한다. 프로필 갱신과 원정대 발견을 번갈아 하고, 할 일이 없는 쪽은 건너뛴다.
 *
 * <p>사용자 요청 우선: 게이트의 최근 60초 사용량이 budget − reserve 이상이면 이번 회차를 건너뛴다. 호출 한도 초과를 만나도 다음 회차까지 기다린다.
 */
@Service
public class CharacterCollectorService {
  private static final Logger log = LoggerFactory.getLogger(CharacterCollectorService.class);
  private final CharacterDetailsPort details;
  private final CharacterCollectionPort collection;
  private final CharacterHistoryService history;
  private final CharacterCollectionPolicy policy;
  private final LostArkRequestGate gate;
  private final LostArkProperties lostArk;
  private final MeterRegistry registry;
  private final Clock clock;
  private boolean profileTurn = true;

  public CharacterCollectorService(
      CharacterDetailsPort details,
      CharacterCollectionPort collection,
      CharacterHistoryService history,
      CharacterCollectionPolicy policy,
      LostArkRequestGate gate,
      LostArkProperties lostArk,
      MeterRegistry registry,
      Clock clock) {
    this.details = details;
    this.collection = collection;
    this.history = history;
    this.policy = policy;
    this.gate = gate;
    this.lostArk = lostArk;
    this.registry = registry;
    this.clock = clock;
  }

  public enum Task {
    PROFILE,
    ROSTER,
    NONE
  }

  public synchronized Task tick() {
    if (!policy.enabled() || lostArk.apiKey().isBlank()) return Task.NONE;
    if (!gate.backgroundAvailable(policy.reserve())) {
      record(Task.NONE, "yielded");
      return Task.NONE;
    }
    try {
      var first = profileTurn ? Task.PROFILE : Task.ROSTER;
      profileTurn = !profileTurn;
      if (run(first)) return first;
      var second = first == Task.PROFILE ? Task.ROSTER : Task.PROFILE;
      return run(second) ? second : Task.NONE;
    } catch (DataAccessException exception) {
      record(Task.NONE, "storage_failed");
      log.warn("통계 표본 수집 저장에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return Task.NONE;
    }
  }

  /** 할 일이 있어 공식 호출을 시도했으면 true. */
  private boolean run(Task task) {
    var now = clock.instant();
    if (task == Task.PROFILE) {
      var target =
          collection.nextProfileDue(policy.minItemLevel(), now.minus(policy.profileRefreshAfter()));
      if (target.isEmpty()) return false;
      try {
        var fetched = details.fetchArmory(target.get().characterName());
        var profile = fetched.details().profile();
        history.record(profile, now);
        var position =
            CharacterPosition.judge(profile.className(), fetched.details().loadout().arkPassive());
        collection.markProfileRefreshed(
            target.get(),
            position == null || position.role() == null ? null : position.role().name(),
            now);
        record(task, "success");
      } catch (BusinessException exception) {
        handle(task, exception, () -> collection.markProfileMissing(target.get(), now));
      }
      return true;
    }
    var target = collection.nextRosterDue(now.minus(policy.rosterRescanAfter()));
    if (target.isEmpty()) return false;
    try {
      var siblings = details.fetchSiblings(target.get().characterName());
      int discovered = collection.saveRoster(siblings, now);
      collection.markRosterScanned(target.get(), now);
      record(task, "success");
      if (discovered > 0)
        registry.counter("loakraken.stats.collector.discovered").increment(discovered);
    } catch (BusinessException exception) {
      handle(task, exception, () -> collection.markRosterScanned(target.get(), now));
    }
    return true;
  }

  /** 없는 캐릭터와 해석 실패는 다시 고르지 않게 표시하고, 한도·일시 장애는 다음 회차에 다시 시도한다. */
  private void handle(Task task, BusinessException exception, Runnable markDone) {
    var code = exception.errorCode();
    boolean transientFailure =
        code == CharacterErrorCode.LOST_ARK_RATE_LIMITED
            || code == LostArkErrorCode.LOST_ARK_RATE_LIMITED
            || code == CharacterErrorCode.LOST_ARK_API_TIMEOUT
            || code == CharacterErrorCode.LOST_ARK_NOT_CONFIGURED
            || code == CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE;
    if (!transientFailure) markDone.run();
    record(task, code == CharacterErrorCode.CHARACTER_NOT_FOUND ? "not_found" : "failed");
  }

  private void record(Task task, String outcome) {
    registry
        .counter(
            "loakraken.stats.collector",
            "task",
            task.name().toLowerCase(Locale.ROOT),
            "outcome",
            outcome)
        .increment();
  }
}
