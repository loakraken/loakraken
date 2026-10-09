package com.loakraken.common.lostark;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkMetrics.GateEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 공식 API 호출 게이트. 분당 예산(60초 창)과 공식 한도 대기는 {@link GateStore}가 지킨다. 기본은 Valkey라 같은 키를 쓰는 모든 인스턴스 합산
 * 90회/분이다(#107). 동시 요청 수는 인스턴스 안에서만 센다.
 *
 * <p>백그라운드 작업(수집기·예열)은 {@link #withReserve}로 감싼다. 그 안의 acquire()는 사용자 몫(reserve)을 남기는지 판정과 기록을 한 번에
 * 한다. 저장소 장애면 사용자 요청은 거절하고(LOST_ARK_GATE_UNAVAILABLE), 백그라운드는 실행하지 않는다.
 */
@Component
public class LostArkRequestGate {
  private static final ThreadLocal<Integer> RESERVE = new ThreadLocal<>();
  private final Clock clock;
  private final int budget;
  private final Semaphore permits;
  private final LostArkMetrics metrics;
  private final GateStore store;

  /** 단일 인스턴스 메모리 게이트(단위 테스트). */
  public LostArkRequestGate(Clock clock, LostArkProperties properties, LostArkMetrics metrics) {
    this(clock, properties, metrics, new MemoryGateStore(clock));
  }

  @Autowired
  public LostArkRequestGate(
      Clock clock, LostArkProperties properties, LostArkMetrics metrics, GateStore store) {
    this.clock = clock;
    budget = properties.requestsPerMinute();
    permits = new Semaphore(properties.maxConcurrentRequests());
    this.metrics = metrics;
    this.store = store;
  }

  public void acquire() {
    if (!permits.tryAcquire()) reject(GateEvent.CONCURRENCY_REJECT);
    GateStore.Admission admission;
    try {
      admission = store.admit(budget, currentReserve());
    } catch (GateStore.GateUnavailableException exception) {
      permits.release();
      metrics.gate(GateEvent.GATE_UNAVAILABLE);
      throw new BusinessException(LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE);
    }
    if (admission == GateStore.Admission.ADMITTED) return;
    permits.release();
    reject(
        admission == GateStore.Admission.BACKOFF
            ? GateEvent.UPSTREAM_BACKOFF_REJECT
            : GateEvent.BUDGET_REJECT);
  }

  /**
   * 백그라운드 작업이 지금 호출해도 되는지 미리 본다. 사용자 몫을 남기는지 확인만 하고 기록하지 않는다. 실제 판정은 {@link #withReserve} 안의
   * acquire()가 원자적으로 다시 한다. 저장소 장애면 false.
   */
  public boolean backgroundAvailable(int reserve) {
    try {
      return store.available(budget, Math.max(0, reserve));
    } catch (GateStore.GateUnavailableException exception) {
      metrics.gate(GateEvent.GATE_UNAVAILABLE);
      return false;
    }
  }

  /** 작업 안의 공식 호출이 사용자 몫(reserve)을 남기도록 한다. 판정에 실패하면 acquire()가 LOST_ARK_RATE_LIMITED를 던진다. */
  public <T> T withReserve(int reserve, Supplier<T> work) {
    Integer previous = RESERVE.get();
    RESERVE.set(Math.max(0, reserve));
    try {
      return work.get();
    } finally {
      if (previous == null) RESERVE.remove();
      else RESERVE.set(previous);
    }
  }

  public void release() {
    permits.release();
  }

  public void observe(HttpHeaders headers, boolean rateLimited) {
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
    try {
      store.blockUntil(next);
    } catch (GateStore.GateUnavailableException exception) {
      metrics.gate(GateEvent.GATE_UNAVAILABLE);
    }
  }

  private static int currentReserve() {
    var reserve = RESERVE.get();
    return reserve == null ? 0 : reserve;
  }

  private void reject(GateEvent reason) {
    metrics.gate(reason);
    throw new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
  }
}
