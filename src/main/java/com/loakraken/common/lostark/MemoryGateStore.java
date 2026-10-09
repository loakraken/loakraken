package com.loakraken.common.lostark;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;

/** 단일 인스턴스용 메모리 게이트(lostark.gate.store=memory, 단위 테스트). 여러 인스턴스의 사용량을 모른다. */
public class MemoryGateStore implements GateStore {
  private final Clock clock;
  private final ArrayDeque<Instant> requests = new ArrayDeque<>();
  private Instant blockedUntil = Instant.EPOCH;

  public MemoryGateStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized Admission admit(int budget, int reserve) {
    var now = clock.instant();
    prune(now);
    if (now.isBefore(blockedUntil)) return Admission.BACKOFF;
    if (requests.size() >= budget - Math.max(0, reserve)) return Admission.BUDGET;
    requests.addLast(now);
    return Admission.ADMITTED;
  }

  @Override
  public synchronized boolean available(int budget, int reserve) {
    var now = clock.instant();
    prune(now);
    return !now.isBefore(blockedUntil) && requests.size() < budget - Math.max(0, reserve);
  }

  @Override
  public synchronized void blockUntil(Instant until) {
    if (until.isAfter(blockedUntil)) blockedUntil = until;
  }

  private void prune(Instant now) {
    while (!requests.isEmpty() && !requests.peekFirst().isAfter(now.minusSeconds(60)))
      requests.removeFirst();
  }
}
