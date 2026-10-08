package com.loakraken.common.lostark;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkMetrics.GateEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class LostArkRequestGate {
  private final Clock clock;
  private final int budget;
  private final Semaphore permits;
  private final LostArkMetrics metrics;
  private final ArrayDeque<Instant> requests = new ArrayDeque<>();
  private Instant blockedUntil = Instant.EPOCH;

  public LostArkRequestGate(Clock clock, LostArkProperties properties, LostArkMetrics metrics) {
    this.clock = clock;
    budget = properties.requestsPerMinute();
    permits = new Semaphore(properties.maxConcurrentRequests());
    this.metrics = metrics;
  }

  public synchronized void acquire() {
    var now = clock.instant();
    while (!requests.isEmpty() && !requests.peekFirst().isAfter(now.minusSeconds(60)))
      requests.removeFirst();
    if (now.isBefore(blockedUntil)) reject(GateEvent.UPSTREAM_BACKOFF_REJECT);
    if (requests.size() >= budget) reject(GateEvent.BUDGET_REJECT);
    if (!permits.tryAcquire()) reject(GateEvent.CONCURRENCY_REJECT);
    requests.addLast(now);
  }

  /**
   * 백그라운드 작업(통계 수집 등)이 지금 호출해도 되는지. 최근 60초 사용량이 budget − reserve 미만이고 공식 한도 대기 중이 아닐 때만 true다. 사용자
   * 요청 몫(reserve)을 남겨 두기 위한 사전 확인이며, 실제 호출은 acquire()를 거친다.
   */
  public synchronized boolean backgroundAvailable(int reserve) {
    var now = clock.instant();
    while (!requests.isEmpty() && !requests.peekFirst().isAfter(now.minusSeconds(60)))
      requests.removeFirst();
    return !now.isBefore(blockedUntil) && requests.size() < budget - Math.max(0, reserve);
  }

  public void release() {
    permits.release();
  }

  private void reject(GateEvent reason) {
    metrics.gate(reason);
    throw new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
  }

  public synchronized void observe(HttpHeaders headers, boolean rateLimited) {
    if (rateLimited) metrics.gate(GateEvent.UPSTREAM_429);
    if (!rateLimited && !"0".equals(headers.getFirst("X-RateLimit-Remaining"))) return;
    var now = clock.instant();
    var next = now.plusSeconds(60);
    try {
      var reset = Instant.ofEpochSecond(Long.parseLong(headers.getFirst("X-RateLimit-Reset")));
      if (reset.isAfter(now) && reset.isBefore(now.plusSeconds(300))) next = reset;
    } catch (RuntimeException ignored) {
      // 잘못된 외부 헤더는 보수적인 기본 대기 시간으로 처리한다.
    }
    if (next.isAfter(blockedUntil)) blockedUntil = next;
  }
}
