package com.loakraken.refining.application;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 테스트 전용: OPTIMAL 풀이 자리 하나를 다른 스레드에서 잡고 있다가 close()에서 놓는다. 시간 겹침에 기대지 않고 "자리가 다 찬 상태"를 만든다(PM 총괄
 * 10-25).
 */
public final class SolveSlotHold implements AutoCloseable {
  private final CountDownLatch held = new CountDownLatch(1);
  private final CountDownLatch release = new CountDownLatch(1);
  private final Thread thread;

  private SolveSlotHold(RefiningPlanService service) {
    thread =
        new Thread(
            () ->
                service.bounded(
                    () -> {
                      held.countDown();
                      try {
                        release.await(30, TimeUnit.SECONDS);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }),
            "solve-slot-hold");
  }

  /** 자리를 잡을 때까지 기다린다. 5초 안에 못 잡으면(자리가 이미 다 찬 경우) 실패. */
  public static SolveSlotHold hold(RefiningPlanService service) throws InterruptedException {
    var hold = new SolveSlotHold(service);
    hold.thread.start();
    if (!hold.held.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("풀이 자리를 잡지 못했습니다");
    return hold;
  }

  @Override
  public void close() throws InterruptedException {
    release.countDown();
    thread.join(5_000);
  }
}
