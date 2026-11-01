package com.loakraken.refining.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.OptimalStageCache;
import com.loakraken.refining.domain.RefiningOptimal;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.refining.domain.VambraceRules;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RefiningPlanService {
  /** 자리가 없으면 기다리지 않고 바로 429(기다리는 요청이 서버 스레드를 붙잡지 않게, PM-프론트 10-25). 잠시 뒤 다시. */
  static final Duration RETRY_AFTER = Duration.ofSeconds(2);

  private final RefiningRules rules;
  private final GrowthCosts growthCosts;
  private final OptimalStageCache cache;

  /** 팔 보호구(완갑) 단계표. 없으면(일부 테스트) ARM_GUARD 계획은 만들 수 없다. */
  private final VambraceRules vambrace;

  /**
   * OPTIMAL 단계 풀이(캐시에 없을 때만, 단계 하나 수~수십 ms)를 동시에 몇 개까지 할지. 웹이 털려 OPTIMAL을 마구 보내도 api CPU를 이 수만큼만
   * 쓴다(PM 총괄 10-25).
   */
  private final Semaphore solves;

  private final int maxSolves;

  /**
   * 지표(운영에서 물을 것: OPTIMAL이 얼마나 자주 자리가 없어 거절되나, 캐시에 없는 단계 풀이가 얼마나 걸리나, 자리가 얼마나 찼나). 거절은 {@code
   * loakraken.refining.optimal.rejected}, 풀이 시간은 {@code
   * loakraken.refining.optimal.solve}(p50·p95·p99), 지금 풀이 수는 {@code
   * loakraken.refining.optimal.solving}(최대는 {@code ...solving.max}). 라벨 없음. 거절마다 로그는 남기지 않는다(몰릴 때
   * 로그가 넘치지 않게, 수는 지표로).
   */
  private final Counter rejected;

  private final Timer solveTime;

  public RefiningPlanService(
      RefiningRules rules,
      GrowthCosts growthCosts,
      OptimalStageCache cache,
      MeterRegistry registry,
      int maxConcurrentSolves) {
    this(rules, growthCosts, null, cache, registry, maxConcurrentSolves);
  }

  @Autowired
  public RefiningPlanService(
      RefiningRules rules,
      GrowthCosts growthCosts,
      VambraceRules vambrace,
      OptimalStageCache cache,
      MeterRegistry registry,
      @Value("${refining.optimal.max-concurrent-solves:2}") int maxConcurrentSolves) {
    this.rules = rules;
    this.growthCosts = growthCosts;
    this.vambrace = vambrace;
    this.cache = cache;
    this.maxSolves = Math.max(1, maxConcurrentSolves);
    this.solves = new Semaphore(maxSolves);
    this.rejected =
        Counter.builder("loakraken.refining.optimal.rejected")
            .description("OPTIMAL 단계 풀이 자리가 없어 429 REFINING_SOLVE_BUSY로 돌려준 수")
            .register(registry);
    this.solveTime =
        Timer.builder("loakraken.refining.optimal.solve")
            .description("캐시에 없는 OPTIMAL 단계 풀이 하나에 걸린 시간")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    Gauge.builder("loakraken.refining.optimal.solving", this, RefiningPlanService::solving)
        .description("지금 진행 중인 OPTIMAL 단계 풀이 수")
        .register(registry);
    Gauge.builder("loakraken.refining.optimal.solving.max", this, service -> service.maxSolves)
        .description("동시에 할 수 있는 OPTIMAL 단계 풀이 수(refining.optimal.max-concurrent-solves)")
        .register(registry);
  }

  /** 지금 풀이 중인 자리 수. */
  int solving() {
    return maxSolves - solves.availablePermits();
  }

  public RefiningPlanService(RefiningRules rules, GrowthCosts growthCosts) {
    this(rules, growthCosts, null);
  }

  public RefiningPlanService(RefiningRules rules, GrowthCosts growthCosts, VambraceRules vambrace) {
    this(
        rules,
        growthCosts,
        vambrace,
        OptimalStageCache.NONE,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        2);
  }

  public VambraceRules vambrace() {
    return vambrace;
  }

  public GrowthCosts growthCosts() {
    return growthCosts;
  }

  public RefiningRules rules() {
    return rules;
  }

  /**
   * 첫 단계(targetStage)의 현재 성공 확률(숨결·책 전, 만분율)에서 이미 실패한 횟수. 그 단계 기본 확률 × (1 + 0.1k)와 같은 k가 없으면 400
   * REFINING_SUCCESS_RATE_MISMATCH. 장인의 기운(0.01%p)이 그 횟수만큼 실패해 쌓일 수 있는 범위({@link
   * RefiningPlan#energyRange}, 숨결·책을 쓴 이력은 모르니 매 시도 없음 ~ 매 시도 최대) 밖이면 400
   * REFINING_ARTISAN_ENERGY_MISMATCH.
   */
  public int failuresFromSuccessRate(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      int targetStage,
      int rateBp,
      int energyBp) {
    double base;
    double auxMax = 0;
    if (equipmentType == RefiningRules.EquipmentType.ARM_GUARD) {
      var stage = vambrace.stage(targetStage);
      base = stage.baseRateBp();
      for (var breath : stage.breaths()) auxMax += breath.rateBp() * breath.maxCount();
    } else {
      var stage = rules.track(grade, equipmentType).stage(targetStage);
      base = stage.baseRateBp();
      if (stage.breath() != null)
        auxMax += (double) stage.breath().rateBp() * stage.breath().maxCount();
      if (stage.book() != null) auxMax += stage.book().rateBp();
    }
    int failures = RefiningPlan.failuresFromRate(base, rateBp);
    if (failures < 0) throw new BusinessException(RefiningErrorCode.REFINING_SUCCESS_RATE_MISMATCH);
    var range = RefiningPlan.energyRange(base, auxMax, failures);
    if (energyBp < range[0] || energyBp > range[1])
      throw new BusinessException(RefiningErrorCode.REFINING_ARTISAN_ENERGY_MISMATCH);
    return failures;
  }

  /** 회차별 최적 계획. 필요한 시세가 하나라도 없으면 null. */
  public RefiningPlan.Optimal optimal(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlan.Options options,
      java.util.Map<String, ? extends Number> prices) {
    if (equipmentType == RefiningRules.EquipmentType.ARM_GUARD)
      return RefiningPlan.optimal(
          vambrace, options, prices, (key, compute) -> cache.get(key, () -> bounded(compute)));
    return RefiningPlan.optimal(
        rules.track(grade, equipmentType),
        grade,
        options,
        growthCosts,
        prices,
        (key, compute) -> cache.get(key, () -> bounded(compute)));
  }

  /** 캐시에 없는 단계 풀이만 자리를 받는다. 빈 자리가 없으면 기다리지 않고 429 REFINING_SOLVE_BUSY(Retry-After). */
  RefiningOptimal.Result bounded(Supplier<RefiningOptimal.Result> compute) {
    if (!solves.tryAcquire()) {
      rejected.increment();
      throw new BusinessException(RefiningErrorCode.REFINING_SOLVE_BUSY, RETRY_AFTER);
    }
    var sample = Timer.start();
    try {
      return compute.get();
    } finally {
      sample.stop(solveTime);
      solves.release();
    }
  }

  public RefiningPlan plan(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlan.Options options) {
    if (equipmentType == RefiningRules.EquipmentType.ARM_GUARD)
      return RefiningPlan.calculate(vambrace, options);
    return RefiningPlan.calculate(rules.track(grade, equipmentType), grade, options, growthCosts);
  }
}
