package com.loakraken.refining.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.OptimalStageCache;
import com.loakraken.refining.domain.RefiningRules;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 사용자 가격 특재 가치 풀이 자리·캐시(#1099 리뷰 P2): 크기 64 LRU, 자리 1개(차면 기다리지 않고 429), 공유 OPTIMAL 자리와 따로. */
class UserPriceSolvePoolTest {
  private static final String PREFIX = "loakraken.refining.special_stone.user_price";

  @Test
  void 캐시는_64개까지이고_가장_오래_안_쓴_것부터_밀어낸다() {
    var registry = new SimpleMeterRegistry();
    var pool = new UserPriceSolvePool(registry, 1);
    var computed = new AtomicInteger();
    for (int i = 0; i < 70; i++)
      pool.get(
          "k" + i,
          () -> {
            computed.incrementAndGet();
            return null;
          });
    assertThat(pool.size()).isEqualTo(UserPriceSolvePool.MAX_ENTRIES).isEqualTo(64);
    assertThat(registry.get(PREFIX + ".cache.size").gauge().value()).isEqualTo(64);
    // 마지막 것은 남아 있고(다시 풀지 않음) 맨 처음 것은 밀려났다(다시 푼다).
    pool.get("k69", () -> fail());
    pool.get(
        "k0",
        () -> {
          computed.incrementAndGet();
          return null;
        });
    assertThat(computed).hasValue(71);
    assertThat(registry.counter(PREFIX + ".cache", "result", "hit").count()).isEqualTo(1);
    assertThat(registry.counter(PREFIX + ".cache", "result", "miss").count()).isEqualTo(71);
  }

  @Test
  void 자리가_차면_기다리지_않고_429이고_캐시에_있는_것은_그대로_답한다() throws Exception {
    var registry = new SimpleMeterRegistry();
    var pool = new UserPriceSolvePool(registry, 1);
    pool.get("cached", () -> null);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder = new Thread(() -> pool.get("slow", () -> hold(started, release)));
    holder.start();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(registry.get(PREFIX + ".solving").gauge().value()).isEqualTo(1);
    long begin = System.nanoTime();
    assertThatThrownBy(() -> pool.get("other", () -> null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).errorCode())
                    .isEqualTo(RefiningErrorCode.REFINING_SOLVE_BUSY));
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - begin))
        .isLessThan(java.time.Duration.ofMillis(200));
    assertThat(registry.counter(PREFIX + ".rejected").count()).isEqualTo(1);
    // 캐시에 있는 것은 자리 없이 답한다.
    assertThat(pool.get("cached", () -> fail())).isEmpty();
    release.countDown();
    holder.join(5_000);
    assertThat(pool.get("other", () -> null)).isEmpty();
    assertThat(registry.get(PREFIX + ".solve").timer().count()).isEqualTo(3);
    assertThat(registry.get(PREFIX + ".solving").gauge().value()).isZero();
  }

  @Test
  void 공유_OPTIMAL_자리가_다_차도_사용자_가격_풀이는_돈다() throws Exception {
    var registry = new SimpleMeterRegistry();
    var shared =
        new RefiningPlanService(
            (RefiningRules) null, (GrowthCosts) null, OptimalStageCache.NONE, registry, 1);
    var pool = new UserPriceSolvePool(registry, 1);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder = new Thread(() -> shared.bounded(() -> hold(started, release)));
    holder.start();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> shared.bounded(() -> null)).isInstanceOf(BusinessException.class);
    assertThat(pool.get("user", () -> null)).isEmpty();
    assertThat(registry.get(PREFIX + ".solve").timer().count()).isEqualTo(1);
    release.countDown();
    holder.join(5_000);
  }

  private static com.loakraken.refining.domain.RefiningOptimal.Result hold(
      CountDownLatch started, CountDownLatch release) {
    started.countDown();
    try {
      release.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    return null;
  }

  private static com.loakraken.refining.domain.RefiningOptimal.Result fail() {
    throw new AssertionError("캐시에 있어야 한다");
  }
}
