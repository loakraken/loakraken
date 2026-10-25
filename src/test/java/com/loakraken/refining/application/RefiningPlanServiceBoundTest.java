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
import org.junit.jupiter.api.Test;

/**
 * OPTIMAL 단계 풀이 동시 수 제한: 자리가 다 차 있으면 기다리지 않고 바로 429 REFINING_SOLVE_BUSY, 자리가 나면 다시 받는다(PM 총괄·PM-프론트
 * 10-25).
 */
class RefiningPlanServiceBoundTest {
  @Test
  void 풀이_자리가_다_차면_429이고_끝나면_다시_받는다() throws Exception {
    var registry = new SimpleMeterRegistry();
    var service =
        new RefiningPlanService(
            (RefiningRules) null, (GrowthCosts) null, OptimalStageCache.NONE, registry, 1);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var holder =
        new Thread(
            () ->
                service.bounded(
                    () -> {
                      started.countDown();
                      try {
                        release.await(10, TimeUnit.SECONDS);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    holder.start();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    // 자리가 찬 동안 지금 풀이 수 1 / 최대 1
    assertThat(registry.get("loakraken.refining.optimal.solving").gauge().value()).isEqualTo(1);
    assertThat(registry.get("loakraken.refining.optimal.solving.max").gauge().value()).isEqualTo(1);
    long begin = System.nanoTime();
    assertThatThrownBy(() -> service.bounded(() -> null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).errorCode())
                    .isEqualTo(RefiningErrorCode.REFINING_SOLVE_BUSY));
    // 기다리지 않는다(서버 스레드를 붙잡지 않음).
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - begin))
        .isLessThan(java.time.Duration.ofMillis(200));
    assertThat(registry.counter("loakraken.refining.optimal.rejected").count()).isEqualTo(1);
    release.countDown();
    holder.join(5_000);
    assertThat(service.bounded(() -> null)).isNull();
    // 풀이 시간은 끝난 풀이마다(거절은 세지 않음): 잡고 있던 것 + 마지막 것 = 2
    assertThat(registry.get("loakraken.refining.optimal.solve").timer().count()).isEqualTo(2);
    assertThat(registry.get("loakraken.refining.optimal.solving").gauge().value()).isZero();
  }
}
