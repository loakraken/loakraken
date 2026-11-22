package com.loakraken.refining.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.refining.domain.OptimalStageCache;
import com.loakraken.refining.domain.RefiningOptimal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사용자 가격이 있는 특수 재련 돌파석 가치 요청(M42)의 회차별 최적 풀이 자리·캐시(#1099 리뷰 P2). 사용자 가격은 캐시 키에 들어가 가격 조합마다 새로 푼다.
 * 그래서 /refining/plans OPTIMAL의 공유 자리({@code refining.optimal.max-concurrent-solves})와 공유 캐시(시세 기준
 * 단계)를 쓰지 않고, 따로 둔 자리 {@code refining.special-stone.user-price-solves}(기본 1)와 크기 {@link
 * #MAX_ENTRIES}의 LRU만 쓴다. 가격을 바꿔 가며 보내도 공유 쪽 사용자는 기다리거나 다시 계산하지 않는다. 자리가 차면 기다리지 않고 429
 * REFINING_SOLVE_BUSY(공유 쪽과 같은 오류, Retry-After).
 *
 * <p>지표(운영에서 물을 것: 사용자 가격 요청이 얼마나 자주 거절되나, 캐시가 듣나, 풀이가 얼마나 걸리나): {@code
 * loakraken.refining.special_stone.user_price.rejected}, {@code
 * ...user_price.cache{result=hit|miss}}, {@code ...user_price.cache.size}, {@code
 * ...user_price.solve}(p50·p95·p99), {@code ...user_price.solving}. 라벨 값은 고정.
 */
@Component
public class UserPriceSolvePool {
  static final int MAX_ENTRIES = 64;

  private final Map<String, Optional<RefiningOptimal.Result>> entries =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(
            Map.Entry<String, Optional<RefiningOptimal.Result>> eldest) {
          return size() > MAX_ENTRIES;
        }
      };
  private final Semaphore seats;
  private final int maxSeats;
  private final Counter hits;
  private final Counter misses;
  private final Counter rejected;
  private final Timer solveTime;

  public UserPriceSolvePool(
      MeterRegistry registry,
      @Value("${refining.special-stone.user-price-solves:1}") int maxSeats) {
    this.maxSeats = Math.max(1, maxSeats);
    this.seats = new Semaphore(this.maxSeats);
    String name = "loakraken.refining.special_stone.user_price";
    hits = registry.counter(name + ".cache", "result", "hit");
    misses = registry.counter(name + ".cache", "result", "miss");
    rejected =
        Counter.builder(name + ".rejected")
            .description("사용자 가격 특재 가치 풀이 자리가 없어 429 REFINING_SOLVE_BUSY로 돌려준 수")
            .register(registry);
    solveTime =
        Timer.builder(name + ".solve")
            .description("사용자 가격 특재 가치의 캐시에 없는 단계 풀이 하나에 걸린 시간")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    Gauge.builder(name + ".cache.size", this, UserPriceSolvePool::size)
        .description("사용자 가격 특재 가치 풀이 캐시에 든 결과 수")
        .register(registry);
    Gauge.builder(name + ".solving", this, UserPriceSolvePool::solving)
        .description("지금 진행 중인 사용자 가격 특재 가치 풀이 수")
        .register(registry);
  }

  /**
   * {@link RefiningPlanService#optimalWith}에 넘길 풀이기(이 캐시 + 이 자리). 이 클래스는 {@link OptimalStageCache}
   * 빈이 아니다(공유 캐시 빈이 하나뿐이어야 RefiningPlanService가 그것을 받는다).
   */
  public OptimalStageCache solver() {
    return this::get;
  }

  Optional<RefiningOptimal.Result> get(String key, Supplier<RefiningOptimal.Result> compute) {
    synchronized (entries) {
      var cached = entries.get(key);
      if (cached != null) {
        hits.increment();
        return cached;
      }
    }
    // 캐시에 없는 풀이만 자리를 받는다. 계산은 잠금 밖에서(같은 키를 동시에 풀면 마지막 결과를 둔다, 값은 같다).
    misses.increment();
    if (!seats.tryAcquire()) {
      rejected.increment();
      throw new BusinessException(
          RefiningErrorCode.REFINING_SOLVE_BUSY, RefiningPlanService.RETRY_AFTER);
    }
    Optional<RefiningOptimal.Result> computed;
    var sample = Timer.start();
    try {
      computed = Optional.ofNullable(compute.get());
    } finally {
      sample.stop(solveTime);
      seats.release();
    }
    synchronized (entries) {
      entries.put(key, computed);
    }
    return computed;
  }

  int size() {
    synchronized (entries) {
      return entries.size();
    }
  }

  int solving() {
    return maxSeats - seats.availablePermits();
  }
}
