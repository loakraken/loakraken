package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterMetrics;
import com.loakraken.character.application.CharacterMetrics.GateEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
class LostArkRequestGate {
  private final Clock clock;
  private final int budget;
  private final Semaphore permits;
  private final CharacterMetrics metrics;
  private final ArrayDeque<Instant> requests = new ArrayDeque<>();
  private Instant blockedUntil = Instant.EPOCH;

  LostArkRequestGate(Clock clock, LostArkProperties properties, CharacterMetrics metrics) {
    this.clock = clock;
    budget = properties.requestsPerMinute();
    permits = new Semaphore(properties.maxConcurrentRequests());
    this.metrics = metrics;
  }

  synchronized void acquire() {
    var now = clock.instant();
    while (!requests.isEmpty() && !requests.peekFirst().isAfter(now.minusSeconds(60)))
      requests.removeFirst();
    if (now.isBefore(blockedUntil)) reject(GateEvent.UPSTREAM_BACKOFF_REJECT);
    if (requests.size() >= budget) reject(GateEvent.BUDGET_REJECT);
    if (!permits.tryAcquire()) reject(GateEvent.CONCURRENCY_REJECT);
    requests.addLast(now);
  }

  void release() {
    permits.release();
  }

  private void reject(GateEvent reason) {
    metrics.gate(reason);
    throw new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED);
  }

  synchronized void observe(HttpHeaders headers, boolean rateLimited) {
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
