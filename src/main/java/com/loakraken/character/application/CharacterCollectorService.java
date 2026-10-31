package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
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
  private final CharacterCardService cards;
  private final CharacterCollectionPolicy policy;
  private final CharacterSeedList seeds;
  private final CharacterPublicSummaryPort stored;
  private final LostArkRequestGate gate;
  private final LeaderLease lease;
  private final LostArkProperties lostArk;
  private final MeterRegistry registry;
  private final Clock clock;
  // 마지막 성공 시각(epoch 초). 0이면 이 인스턴스에서 아직 성공하지 않았다(#143).
  private final java.util.concurrent.atomic.AtomicLong lastSuccess =
      new java.util.concurrent.atomic.AtomicLong();
  private boolean profileTurn = true;
  private boolean seedTurn = true;

  public CharacterCollectorService(
      CharacterDetailsPort details,
      CharacterCollectionPort collection,
      CharacterHistoryService history,
      CharacterCardService cards,
      CharacterCollectionPolicy policy,
      CharacterSeedList seeds,
      CharacterPublicSummaryPort stored,
      LostArkRequestGate gate,
      LeaderLease lease,
      LostArkProperties lostArk,
      MeterRegistry registry,
      Clock clock) {
    this.details = details;
    this.collection = collection;
    this.history = history;
    this.cards = cards;
    this.policy = policy;
    this.seeds = seeds;
    this.stored = stored;
    this.gate = gate;
    this.lease = lease;
    this.lostArk = lostArk;
    this.registry = registry;
    this.clock = clock;
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.background.last_success",
            lastSuccess,
            java.util.concurrent.atomic.AtomicLong::doubleValue)
        .tag("job", "collector")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.stats.seed.pending", seeds, CharacterSeedList::pendingCount)
        .description("아직 원정대를 훑지 않은 시드 이름 수")
        .register(registry);
  }

  static final String LEASE = "stats-collector";
  static final java.time.Duration LEASE_TTL = java.time.Duration.ofSeconds(30);

  public enum Task {
    PROFILE,
    ROSTER,
    SEED,
    NONE
  }

  public synchronized Task tick() {
    if (!policy.enabled() || lostArk.apiKey().isBlank()) return Task.NONE;
    // 켜진 인스턴스끼리도 한 곳만 돈다(#107). 임대 저장소 장애면 돌지 않는다.
    if (!lease.hold(LEASE, LEASE_TTL)) {
      record(Task.NONE, "not_leader");
      return Task.NONE;
    }
    if (!gate.backgroundAvailable(policy.reserve())) {
      record(Task.NONE, "yielded");
      return Task.NONE;
    }
    try {
      // 시드(#255)와 기존 수집(프로필·원정대)을 번갈아 한다(#271). 시드가 수천 줄이어도 그동안 전투력 표본이 계속 쌓이게 하려는 것이다.
      // 한쪽에 할 일이 없으면 다른 쪽을 한다.
      if (seedTurn && seeds.pendingCount() > 0) {
        seedTurn = false;
        if (seed()) return Task.SEED;
      } else {
        seedTurn = true;
      }
      var first = profileTurn ? Task.PROFILE : Task.ROSTER;
      profileTurn = !profileTurn;
      if (run(first)) return first;
      var second = first == Task.PROFILE ? Task.ROSTER : Task.PROFILE;
      if (run(second)) return second;
      return seed() ? Task.SEED : Task.NONE;
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
        var fetched =
            gate.withReserve(
                policy.reserve(), () -> details.fetchArmory(target.get().characterName()));
        var profile = fetched.details().profile();
        // 대상 고르기는 처리정지 목록을 이미 뺀다. 공식 응답을 받는 사이에 목록에 들었으면 아무것도 남기지 않는다(관측 이력·카드·갱신 표시).
        // 확인하지 못하면(저장소 장애) 예외로 아래 기록 전에 멈춘다.
        if (stored.excluded(profile.characterName(), profile.serverName())) {
          record(task, "excluded");
          return true;
        }
        history.record(profile, now);
        cards.record(fetched.details(), fetched.krakenScoreInput(), now);
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
      var siblings =
          gate.withReserve(
              policy.reserve(), () -> details.fetchSiblings(target.get().characterName()));
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

  /**
   * 시드 하나의 원정대를 훑는다(#255). 처리정지 목록 이름과 이미 원정대를 훑은 이름은 공식 호출 없이 건너뛰고 다음 이름을 본다. 공식 호출을 했으면 true. 일시
   * 장애는 그 이름을 남겨 다음 회차에 다시 시도한다.
   */
  private boolean seed() {
    for (var name = seeds.peek(); name != null; name = seeds.peek()) {
      if (stored.excludedAnyServer(name) || collection.rosterKnown(name)) {
        seeds.done(name);
        registry.counter("loakraken.stats.seed", "outcome", "skipped").increment();
        continue;
      }
      var now = clock.instant();
      var target = name;
      try {
        var siblings = gate.withReserve(policy.reserve(), () -> details.fetchSiblings(target));
        int discovered = collection.saveRoster(siblings, now);
        seeds.done(name);
        record(Task.SEED, "success");
        registry.counter("loakraken.stats.seed", "outcome", "success").increment();
        if (discovered > 0)
          registry.counter("loakraken.stats.collector.discovered").increment(discovered);
      } catch (BusinessException exception) {
        handle(
            Task.SEED,
            exception,
            () -> {
              seeds.done(target);
              registry.counter("loakraken.stats.seed", "outcome", "failed").increment();
            });
      }
      return true;
    }
    return false;
  }

  /** 없는 캐릭터와 해석 실패는 다시 고르지 않게 표시하고, 한도·일시 장애는 다음 회차에 다시 시도한다. */
  private void handle(Task task, BusinessException exception, Runnable markDone) {
    var code = exception.errorCode();
    boolean transientFailure =
        code == CharacterErrorCode.LOST_ARK_RATE_LIMITED
            || code == LostArkErrorCode.LOST_ARK_RATE_LIMITED
            || code == LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE
            || code == CharacterErrorCode.LOST_ARK_API_TIMEOUT
            || code == CharacterErrorCode.LOST_ARK_NOT_CONFIGURED
            || code == CharacterErrorCode.CHARACTER_CACHE_UNAVAILABLE;
    if (!transientFailure) markDone.run();
    record(task, code == CharacterErrorCode.CHARACTER_NOT_FOUND ? "not_found" : "failed");
  }

  private void record(Task task, String outcome) {
    if ("success".equals(outcome)) lastSuccess.set(clock.instant().getEpochSecond());
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
