package com.loakraken.common.lostark;

import com.loakraken.common.api.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;

/**
 * 사용자 공식 API 키(BYOK) 호출의 게이트. 서버 키 예산({@link LostArkRequestGate})과 따로 센다.
 *
 * <ul>
 *   <li>키마다 분당 상한(기본 90)과 동시 호출 상한(기본 2). 저장소 키는 키의 HMAC id뿐이다.
 *   <li>방문자의 분당 공식 호출 칸(OFFICIAL)은 서버 키 호출과 똑같이 센다(남용 방지).
 *   <li>저장소 장애면 부르지 않는다(LOST_ARK_GATE_UNAVAILABLE). 어떤 거절에도 서버 키로 대신 부르지 않는다.
 * </ul>
 */
public class UserKeyGate {
  static final String PREFIX = "lostark:byok:";
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserKeyGate.class);
  private static final Duration WINDOW = Duration.ofMinutes(1);

  /** 공식 Retry-After를 그대로 넘기되 이보다 길게는 주지 않는다. */
  static final long MAX_RETRY_AFTER_SECONDS = 120;

  private final ByokProperties properties;
  private final ClientLimiter clients;
  private final ClientLimitStore store;
  private final LostArkMetrics metrics;
  private final Clock clock;
  private final ConcurrentHashMap<String, AtomicInteger> running = new ConcurrentHashMap<>();

  UserKeyGate(
      ByokProperties properties,
      ClientLimiter clients,
      ClientLimitStore store,
      LostArkMetrics metrics,
      Clock clock) {
    this.properties = properties;
    this.clients = clients;
    this.store = store;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** 지금 요청의 사용자 키로 공식 호출 하나를 허가한다. 허가되면 끝난 뒤 {@link #release()}를 부른다. */
  public void acquire() {
    var id = UserKeyContext.id();
    if (id == null) throw new IllegalStateException("사용자 키 없이 사용자 키 게이트를 불렀습니다.");
    clients.chargeOfficial();
    if (!enter(id)) throw rateLimited(1);
    long now = clock.millis();
    long window = now / WINDOW.toMillis();
    long count;
    try {
      count = store.increment(PREFIX + id + ":" + window, WINDOW.plusSeconds(1));
    } catch (ClientLimitStore.Unavailable exception) {
      exit(id);
      metrics.gate(LostArkMetrics.GateEvent.GATE_UNAVAILABLE);
      throw new BusinessException(LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE);
    }
    if (count > properties.perKeyPerMinute()) {
      exit(id);
      // 키마다 창마다 처음 한 번만 남긴다. HMAC 앞 8자리만(운영이 남용 키 하나를 막을 수 있게, 원문 없음).
      if (count == properties.perKeyPerMinute() + 1L)
        log.info("event=user_key_limit key={}", id.substring(0, Math.min(8, id.length())));
      long remaining = (window + 1) * WINDOW.toMillis() - now;
      throw rateLimited((remaining + 999) / 1000);
    }
    metrics.call(LostArkMetrics.CallOrigin.USER_KEY);
  }

  public void release() {
    var id = UserKeyContext.id();
    if (id != null) exit(id);
  }

  /**
   * 사용자 키 호출의 공식 응답 상태를 우리 오류로 바꾼다. 401·403은 USER_KEY_INVALID, 429는 USER_KEY_RATE_LIMITED(공식
   * Retry-After, 최대 120초). 그 밖이면 null(부르는 쪽 기존 처리). 서버 키의 공식 한도 대기에는 기록하지 않는다.
   */
  public BusinessException upstream(int status, HttpHeaders headers) {
    if (status == 401 || status == 403)
      return new BusinessException(LostArkErrorCode.USER_KEY_INVALID);
    if (status == 429) return rateLimited(retryAfter(headers));
    return null;
  }

  private boolean enter(String id) {
    var admitted = new boolean[1];
    running.compute(
        id,
        (key, count) -> {
          var current = count == null ? new AtomicInteger() : count;
          if (current.get() < properties.perKeyConcurrency()) {
            current.incrementAndGet();
            admitted[0] = true;
          }
          return current.get() == 0 ? null : current;
        });
    return admitted[0];
  }

  private void exit(String id) {
    running.computeIfPresent(id, (key, count) -> count.decrementAndGet() <= 0 ? null : count);
  }

  private BusinessException rateLimited(long seconds) {
    metrics.gate(LostArkMetrics.GateEvent.USER_KEY_REJECT);
    long bounded = Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, seconds));
    return new BusinessException(
        LostArkErrorCode.USER_KEY_RATE_LIMITED, Duration.ofSeconds(bounded));
  }

  /** 공식 Retry-After(초). 없거나 이상하면 60초. */
  static long retryAfter(HttpHeaders headers) {
    try {
      var value = headers.getFirst(HttpHeaders.RETRY_AFTER);
      if (value != null) return Long.parseLong(value.strip());
    } catch (NumberFormatException ignored) {
      // 잘못된 외부 헤더는 기본 대기 시간으로 처리한다.
    }
    return 60;
  }

  /** 테스트: 지금 이 키로 진행 중인 호출 수. */
  int running(String id) {
    var count = running.get(id);
    return count == null ? 0 : count.get();
  }
}
