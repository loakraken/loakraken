package com.loakraken.common.lostark;

import java.util.ArrayDeque;

/**
 * 이 인스턴스 안에서 센 최근 60초 공식 호출 기록. 공유 저장소({@link GateStore})와 별도로 같은 예산을 한 번 더 지키는 두 번째 상한이다 — 저장소의 창이
 * 지워지거나 비어도 이 인스턴스는 분당 예산을 넘겨 부르지 않는다. 기록은 예산 수만큼만 남아 메모리가 늘지 않는다.
 */
final class LocalBudgetWindow {
  private static final long WINDOW_MILLIS = 60_000;
  private final ArrayDeque<Long> admitted = new ArrayDeque<>();

  /** 최근 60초 기록이 limit 미만이면 지금을 기록하고 true. limit이 0 이하면 false. */
  synchronized boolean tryAdmit(long nowMillis, int limit) {
    prune(nowMillis);
    if (admitted.size() >= limit) return false;
    admitted.addLast(nowMillis);
    return true;
  }

  /** 기록 없이 같은 조건만 본다. */
  synchronized boolean available(long nowMillis, int limit) {
    prune(nowMillis);
    return admitted.size() < limit;
  }

  /** 방금 {@link #tryAdmit}로 남긴 기록 하나를 지운다(공유 저장소가 거절했을 때). */
  synchronized void undo() {
    admitted.pollLast();
  }

  private void prune(long nowMillis) {
    while (!admitted.isEmpty() && admitted.peekFirst() <= nowMillis - WINDOW_MILLIS)
      admitted.pollFirst();
  }
}
