package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.common.api.BusinessException;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.specup.domain.SpecUpCost;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 스펙업 목표 경로(L31). 목표 전투력에 닿을 때까지 지금 상태에서 칸마다 '다음 한 단계'를 만들고, 효율 순(1만 골드당 전투력, 비용을 모르면 그 뒤에 Δ 순)으로
 * 가장 앞의 한 단계를 적용하기를 되풀이한다. 같은 칸을 여러 단계 올릴 수 있다. 모든 전투력은 시뮬레이터 결과이고(합산·추정 없음), 공식 API를 부르지 않는다.
 *
 * <p>비용: 시세가 없는 단계가 하나라도 있으면 그 단계부터 누적 비용은 null이다(0으로 더하지 않음). 보석 같은 칸의 둘째 단계부터는 직전 단계 매입가를 뺀
 * 증분이다(PM-백엔드 L31 판정 ①).
 */
@Service
public class SpecUpGoalService {
  public static final String RULE_VERSION = "spec-up-goal-v2";
  static final int MAX_STEPS = 30;
  static final Duration TIME_LIMIT = Duration.ofSeconds(2);
  static final Duration CACHE_TTL = Duration.ofMinutes(10);
  static final String REACHED = "REACHED";
  static final String ALREADY_REACHED = "ALREADY_REACHED";
  static final String NO_CANDIDATE = "NO_CANDIDATE";
  static final String STEP_LIMIT = "STEP_LIMIT";
  static final String TIME_LIMITED = "TIME_LIMIT";
  private static final Map<String, String> STOP_TEXT =
      Map.of(
          REACHED, "목표 전투력에 닿았어요.",
          ALREADY_REACHED, "이미 목표 전투력 이상이에요.",
          NO_CANDIDATE, "더 올릴 수 있는 항목이 없어 여기서 멈췄어요.",
          STEP_LIMIT, "단계가 너무 많아 30단계에서 멈췄어요. 목표를 낮춰 보세요.",
          TIME_LIMITED, "계산 시간이 길어 여기까지 고른 단계만 보여 드려요.");

  private final SpecUpService specUp;
  private final CombatPowerSimulationService simulator;
  private final MarketAuctionService auctions;
  private final RelicBookPriceService books;
  private final RefiningMaterialPriceService materials;
  private final SpecUpGoalStorePort store;
  private final LongSupplier nanoTime;

  /** 추천 순서 계산(캐시에 없을 때) 동시 수. 넘으면 기다리지 않고 429 SPEC_UP_BUSY(요청 스레드를 붙잡지 않게). */
  private final java.util.concurrent.Semaphore pathSolves;

  private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
      new ConcurrentHashMap<>();

  @Autowired
  public SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store,
      MeterRegistry meters) {
    this(specUp, simulator, auctions, books, materials, store, meters, System::nanoTime);
  }

  SpecUpGoalService(
      SpecUpService specUp,
      CombatPowerSimulationService simulator,
      MarketAuctionService auctions,
      RelicBookPriceService books,
      RefiningMaterialPriceService materials,
      SpecUpGoalStorePort store,
      MeterRegistry meters,
      LongSupplier nanoTime) {
    this.specUp = specUp;
    this.simulator = simulator;
    this.auctions = auctions;
    this.books = books;
    this.materials = materials;
    this.store = store;
    this.nanoTime = nanoTime;
    this.meters = meters;
    this.pathSolves = new java.util.concurrent.Semaphore(PATH_MAX_CONCURRENT);
  }

  private static final Logger log = LoggerFactory.getLogger(SpecUpGoalService.class);

  /**
   * 추천 순서 운영 지표. loakraken.spec_up.path{outcome=cache_hit|computed|busy}: 캐시 적중·계산·자리 부족(429) 비율.
   * loakraken.spec_up.path_compute{pick, stop}: 캐시에 없을 때 계산 시간(p95·p99), stop은 멈춘 이유 코드(작은 고정 집합).
   * 캐릭터 이름은 태그·로그에 넣지 않는다.
   */
  private final MeterRegistry meters;

  private void pathOutcome(String outcome) {
    meters.counter("loakraken.spec_up.path", "outcome", outcome).increment();
  }

  // v2: 완갑(팔 보호구) 재련 단계를 고른다. v3: 어빌리티 스톤 목표 조합(각인 둘을 한 단계에), 단계 changes.
  public static final String PATH_RULE_VERSION = "spec-up-path-v3";
  static final int PATH_MAX_STEPS = 30;
  static final int PATH_MAX_CONCURRENT = 2;
  static final Duration PATH_RETRY_AFTER = Duration.ofSeconds(2);
  private static final Map<String, String> PATH_STOP_TEXT =
      Map.of(
          NO_CANDIDATE, "시세가 있는 항목 가운데 전투력이 오르는 것을 모두 골랐어요.",
          STEP_LIMIT, "정한 단계 수까지 보여 드려요.",
          TIME_LIMITED, "계산 시간이 길어 여기까지 고른 단계만 보여 드려요.");

  /** 순서에서 뺀 항목(지금 상태에서 시세를 몰라 고를 수 없는 다음 단계 중 전투력이 오르는 것). 추측 비용을 넣지 않는다. */
  public record Excluded(
      String candidateId,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      BigDecimal deltaCombatPower,
      SpecUpCost cost) {}

  /** 추천 순서(이어 고르기): 목표 없이 limit 단계까지. steps는 시세가 있는 단계만, unpriced는 지금 상태 기준 뺀 항목. */
  public record Path(
      String characterName,
      String serverName,
      String className,
      BigDecimal combatPower,
      BigDecimal finalCombatPower,
      String pick,
      int limit,
      String stopReasonCode,
      String stopReason,
      Long totalCost,
      String ruleVersion,
      String simulatorRuleVersion,
      Instant fetchedAt,
      Instant pricesAt,
      List<Step> steps,
      List<Excluded> unpriced) {}

  /**
   * 추천 순서(스펙업 순서 시안 '나'): 지금 상태에서 pick 기준으로 가장 앞의 다음 한 단계를 적용하고 다시 재기를 limit 단계까지(목표 없음). 시세(더 드는
   * 비용)를 모르는 단계는 고르지 않고 unpriced로 따로 준다. 목표 경로와 같은 단계·비용·시뮬레이터를 쓴다. 같은 캐릭터 수집 시각·시세·기준·단계 수는 10분
   * 캐시, 같은 키는 한 번만 계산, 캐시에 없는 계산은 동시에 PATH_MAX_CONCURRENT개까지(넘으면 바로 429).
   */
  public Path path(String characterName, Pick pick, int limit) {
    if (!specUp.enabled()) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    var profile = snapshot.details().profile();
    var before =
        profile.combatPower() == null
            ? null
            : profile.combatPower().setScale(2, RoundingMode.HALF_UP);
    var gems = gemPrices();
    var refinePrices = materials.cached();
    var key =
        String.join(
            ":",
            profile.serverName(),
            profile.characterName(),
            pick.name(),
            String.valueOf(limit),
            String.valueOf(snapshot.fetchedAt() == null ? 0 : snapshot.fetchedAt().toEpochMilli()),
            String.valueOf(latest(gems)),
            staleSignature(gems),
            String.valueOf(refinePrices.map(p -> p.observedAt().toEpochMilli()).orElse(0L)),
            String.valueOf(books.observedAt().map(Instant::toEpochMilli).orElse(0L)),
            PATH_RULE_VERSION,
            CombatPowerSimulationService.RULE_VERSION,
            String.valueOf(specUp.costRuleVersions()),
            specUp.armGuardEnabled() ? "ag" : "-");
    var cached = store.findPath(key);
    if (cached.isPresent()) {
      pathOutcome("cache_hit");
      return cached.get();
    }
    return single(
        "path:" + key,
        () -> {
          if (!pathSolves.tryAcquire()) {
            pathOutcome("busy");
            log.warn(
                "event=spec_up_path_busy pick={} limit={} slots={}",
                pick,
                limit,
                PATH_MAX_CONCURRENT);
            throw new BusinessException(SpecUpErrorCode.SPEC_UP_BUSY, PATH_RETRY_AFTER);
          }
          try {
            long started = nanoTime.getAsLong();
            var result = computePath(snapshot, before, pick, limit, gems, refinePrices);
            long elapsed = nanoTime.getAsLong() - started;
            pathOutcome("computed");
            Timer.builder("loakraken.spec_up.path_compute")
                .tag("pick", pick.name())
                .tag("stop", result.stopReasonCode())
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters)
                .record(Duration.ofNanos(elapsed));
            if (TIME_LIMITED.equals(result.stopReasonCode()))
              log.warn(
                  "event=spec_up_path_time_limit pick={} limit={} steps={} elapsedMs={}",
                  pick,
                  limit,
                  result.steps().size(),
                  elapsed / 1_000_000);
            else store.savePath(key, result, CACHE_TTL);
            return result;
          } finally {
            pathSolves.release();
          }
        });
  }

  private Path computePath(
      CharacterResult snapshot,
      BigDecimal before,
      Pick pick,
      int limit,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices) {
    var profile = snapshot.details().profile();
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    if (roleReason != null || before == null) {
      var reason =
          roleReason != null
              ? CombatPowerReasons.classify(roleReason)
              : CombatPowerReasons.NO_OFFICIAL_COMBAT_POWER;
      return new Path(
          profile.characterName(),
          profile.serverName(),
          profile.className(),
          before,
          before,
          pick.name(),
          limit,
          reason.code(),
          reason.text(),
          null,
          PATH_RULE_VERSION,
          CombatPowerSimulationService.RULE_VERSION,
          snapshot.fetchedAt(),
          null,
          List.of(),
          List.of());
    }
    // 추천 순서는 완갑(팔 보호구) 재련도 고른다(목록 완갑 스위치를 따름). 목표 경로는 그대로(완갑 없음).
    boolean armGuard = specUp.armGuardEnabled();
    var chain = chain(snapshot, before, null, pick, true, limit, armGuard, gems, refinePrices);
    var unpriced = unpricedAtStart(snapshot, before, armGuard, gems, refinePrices);
    return new Path(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        before,
        chain.last(),
        pick.name(),
        limit,
        chain.stop(),
        PATH_STOP_TEXT.get(chain.stop()),
        chain.cumulative(),
        PATH_RULE_VERSION,
        CombatPowerSimulationService.RULE_VERSION,
        snapshot.fetchedAt(),
        chain.pricesAt(),
        chain.steps(),
        unpriced);
  }

  /** 지금 상태에서 비용을 몰라 순서에서 뺀 다음 단계 중 전투력이 오르는 것. Δ 큰 순, 같으면 id. */
  private List<Excluded> unpricedAtStart(
      CharacterResult snapshot,
      BigDecimal before,
      boolean armGuard,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices) {
    var pricesAt = new Instant[1];
    var result = new ArrayList<Excluded>();
    for (var option :
        options(
            State.of(snapshot.details().loadout(), armGuard, true), gems, refinePrices, pricesAt)) {
      if (option.cost() != null && option.cost().amount() != null) continue;
      var simulated = simulator.simulate(snapshot, option.changes());
      if (simulated.unsupportedCount() > 0 || simulated.after() == null) continue;
      var delta = simulated.after().subtract(before);
      if (delta.signum() <= 0) continue;
      result.add(
          new Excluded(
              option.id(),
              option.kind(),
              option.target(),
              option.from(),
              option.to(),
              option.icon(),
              option.grade(),
              option.skillName(),
              delta,
              option.cost()));
    }
    result.sort(
        Comparator.comparing(Excluded::deltaCombatPower, Comparator.reverseOrder())
            .thenComparing(Excluded::candidateId));
    return List.copyOf(result);
  }

  /** 경로의 한 단계. 표시 필드는 스펙업 후보와 같은 뜻이고 change는 시뮬레이터 요청 changes[] 하나와 같다. */
  public record Step(
      int order,
      String candidateId,
      String kind,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      BigDecimal deltaCombatPower,
      BigDecimal combatPowerAfter,
      SpecUpCost cost,
      Long cumulativeCost,
      BigDecimal combatPowerPer10kGold,
      Long extraCost,
      List<Change> changes) {}

  public record Goal(
      String characterName,
      String serverName,
      String className,
      BigDecimal combatPower,
      BigDecimal targetCombatPower,
      BigDecimal finalCombatPower,
      boolean reached,
      String stopReasonCode,
      String stopReason,
      Long totalCost,
      int unpricedSteps,
      String ruleVersion,
      String simulatorRuleVersion,
      Instant fetchedAt,
      Instant pricesAt,
      List<Step> steps) {}

  public Goal goal(String characterName, BigDecimal target) {
    if (!specUp.enabled()) throw new BusinessException(SpecUpErrorCode.SPEC_UP_DISABLED);
    var snapshot = simulator.snapshot(characterName);
    var profile = snapshot.details().profile();
    var before =
        profile.combatPower() == null
            ? null
            : profile.combatPower().setScale(2, RoundingMode.HALF_UP);
    var goal = target.setScale(2, RoundingMode.HALF_UP);
    if (before != null && goal.compareTo(before.multiply(BigDecimal.TWO)) > 0)
      throw new BusinessException(SpecUpErrorCode.SPEC_UP_GOAL_OUT_OF_RANGE);
    var gems = gemPrices();
    var refinePrices = materials.cached();
    var key =
        String.join(
            ":",
            profile.serverName(),
            profile.characterName(),
            goal.toPlainString(),
            String.valueOf(snapshot.fetchedAt() == null ? 0 : snapshot.fetchedAt().toEpochMilli()),
            String.valueOf(latest(gems)),
            // 이전 시세(stale)로 바뀐 칸은 관측 시각이 그대로라, 어느 칸이 이전 시세인지도 키에 넣는다(코드 리뷰 10-25 P1: 지금 시세로 만든 경로를
            // 이전 시세가 된 뒤에도 stale=false로 주지 않게).
            staleSignature(gems),
            String.valueOf(refinePrices.map(p -> p.observedAt().toEpochMilli()).orElse(0L)),
            // 각인서 시세가 바뀌면 각인 비용이 달라지므로 다시 계산한다(시세가 그대로면 캐시 그대로).
            String.valueOf(books.observedAt().map(Instant::toEpochMilli).orElse(0L)),
            RULE_VERSION,
            CombatPowerSimulationService.RULE_VERSION,
            String.valueOf(specUp.costRuleVersions()));
    var cached = store.find(key);
    if (cached.isPresent()) return cached.get();
    return single(
        key,
        () -> {
          var result = compute(snapshot, before, goal, gems, refinePrices);
          // 시간 상한으로 멈춘 부분 결과는 캐시하지 않는다(PM-프론트 L31 ③).
          if (!TIME_LIMITED.equals(result.stopReasonCode())) store.save(key, result, CACHE_TTL);
          return result;
        });
  }

  private Goal compute(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices) {
    var roleReason = CombatPowerSimulationService.roleReason(snapshot);
    if (roleReason != null || before == null) {
      var reason =
          roleReason != null
              ? CombatPowerReasons.classify(roleReason)
              : CombatPowerReasons.NO_OFFICIAL_COMBAT_POWER;
      return result(
          snapshot, before, target, before, reason.code(), reason.text(), null, 0, null, List.of());
    }
    if (before.compareTo(target) >= 0)
      return result(
          snapshot, before, target, before, ALREADY_REACHED, null, 0L, 0, null, List.of());
    var chain =
        chain(
            snapshot, before, target, Pick.EFFICIENCY, false, MAX_STEPS, false, gems, refinePrices);
    return result(
        snapshot,
        before,
        target,
        chain.last(),
        chain.stop(),
        null,
        chain.cumulative(),
        chain.unpriced(),
        chain.pricesAt(),
        chain.steps());
  }

  /** 다음 단계를 고르는 기준. EFFICIENCY: 더 드는 비용 1만 골드당 전투력(목표 경로와 같음). DELTA: 전투력 증가가 큰 순(같으면 효율·id). */
  public enum Pick {
    EFFICIENCY,
    DELTA
  }

  /** 한 번 이어 고른 결과. last = 마지막 단계 뒤 전투력(단계가 없으면 처음 전투력). */
  record Chain(
      List<Step> steps,
      String stop,
      Long cumulative,
      int unpriced,
      BigDecimal last,
      Instant pricesAt) {}

  /**
   * 지금 상태에서 다음 한 단계를 고르고 적용한 뒤 다시 모든 칸의 다음 단계를 시뮬레이터로 재기를 되풀이한다(사슬). target이 있으면 닿을 때 멈추고,
   * pricedOnly면 비용(더 드는 비용)을 모르는 단계는 고르지 않는다. 같은 입력이면 같은 사슬이다(같으면 id 순).
   */
  private Chain chain(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      Pick pick,
      boolean pricedOnly,
      int maxSteps,
      boolean armGuard,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices) {
    var pricesAt = new Instant[1];
    var steps = new ArrayList<Step>();
    // 추천 순서(armGuard 인자를 받는 쪽)는 어빌리티 스톤도 고른다. 목표 경로는 그대로.
    var state = State.of(snapshot.details().loadout(), armGuard, pricedOnly);
    // 칸마다 그 칸의 마지막 단계 변경(스톤은 각인 둘이라 여러 개).
    var applied = new LinkedHashMap<String, List<Change>>();
    var current = before;
    Long cumulative = 0L;
    int unpriced = 0;
    // 경로 합계: 보석 칸은 그 칸의 마지막 단계 누적 비용(칸마다 하나), 그 밖은 단계 비용의 합. 모르는 값이 있으면 합계도 모름.
    var gemTotals = new HashMap<String, Long>();
    long others = 0;
    boolean othersUnknown = false;
    long started = nanoTime.getAsLong();
    String stop;
    while (true) {
      if (target != null && current.compareTo(target) >= 0) {
        stop = REACHED;
        break;
      }
      if (steps.size() >= maxSteps) {
        stop = STEP_LIMIT;
        break;
      }
      if (nanoTime.getAsLong() - started > TIME_LIMIT.toNanos()) {
        stop = TIME_LIMITED;
        break;
      }
      Option best = null;
      boolean bestFree = false;
      Long bestMarginal = null;
      for (var option : options(state, gems, refinePrices, pricesAt)) {
        // 고르는 효율은 이 단계에 더 드는 비용(보석은 누적 차이)으로: 같은 칸을 이어서 올릴 때 이미 낸 비용을 다시 세지 않는다.
        var marginal = marginal(option, gemTotals);
        if (pricedOnly && marginal == null) continue;
        var trial = new LinkedHashMap<>(applied);
        trial.put(option.slot(), option.changes());
        var simulated = simulator.simulate(snapshot, flatten(trial));
        // 지금까지 적용한 단계는 모두 계산된 것이므로, 계산하지 못한 변경이 있으면 이 후보다.
        if (simulated.unsupportedCount() > 0 || simulated.after() == null) continue;
        var delta = simulated.after().subtract(current);
        if (delta.signum() <= 0) continue;
        // 고르기는 반올림하지 않은 비율로(소수 둘째 자리에서 같아져 id 순으로 갈리지 않게, 계산 검증 11-01). 화면 값은 단계를 만들 때 따로 반올림한다.
        var scored = option.scored(delta, simulated.after(), rank(delta, marginal));
        boolean free = free(marginal);
        if (best == null
            || (pick == Pick.DELTA
                ? BY_DELTA.compare(scored, best) < 0
                : preferred(scored, free, best, bestFree))) {
          best = scored;
          bestFree = free;
          bestMarginal = marginal;
        }
      }
      if (best == null) {
        stop = NO_CANDIDATE;
        break;
      }
      applied.put(best.slot(), best.changes());
      state = state.after(best);
      current = best.after();
      var amount = best.cost() == null ? null : best.cost().amount();
      if (amount == null) unpriced++;
      if (best.change().kind() == Kind.GEM_LEVEL) gemTotals.put(best.slot(), amount);
      else if (amount == null) othersUnknown = true;
      else others += amount;
      cumulative = total(others, othersUnknown, gemTotals);
      steps.add(
          new Step(
              steps.size() + 1,
              best.id(),
              best.kind(),
              best.target(),
              best.from(),
              best.to(),
              best.icon(),
              best.grade(),
              best.skillName(),
              best.change(),
              best.delta(),
              best.after(),
              best.cost(),
              cumulative,
              SpecUpCost.efficiency(best.delta(), bestMarginal),
              bestMarginal,
              best.changes()));
    }
    return new Chain(List.copyOf(steps), stop, cumulative, unpriced, current, pricesAt[0]);
  }

  private static List<Change> flatten(Map<String, List<Change>> applied) {
    var changes = new ArrayList<Change>();
    for (var list : applied.values()) changes.addAll(list);
    return List.copyOf(changes);
  }

  /** DELTA 고르기: 전투력 증가 큰 순 → 효율 높은 순(없으면 뒤) → id. */
  static final Comparator<Option> BY_DELTA =
      Comparator.comparing(Option::delta, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::efficiency, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::id);

  private static Goal result(
      CharacterResult snapshot,
      BigDecimal before,
      BigDecimal target,
      BigDecimal last,
      String code,
      String text,
      Long totalCost,
      int unpriced,
      Instant pricesAt,
      List<Step> steps) {
    var profile = snapshot.details().profile();
    return new Goal(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        before,
        target,
        last,
        last != null && last.compareTo(target) >= 0,
        code,
        text != null ? text : STOP_TEXT.get(code),
        totalCost,
        unpriced,
        RULE_VERSION,
        CombatPowerSimulationService.RULE_VERSION,
        snapshot.fetchedAt(),
        pricesAt,
        List.copyOf(steps));
  }

  /** 효율 있음(내림차순) → 비용 모름(Δ 내림차순) → id 순. 스펙업 가이드의 효율 순과 같다. */
  /** 이 단계에 더 드는 비용: 보석은 지금 누적 − 그 칸에서 이미 낸 누적(처음 단계는 0), 그 밖은 단계 비용. 모르면 null. */
  static Long marginal(Option option, Map<String, Long> gemTotals) {
    var amount = option.cost() == null ? null : option.cost().amount();
    if (amount == null || option.change().kind() != Kind.GEM_LEVEL) return amount;
    var paid =
        gemTotals.containsKey(option.slot()) ? gemTotals.get(option.slot()) : Long.valueOf(0);
    return paid == null ? null : amount - paid;
  }

  /** 더 드는 비용이 0 이하로 확인된 단계(거꾸로 시세의 보석 단계 등). 효율은 '-'(null)이지만 경로에는 먼저 넣는다. */
  static boolean free(Long marginal) {
    return marginal != null && marginal <= 0;
  }

  /**
   * 다음 단계 고르기: 목표 경로는 '가장 싸게 목표까지 가는 계획'이라 더 드는 비용이 0 이하인 단계를 맨 앞에(Δ 큰 순) 넣어 단계 상한에 밀려 빠지지 않게 하고, 그
   * 밖은 ORDER(효율 높은 순, 효율 없음은 뒤, 같으면 Δ·id). 목록 정렬(효율 없는 줄은 뒤)과는 다르다(PM 판정 10-18).
   */
  static boolean preferred(Option candidate, boolean candidateFree, Option best, boolean bestFree) {
    if (candidateFree != bestFree) return candidateFree;
    if (candidateFree) {
      int byDelta = best.delta().compareTo(candidate.delta());
      return byDelta != 0 ? byDelta < 0 : candidate.id().compareTo(best.id()) < 0;
    }
    return ORDER.compare(candidate, best) < 0;
  }

  /** 경로 합계(보석 칸마다 마지막 누적 + 그 밖 합). 모르는 값이 있으면 null. */
  static Long total(long others, boolean othersUnknown, Map<String, Long> gemTotals) {
    if (othersUnknown || gemTotals.containsValue(null)) return null;
    long sum = others;
    for (var value : gemTotals.values()) sum += value;
    return sum;
  }

  /**
   * 고르는 효율: Δ × 10,000 ÷ 더 드는 비용(반올림 없음, 소수 12자리). 더 드는 비용이 0 이하이거나 모르면 null. 화면 값은
   * SpecUpCost.efficiency.
   */
  static BigDecimal rank(BigDecimal delta, Long marginal) {
    return SpecUpCost.rankEfficiency(delta, marginal);
  }

  static final Comparator<Option> ORDER =
      Comparator.comparing((Option option) -> option.efficiency() == null ? 1 : 0)
          .thenComparing(Option::efficiency, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::delta, Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(Option::id);

  /** 지금 상태에서 칸마다 다음 한 단계. */
  private List<Option> options(
      State state,
      Map<String, MarketAuctionService.GemCell> gems,
      Optional<RefiningMaterialPriceService.Prices> refinePrices,
      Instant[] pricesAt) {
    var result = new ArrayList<Option>();
    for (var gem : state.gems().values()) {
      if (gem.level() >= 10) continue;
      int next = gem.level() + 1;
      String label = gem.level() + "레벨 " + (gem.kind() == null ? "보석" : gem.kind());
      String target =
          gem.skill() == null ? "보석 " + gem.slot() + "번 · " + label : gem.skill() + " · " + label;
      result.add(
          new Option(
              "GEM_LEVEL:" + gem.slot(),
              "GEM_LEVEL:" + gem.slot() + ":" + next,
              target,
              gem.level() + "레벨",
              next + "레벨",
              gem.icon(),
              gem.grade(),
              gem.skill(),
              new Change(
                  Kind.GEM_LEVEL,
                  gem.slot(),
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              gemCost(gems, pricesAt, gem, next),
              null,
              null,
              null));
    }
    for (var item : state.refines().values()) {
      int next = item.refine() + 1;
      result.add(
          new Option(
              "EQUIPMENT_REFINE:" + item.index(),
              "EQUIPMENT_REFINE:" + item.index() + ":" + next,
              item.equipment().name(),
              "+" + item.refine(),
              "+" + next,
              item.equipment().icon(),
              item.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_REFINE,
                  null,
                  item.index(),
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              item.refine() >= 10 && item.refine() < 25
                  ? specUp.refineCost(item.equipment(), item.refine(), refinePrices, pricesAt)
                  : SpecUpCost.REFINE_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    if (state.armGuard() != null) {
      var item = state.armGuard();
      int next = item.refine() + 1;
      // 등급은 목록 완갑 줄과 같이 처음 등급에서 목표 단계로 정한다(시뮬레이터 ARM_GUARD는 목표 단계·등급을 그대로 받는다).
      var grade = specUp.armGuardGrade(item.equipment().grade(), next);
      if (grade != null)
        result.add(
            new Option(
                "EQUIPMENT_REFINE:" + item.index(),
                "EQUIPMENT_REFINE:" + item.index() + ":" + next,
                item.equipment().name(),
                "+" + item.refine(),
                "+" + next + (grade.equals(item.equipment().grade()) ? "" : " " + grade),
                item.equipment().icon(),
                item.equipment().grade(),
                null,
                new Change(
                    Kind.ARM_GUARD,
                    null,
                    null,
                    null,
                    next,
                    null,
                    null,
                    null,
                    grade,
                    null,
                    null,
                    null,
                    null,
                    null),
                specUp.armGuardStepCost(item.refine(), refinePrices, pricesAt),
                null,
                null,
                null));
    }
    for (var item : state.advanced().values()) {
      int next = item.refine() + 1;
      result.add(
          new Option(
              "EQUIPMENT_ADVANCED_REFINE:" + item.index(),
              "EQUIPMENT_ADVANCED_REFINE:" + item.index() + ":" + next,
              item.equipment().name(),
              "상급 재련 " + item.refine() + "단계",
              "상급 재련 " + next + "단계",
              item.equipment().icon(),
              item.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_ADVANCED_REFINE,
                  null,
                  item.index(),
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              SpecUpCost.REFINE_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    if (state.quality() != null) {
      var weapon = state.quality();
      int next = Math.min(100, (weapon.refine() / 10 + 1) * 10);
      result.add(
          new Option(
              "EQUIPMENT_QUALITY:" + weapon.index(),
              "EQUIPMENT_QUALITY:" + weapon.index() + ":" + next,
              weapon.equipment().name(),
              "품질 " + weapon.refine(),
              "품질 " + next,
              weapon.equipment().icon(),
              weapon.equipment().grade(),
              null,
              new Change(
                  Kind.EQUIPMENT_QUALITY,
                  null,
                  weapon.index(),
                  null,
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null),
              SpecUpCost.QUALITY_COST_UNCONFIRMED,
              null,
              null,
              null));
    }
    for (var engraving : state.engravings().values()) {
      int next = engraving.level() + 1;
      result.add(
          new Option(
              "ENGRAVING_LEVEL:" + engraving.name(),
              "ENGRAVING_LEVEL:" + engraving.name() + ":" + next,
              engraving.name() + " 각인",
              engraving.level() + "레벨",
              next + "레벨",
              null,
              engraving.grade(),
              null,
              new Change(
                  Kind.ENGRAVING_LEVEL,
                  null,
                  null,
                  null,
                  next,
                  null,
                  null,
                  null,
                  null,
                  engraving.name(),
                  null,
                  null,
                  null,
                  null),
              SpecUpService.bookCost(engraving.name(), engraving.grade(), books::price, pricesAt),
              null,
              null,
              null));
    }
    if (state.stone() != null) result.addAll(stoneOptions(state.stone()));
    return result;
  }

  /**
   * 어빌리티 스톤 목표 조합(목록 스톤 줄과 같은 목표·id·비용): 조합마다 두 배치(어느 각인에 높은 레벨). 한 단계에 각인 둘(STONE_ENGRAVING 둘)을
   * 바꾼다. 비용은 새 스톤 기대 비용 전부(스톤은 이어 올리지 않고 새로 깎는다). 지금과 같은 레벨 조합은 뺀다.
   */
  static List<Option> stoneOptions(Stone stone) {
    var names = List.copyOf(stone.levels().keySet());
    var result = new ArrayList<Option>();
    var seen = new java.util.HashSet<String>();
    for (var target : SpecUpScoreCandidates.STONE_TARGETS) {
      var label = target[0] + "" + target[1] + "돌";
      var gold = com.loakraken.specup.domain.SpecUpCostRules.stoneGold(label);
      var cost = gold == null ? SpecUpCost.STONE_COST_UNCONFIRMED : SpecUpCost.stone(gold);
      for (var order :
          List.of(new int[] {target[2], target[3]}, new int[] {target[3], target[2]})) {
        var levels = new LinkedHashMap<String, Integer>();
        levels.put(names.get(0), order[0]);
        levels.put(names.get(1), order[1]);
        if (levels.equals(stone.levels())) continue;
        var id = "ABILITY_STONE:" + order[0] + ":" + order[1];
        if (!seen.add(id)) continue;
        result.add(
            new Option(
                "ABILITY_STONE",
                id,
                stone.item().name(),
                stoneText(stone.levels()),
                label + " " + stoneText(levels),
                stone.item().icon(),
                stone.item().grade(),
                null,
                stoneChange(names.get(0), order[0]),
                cost,
                null,
                null,
                null,
                List.of(stoneChange(names.get(1), order[1]))));
      }
    }
    return result;
  }

  /** 어빌리티 스톤의 감소 줄 이름(각인이 아니라 스톤 단계에서 바꾸지 않는다). */
  static final java.util.Set<String> STONE_PENALTIES =
      java.util.Set.of("공격력 감소", "방어력 감소", "공격속도 감소", "이동속도 감소");

  private static Change stoneChange(String name, int level) {
    return new Change(
        Kind.STONE_ENGRAVING,
        null,
        null,
        null,
        level,
        null,
        null,
        null,
        null,
        name,
        null,
        null,
        null,
        null);
  }

  private static String stoneText(Map<String, Integer> levels) {
    var parts = new ArrayList<String>();
    levels.forEach((name, level) -> parts.add(name + " Lv." + level));
    return String.join(" · ", parts);
  }

  /**
   * 보석 단계 비용 = 지금에서 그 레벨까지의 누적 비용(목록 직접 비용과 같은 식: max(그 레벨 최저가 − 지금 보석 최저가, 0), 귀속·빈 칸이면 그 레벨 최저가).
   * 단계마다 따로 구한다 — 그 레벨 시세가 없으면 그 단계만 모름, 지금 레벨 시세가 없으면 모든 단계가 모름(PM 판정 10-18 15:18·15:22).
   */
  static SpecUpCost gemCost(
      Map<String, MarketAuctionService.GemCell> gems, Instant[] pricesAt, GemSlot gem, int next) {
    return SpecUpService.gemCost(
        gems, pricesAt, gem.priceKind(), gem.original(), next, gem.bound());
  }

  private Map<String, MarketAuctionService.GemCell> gemPrices() {
    var prices = new HashMap<String, MarketAuctionService.GemCell>();
    for (var cell : auctions.cachedGems()) prices.put(cell.kind() + ":" + cell.level(), cell);
    return prices;
  }

  private static long latest(Map<String, MarketAuctionService.GemCell> gems) {
    long latest = 0;
    for (var cell : gems.values())
      if (cell.cell() != null && cell.cell().observedAt() != null)
        latest = Math.max(latest, cell.cell().observedAt().toEpochMilli());
    return latest;
  }

  /** 이전 시세 칸 수와 어느 칸인지(정렬한 칸 이름의 해시). 이전 시세가 없으면 "0". */
  static String staleSignature(Map<String, MarketAuctionService.GemCell> gems) {
    var stale = new java.util.TreeSet<String>();
    for (var entry : gems.entrySet())
      if (entry.getValue().stale() && entry.getValue().cell() != null) stale.add(entry.getKey());
    return stale.isEmpty()
        ? "0"
        : stale.size() + "-" + Integer.toHexString(String.join(",", stale).hashCode());
  }

  @SuppressWarnings("unchecked")
  private <T> T single(String key, java.util.function.Supplier<T> loader) {
    var future = new CompletableFuture<Object>();
    var existing = flights.putIfAbsent(key, future);
    if (existing != null) {
      try {
        return (T) existing.join();
      } catch (CompletionException exception) {
        if (exception.getCause() instanceof RuntimeException cause) throw cause;
        throw exception;
      }
    }
    try {
      var result = loader.get();
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      flights.remove(key, future);
    }
  }

  /** 다음 한 단계 후보. delta·after·efficiency는 시뮬레이터로 잰 뒤 채운다. */
  record Option(
      String slot,
      String id,
      String target,
      String from,
      String to,
      String icon,
      String grade,
      String skillName,
      Change change,
      SpecUpCost cost,
      BigDecimal delta,
      BigDecimal after,
      BigDecimal efficiency,
      List<Change> more) {
    /** 변경이 하나인 단계. */
    Option(
        String slot,
        String id,
        String target,
        String from,
        String to,
        String icon,
        String grade,
        String skillName,
        Change change,
        SpecUpCost cost,
        BigDecimal delta,
        BigDecimal after,
        BigDecimal efficiency) {
      this(
          slot,
          id,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          delta,
          after,
          efficiency,
          List.of());
    }

    Option scored(BigDecimal delta, BigDecimal after, BigDecimal efficiency) {
      return new Option(
          slot,
          id,
          target,
          from,
          to,
          icon,
          grade,
          skillName,
          change,
          cost,
          delta,
          after,
          efficiency,
          more);
    }

    /** 이 단계의 시뮬레이터 변경 전부(change가 먼저). */
    List<Change> changes() {
      if (more == null || more.isEmpty()) return List.of(change);
      var all = new ArrayList<Change>();
      all.add(change);
      all.addAll(more);
      return List.copyOf(all);
    }

    /** 단계 종류: 변경이 하나면 그 kind, 여럿이면(어빌리티 스톤) 후보 id의 종류(목록과 같음). */
    String kind() {
      if (more == null || more.isEmpty()) return change.kind().name();
      int colon = id.indexOf(':');
      return colon < 0 ? id : id.substring(0, colon);
    }
  }

  /** 어빌리티 스톤: 각인 이름 → 레벨(스톤 줄 순서), 표시용 장비. */
  record Stone(CharacterLoadout.Equipment item, Map<String, Integer> levels) {}

  record GemSlot(
      int slot,
      String kind,
      String priceKind,
      int original,
      int level,
      boolean bound,
      String skill,
      String icon,
      String grade) {}

  /** refine은 재련 단계(상급 재련이면 상급 단계, 품질이면 품질 값)다. */
  record Item(int index, CharacterLoadout.Equipment equipment, int refine) {}

  record Engraving(String name, String grade, int level) {}

  /** 지금까지 적용한 단계를 반영한 칸별 상태. 다음 단계가 없는 칸은 빠진다. */
  record State(
      Map<Integer, GemSlot> gems,
      Map<Integer, Item> refines,
      Map<Integer, Item> advanced,
      Item quality,
      Map<String, Engraving> engravings,
      Item armGuard,
      Stone stone) {
    /** 완갑(팔 보호구)·어빌리티 스톤은 넣지 않는 상태(목표 경로). */
    static State of(CharacterLoadout loadout) {
      return of(loadout, false, false);
    }

    /**
     * armGuard면 완갑 재련 단계도 다음 단계 후보로 둔다(+0~24, 목록 완갑 줄과 같은 대상). stone이면 어빌리티 스톤 목표 조합도 둔다(각인 줄이 둘인
     * 스톤만).
     */
    static State of(CharacterLoadout loadout, boolean armGuard, boolean stone) {
      var gems = new LinkedHashMap<Integer, GemSlot>();
      if (loadout.gems() != null) {
        var skills = new HashMap<Integer, String>();
        var effects = SpecUpService.gemEffects(loadout);
        if (loadout.gems().effects() != null)
          for (var skill : loadout.gems().effects().skills())
            if (skill != null && skill.gemSlot() != null && skill.name() != null)
              skills.putIfAbsent(skill.gemSlot(), skill.name());
        for (var gem : loadout.gems().items()) {
          if (gem == null || gem.slot() == null || gem.level() == null || gem.level() >= 10)
            continue;
          var matcher = gem.name() == null ? null : SpecUpService.GEM.matcher(gem.name());
          String kind = matcher != null && matcher.find() ? matcher.group(2) : null;
          gems.put(
              gem.slot(),
              new GemSlot(
                  gem.slot(),
                  kind,
                  SpecUpService.priceKind(kind, effects.get(gem.slot())),
                  gem.level(),
                  gem.level(),
                  SpecUpService.bound(gem),
                  skills.get(gem.slot()),
                  gem.icon(),
                  gem.grade()));
        }
      }
      var refines = new LinkedHashMap<Integer, Item>();
      var advanced = new LinkedHashMap<Integer, Item>();
      Item quality = null;
      for (int index = 0; index < loadout.equipment().size(); index++) {
        var item = loadout.equipment().get(index);
        if (item == null || item.detail() == null) continue;
        if (SpecUpService.WEAPON_ARMOR.contains(item.type())) {
          var refine = item.detail().refineLevel();
          if (refine != null && refine >= 10 && refine < 25)
            refines.put(index, new Item(index, item, refine));
          var level = item.detail().advancedRefineLevel();
          // 전율 장비에는 상급 재련이 없다(E21).
          if (level != null
              && level < 40
              && SpecUpService.refineGrade(item.name()) != RefiningRules.Grade.JEONYUL)
            advanced.put(index, new Item(index, item, level));
        }
        if (quality == null
            && "무기".equals(item.type())
            && item.detail().quality() != null
            && item.detail().quality() < 100)
          quality = new Item(index, item, item.detail().quality());
      }
      var engravings = new LinkedHashMap<String, Engraving>();
      if (loadout.engravings() != null)
        for (var engraving : loadout.engravings().arkPassiveEffects())
          if (engraving != null
              && engraving.name() != null
              && engraving.level() != null
              && engraving.level() < SpecUpService.engravingMaxLevel(engraving.grade()))
            engravings.put(
                engraving.name(),
                new Engraving(engraving.name(), engraving.grade(), engraving.level()));
      Item guard = null;
      if (armGuard) {
        var item = SpecUpKrakenScore.armGuardItem(loadout);
        if (item != null && item.detail() != null && item.detail().refineLevel() != null) {
          int refine = item.detail().refineLevel();
          if (refine >= 0 && refine < 25)
            guard = new Item(loadout.equipment().indexOf(item), item, refine);
        }
      }
      Stone stoneState = null;
      if (stone) {
        var item =
            loadout.equipment().stream()
                .filter(
                    equipment ->
                        equipment != null
                            && "어빌리티 스톤".equals(equipment.type())
                            && equipment.detail() != null)
                .findFirst()
                .orElse(null);
        if (item != null) {
          var levels = new LinkedHashMap<String, Integer>();
          // 실제 스톤에는 감소 줄(예: 방어력 감소 Lv.0)이 셋째로 붙는다. 각인 줄만 센다(계산 검증 11-01 P1: 감소 줄 때문에 스톤 단계가 하나도 나오지
          // 않음).
          for (var line : item.detail().stoneEngravings())
            if (line != null
                && line.name() != null
                && line.level() != null
                && !STONE_PENALTIES.contains(line.name()))
              levels.putIfAbsent(line.name(), line.level());
          if (levels.size() == 2) stoneState = new Stone(item, levels);
        }
      }
      return new State(gems, refines, advanced, quality, engravings, guard, stoneState);
    }

    /** option 단계를 적용한 다음 상태. 끝 단계에 닿은 칸은 뺀다. */
    State after(Option option) {
      var gems = new LinkedHashMap<>(this.gems);
      var refines = new LinkedHashMap<>(this.refines);
      var advanced = new LinkedHashMap<>(this.advanced);
      var quality = this.quality;
      var engravings = new LinkedHashMap<>(this.engravings);
      var armGuard = this.armGuard;
      var stone = this.stone;
      var change = option.change();
      switch (change.kind()) {
        case GEM_LEVEL -> {
          var gem = gems.get(change.slot());
          if (change.level() >= 10) gems.remove(change.slot());
          else
            gems.put(
                change.slot(),
                new GemSlot(
                    gem.slot(),
                    gem.kind(),
                    gem.priceKind(),
                    gem.original(),
                    change.level(),
                    gem.bound(),
                    gem.skill(),
                    gem.icon(),
                    gem.grade()));
        }
        case EQUIPMENT_REFINE -> {
          var item = refines.get(change.index());
          if (change.refine() >= 25) refines.remove(change.index());
          else
            refines.put(change.index(), new Item(item.index(), item.equipment(), change.refine()));
        }
        case EQUIPMENT_ADVANCED_REFINE -> {
          var item = advanced.get(change.index());
          if (change.level() >= 40) advanced.remove(change.index());
          else
            advanced.put(change.index(), new Item(item.index(), item.equipment(), change.level()));
        }
        case EQUIPMENT_QUALITY ->
            quality =
                change.quality() >= 100
                    ? null
                    : new Item(quality.index(), quality.equipment(), change.quality());
        case ENGRAVING_LEVEL -> {
          var engraving = engravings.get(change.name());
          if (change.level() >= SpecUpService.engravingMaxLevel(engraving.grade()))
            engravings.remove(change.name());
          else
            engravings.put(
                change.name(), new Engraving(engraving.name(), engraving.grade(), change.level()));
        }
        case ARM_GUARD ->
            armGuard =
                change.level() >= 25
                    ? null
                    : new Item(armGuard.index(), armGuard.equipment(), change.level());
        case STONE_ENGRAVING -> {
          var levels = new LinkedHashMap<>(stone.levels());
          for (var each : option.changes()) levels.put(each.name(), each.level());
          stone = new Stone(stone.item(), levels);
        }
        default -> {}
      }
      return new State(gems, refines, advanced, quality, engravings, armGuard, stone);
    }
  }
}
