package com.loakraken.refining.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.OptimalStageCache;
import com.loakraken.refining.domain.RefiningOptimal;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

  /**
   * OPTIMAL 단계 풀이(캐시에 없을 때만, 단계 하나 수~수십 ms)를 동시에 몇 개까지 할지. 웹이 털려 OPTIMAL을 마구 보내도 api CPU를 이 수만큼만
   * 쓴다(PM 총괄 10-25).
   */
  private final Semaphore solves;

  private final Counter rejected;

  @Autowired
  public RefiningPlanService(
      RefiningRules rules,
      GrowthCosts growthCosts,
      OptimalStageCache cache,
      MeterRegistry registry,
      @Value("${refining.optimal.max-concurrent-solves:2}") int maxConcurrentSolves) {
    this.rules = rules;
    this.growthCosts = growthCosts;
    this.cache = cache;
    this.solves = new Semaphore(Math.max(1, maxConcurrentSolves));
    this.rejected = registry.counter("loakraken.refining.optimal.rejected");
  }

  public RefiningPlanService(RefiningRules rules, GrowthCosts growthCosts) {
    this(
        rules,
        growthCosts,
        OptimalStageCache.NONE,
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
        2);
  }

  public GrowthCosts growthCosts() {
    return growthCosts;
  }

  public RefiningRules rules() {
    return rules;
  }

  /** 회차별 최적 계획. 필요한 시세가 하나라도 없으면 null. */
  public RefiningPlan.Optimal optimal(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlan.Options options,
      java.util.Map<String, ? extends Number> prices) {
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
    try {
      return compute.get();
    } finally {
      solves.release();
    }
  }

  public RefiningPlan plan(
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      RefiningPlan.Options options) {
    return RefiningPlan.calculate(rules.track(grade, equipmentType), grade, options, growthCosts);
  }
}
