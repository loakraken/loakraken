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

  /** 설정되면 이 스레드의 공식 호출을 OFFICIAL 대신 이 버킷에 센다({@link #withClientBucket}). */
  private static final ThreadLocal<ClientLimiter.Bucket> CHARGE_BUCKET = new ThreadLocal<>();

  /** 설정되면 이 스레드의 공식 호출을 모든 클라이언트가 함께 쓰는 이 기능 상한에도 센다. */
  private static final ThreadLocal<ClientLimiter.Bucket> SHARED_BUCKET = new ThreadLocal<>();

  private static final ThreadLocal<Boolean> CHARGE_CLIENT = new ThreadLocal<>();

  /** 공식 호출 로그 로거 이름. 로컬 프로필(application-local)에서만 DEBUG다. */
  public static final String CALL_LOGGER = "loakraken.official-calls";

  private static final org.slf4j.Logger CALLS = org.slf4j.LoggerFactory.getLogger(CALL_LOGGER);
  private final Clock clock;
  private final int budget;
  private final Semaphore permits;
  private final LostArkMetrics metrics;
  private final GateStore store;
  private final ClientLimiter clients;

  /** 단일 인스턴스 메모리 게이트(단위 테스트). 클라이언트 제한은 끈다. */
  public LostArkRequestGate(Clock clock, LostArkProperties properties, LostArkMetrics metrics) {
    this(clock, properties, metrics, new MemoryGateStore(clock));
  }

  public LostArkRequestGate(
      Clock clock, LostArkProperties properties, LostArkMetrics metrics, GateStore store) {
    this(clock, properties, metrics, store, ClientLimiter.DISABLED);
  }

  @Autowired
  public LostArkRequestGate(
      Clock clock,
      LostArkProperties properties,
      LostArkMetrics metrics,
      GateStore store,
      ClientLimiter clients) {
    this.clock = clock;
    budget = properties.requestsPerMinute();
    permits = new Semaphore(properties.maxConcurrentRequests());
    this.metrics = metrics;
    this.store = store;
    this.clients = clients;
  }

  /**
   * 공식 호출 하나를 허가한다. 사용자 요청이면 먼저 그 클라이언트의 분당 공식 호출 한도(#108)를 센다. 넘으면 CLIENT_RATE_LIMITED이고 공유 예산은 쓰지
   * 않는다. {@link #withReserve} 안의 호출(수집기·예열 등 백그라운드)은 사용자 몫을 남기는 쪽으로 이미 제한되므로 클라이언트 한도에 매기지 않는다. 사용자
   * 요청 중의 reserve 호출(경매 칸 채우기·N13 검색·L19 불러오기)은 {@link #withUserReserve}로 감싸 매긴다.
   */
  public void acquire() {
    acquire(null);
  }

  /**
   * {@link #acquire()}와 같고, 허가되면 공식 호출 로그(로거 {@value #CALL_LOGGER}, DEBUG)에 한 줄 남긴다. 로컬 프로필만 DEBUG로
   * 켜고 운영은 꺼져 있다. 줄에는 호출 종류와 경로(백그라운드·사용자)만 있고 캐릭터 이름·주소·키는 없다.
   */
  public void acquire(LostArkMetrics.Endpoint endpoint) {
    acquire(endpoint, null);
  }

  /**
   * {@link #acquire(LostArkMetrics.Endpoint)}에 호출 대상(거래소 분류·품목 이름처럼 게임 데이터만, 캐릭터 이름은 넣지 않음)을 덧붙인다.
   * 시세 화면 한 번 열기의 호출 수를 품목·분류별로 나누려는 것이다.
   */
  public void acquire(LostArkMetrics.Endpoint endpoint, String target) {
    admit();
    if (CALLS.isDebugEnabled())
      CALLS.debug(
          "official-call endpoint={} origin={} reserve={}{}",
          endpoint == null ? "UNKNOWN" : endpoint.name(),
          origin(),
          currentReserve(),
          target == null ? "" : " " + target);
  }

  /** BACKGROUND: 예열·수집기(reserve, 클라이언트 한도 안 셈), USER_RESERVE: 사용자 요청 중 reserve 호출, USER: 사용자 요청. */
  private static String origin() {
    if (currentReserve() == 0) return "USER";
    return Boolean.TRUE.equals(CHARGE_CLIENT.get()) || CHARGE_BUCKET.get() != null
        ? "USER_RESERVE"
        : "BACKGROUND";
  }

  private void admit() {
    // 기능 버킷(재료 검색 등)은 공유 예산보다 먼저 세지만(한도에 걸린 호출이 예산을 쓰지 않게), 게이트가 공식 호출 전에 거절하면 센 것을 되돌린다
    // (코드 리뷰 10-18: 게이트가 바쁠 때 공식 호출 없이 합계 상한이 바닥나지 않게).
    String bucketKey = null;
    String sharedKey = null;
    var bucket = CHARGE_BUCKET.get();
    if (bucket != null) {
      bucketKey = clients.chargeRefundable(bucket);
      var shared = SHARED_BUCKET.get();
      if (shared != null) {
        try {
          sharedKey = clients.chargeShared(shared);
        } catch (RuntimeException exception) {
          clients.refund(bucketKey);
          throw exception;
        }
      }
    } else if (currentReserve() == 0 || Boolean.TRUE.equals(CHARGE_CLIENT.get()))
      clients.chargeOfficial();
    try {
      enter();
    } catch (RuntimeException exception) {
      clients.refund(sharedKey);
      clients.refund(bucketKey);
      throw exception;
    }
  }

  /** 동시 호출 허가와 공유 예산 판정. 거절이면 예외(허가를 잡았으면 돌려줌). */
  private void enter() {
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

  /**
   * 작업 안의 공식 호출이 사용자 몫(reserve)을 남기도록 한다. 판정에 실패하면 acquire()가 LOST_ARK_RATE_LIMITED를 던진다.
   *
   * <p><b>같은 스레드 전용</b>이다. reserve는 ThreadLocal이라, work 안에서 다른 스레드(비동기·병렬)로 공식 호출을 넘기면 그 호출은
   * reserve 0(사용자 경로)으로 판정된다. 공식 호출을 비동기로 바꿀 때는 그 스레드에서 다시 withReserve로 감싼다(#130 P2).
   */
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

  /**
   * 사용자 요청 하나가 공식 호출을 여러 번 일으키는 경로(원정대 전투력 불러오기 L19 등). {@link #withReserve}처럼 공유 예산에 사용자 몫
   * reserve를 남기면서, 그 요청 클라이언트의 분당 공식 호출 한도(OFFICIAL)에도 호출마다 매긴다(보안 감사 10-12: reserve 안 호출이 클라이언트
   * 한도를 벗어나던 문제). 같은 스레드 전용이다.
   */
  public <T> T withUserReserve(int reserve, Supplier<T> work) {
    Boolean previous = CHARGE_CLIENT.get();
    CHARGE_CLIENT.set(Boolean.TRUE);
    try {
      return withReserve(reserve, work);
    } finally {
      if (previous == null) CHARGE_CLIENT.remove();
      else CHARGE_CLIENT.set(previous);
    }
  }

  /**
   * 사용자 요청 하나가 공식 호출을 여러 번 일으키는데 그 호출들을 OFFICIAL이 아니라 기능 전용 버킷에 셀 때(재료 시세 검색, 보안 점검 10-18). 공유 예산에는
   * reserve를 남기고, 공식 호출마다 요청 클라이언트의 bucket(넘으면 CLIENT_RATE_LIMITED)과 모든 클라이언트가 함께 쓰는 shared 상한(넘으면
   * LOST_ARK_RATE_LIMITED, null이면 세지 않음)에 매긴다. 같은 스레드 전용이다.
   */
  public <T> T withClientBucket(
      int reserve, ClientLimiter.Bucket bucket, ClientLimiter.Bucket shared, Supplier<T> work) {
    var previous = CHARGE_BUCKET.get();
    var previousShared = SHARED_BUCKET.get();
    CHARGE_BUCKET.set(bucket);
    if (shared == null) SHARED_BUCKET.remove();
    else SHARED_BUCKET.set(shared);
    try {
      return withReserve(reserve, work);
    } finally {
      if (previous == null) CHARGE_BUCKET.remove();
      else CHARGE_BUCKET.set(previous);
      if (previousShared == null) SHARED_BUCKET.remove();
      else SHARED_BUCKET.set(previousShared);
    }
  }

  /** 지금 요청 클라이언트를 버킷에 한 번 센다(N13 캐시 미스 검색 등). 넘으면 CLIENT_RATE_LIMITED + Retry-After. */
  public void chargeClient(ClientLimiter.Bucket bucket) {
    clients.charge(bucket);
  }

  /**
   * 지금 요청 클라이언트가 이번 분에 공식 호출을 몇 번 더 할 수 있는지(읽기만, #614). 세지 않는 경로·저장소 장애는 {@link Integer#MAX_VALUE}.
   * 일괄 불러오기가 방문자 칸을 다 쓰지 않도록 멈출 때 쓴다.
   */
  public int remainingClientOfficial() {
    return clients.remainingOfficial();
  }

  /** 일괄 불러오기(원정대 전투력·카드 수집)가 방문자 칸에 남겨 두는 공식 호출 수 — 그 분 안의 다른 조회가 거절되지 않게(#614). */
  public static final int BULK_KEEP_OFFICIAL = 6;

  /** 일괄 불러오기가 이번 대상을 더 불러도 되는지(남은 칸이 {@link #BULK_KEEP_OFFICIAL}보다 많은지). */
  public boolean bulkMayContinue() {
    return remainingClientOfficial() > BULK_KEEP_OFFICIAL;
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
    throw new LostArkRateLimitedException(
        switch (reason) {
          case UPSTREAM_BACKOFF_REJECT -> LostArkRateLimitedException.Cause.GATE_BACKOFF;
          case CONCURRENCY_REJECT -> LostArkRateLimitedException.Cause.GATE_CONCURRENCY;
          default -> LostArkRateLimitedException.Cause.GATE_BUDGET;
        });
  }
}
