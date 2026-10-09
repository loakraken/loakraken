package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ClientLimiterTest {
  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void 버킷마다_분당_한도를_넘으면_창이_끝날_때까지_거절한다() {
    var limiter = limiter(new ClientLimitProperties(true, 2, 2, 3, 3, true));
    String client = limiter.clientKey("203.0.113.7");
    for (int i = 0; i < 3; i++)
      assertThat(limiter.check(ClientLimiter.Bucket.GENERAL, client).allowed()).isTrue();
    var rejected = limiter.check(ClientLimiter.Bucket.GENERAL, client);
    assertThat(rejected.allowed()).isFalse();
    // 2026-10-04T00:00:20Z → 창 끝(00:01:00)까지 40초
    assertThat(rejected.retryAfterSeconds()).isEqualTo(40);
    // 다른 클라이언트·다른 버킷은 따로 센다.
    assertThat(
            limiter.check(ClientLimiter.Bucket.GENERAL, limiter.clientKey("203.0.113.8")).allowed())
        .isTrue();
    assertThat(limiter.check(ClientLimiter.Bucket.STATS, client).allowed()).isTrue();
    clock.now = clock.now.plusSeconds(40);
    assertThat(limiter.check(ClientLimiter.Bucket.GENERAL, client).allowed()).isTrue();
    assertThat(
            registry
                .get("loakraken.client.limit")
                .tag("bucket", "general")
                .tag("event", "reject")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 클라이언트_키는_주소를_담지_않고_같은_주소면_같다() {
    var limiter = limiter(new ClientLimitProperties(true, 2, 2, 3, 3, true));
    String key = limiter.clientKey("203.0.113.7");
    assertThat(key).hasSize(32).doesNotContain("203").isEqualTo(limiter.clientKey("203.0.113.7"));
    assertThat(key).isNotEqualTo(limiter.clientKey("203.0.113.8"));
  }

  @Test
  void 저장소_장애면_제한하지_않고_지표를_남긴다() {
    var limiter =
        new ClientLimiter(
            new ClientLimitProperties(true, 1, 1, 1, 1, true),
            (key, ttl) -> {
              throw new ClientLimitStore.Unavailable(new IllegalStateException("down"));
            },
            clock,
            registry,
            "key");
    for (int i = 0; i < 3; i++)
      assertThat(limiter.check(ClientLimiter.Bucket.GENERAL, "c").allowed()).isTrue();
    assertThat(registry.get("loakraken.client.limit").tag("event", "unavailable").counter().count())
        .isEqualTo(3);
  }

  @Test
  void 공식_호출은_요청_클라이언트에만_매기고_공유_예산을_쓰기_전에_거절한다() {
    var limiter = limiter(new ClientLimitProperties(true, 2, 10, 10, 10, true));
    var gateStore = new MemoryGateStore(clock);
    var gate =
        new LostArkRequestGate(
            clock, properties(), new LostArkMetrics(registry), gateStore, limiter);
    // 클라이언트가 없는 호출(수집기·예열)은 세지 않는다.
    for (int i = 0; i < 5; i++) {
      gate.acquire();
      gate.release();
    }
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 2; i++) {
        gate.acquire();
        gate.release();
      }
      assertThatThrownBy(gate::acquire)
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception ->
                  assertThat(exception.errorCode())
                      .isEqualTo(LostArkErrorCode.CLIENT_RATE_LIMITED));
    } finally {
      limiter.exit();
    }
    // 거절된 호출은 공유 예산(90)에 기록되지 않았다: 5 + 2 = 7회만 썼다.
    assertThat(gateStore.available(7, 0)).isFalse();
    assertThat(gateStore.available(8, 0)).isTrue();
    // 사용자 몫을 남기는 호출(withReserve)은 클라이언트 한도에 매기지 않는다.
    limiter.enter("client-a");
    try {
      gate.withReserve(
          10,
          () -> {
            gate.acquire();
            gate.release();
            return null;
          });
    } finally {
      limiter.exit();
    }
    // 다른 클라이언트는 영향이 없다.
    limiter.enter("client-b");
    try {
      gate.acquire();
      gate.release();
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 끄면_세지_않는다() {
    var limiter = limiter(new ClientLimitProperties(false, 0, 0, 0, 0, true));
    assertThat(limiter.check(ClientLimiter.Bucket.GENERAL, "c").allowed()).isTrue();
    limiter.enter("c");
    try {
      limiter.chargeOfficial();
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 필터는_공개_주소를_세고_내부_주소는_세지_않는다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 2, 1, 3, 3, true));
    var filter = new ClientLimitFilter(limiter);
    for (int i = 0; i < 5; i++)
      assertThat(run(filter, "/api/v1/markets/tabs", "127.0.0.1", null).getStatus()).isEqualTo(200);
    assertThat(run(filter, "/api/v1/markets/tabs", "172.18.0.5", null).getStatus()).isEqualTo(200);
    // STATS는 1회: 두 번째 통계 요청은 GENERAL 안이어도 거절
    assertThat(run(filter, "/api/v1/stats/summary", "203.0.113.7", null).getStatus())
        .isEqualTo(200);
    var rejected = run(filter, "/api/v1/rankings", "203.0.113.7", null);
    assertThat(rejected.getStatus()).isEqualTo(429);
    assertThat(rejected.getHeader("Retry-After")).isEqualTo("40");
    assertThat(rejected.getContentAsString())
        .contains("\"code\":\"CLIENT_RATE_LIMITED\"")
        .contains("\"data\":null");
    // IPv6는 /64로 묶는다.
    assertThat(run(filter, "/api/v1/markets/tabs", "2001:db8:1:2::1", null).getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/markets/tabs", "2001:db8:1:2::2", null).getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/markets/tabs", "2001:db8:1:2::3", null).getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/markets/tabs", "2001:db8:1:2::4", null).getStatus())
        .isEqualTo(429);
  }

  @Test
  void 봇은_주소와_관계없이_BOT_버킷_하나를_같이_쓴다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 2, 10, 10, 2, true));
    var filter = new ClientLimitFilter(limiter);
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.1", "1").getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.2", "1").getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.3", "1").getStatus())
        .isEqualTo(429);
    // 사람 요청은 BOT과 따로 센다.
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.3", null).getStatus())
        .isEqualTo(200);
  }

  @Test
  void 봇_표시_요청도_통계_경로는_봇_공유_STATS_한도를_받는다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 20, 60, 240, 120, true));
    var filter = new ClientLimitFilter(limiter);
    for (int i = 0; i < 60; i++)
      assertThat(run(filter, "/api/v1/rankings", "66.249.66." + (i % 200), "1").getStatus())
          .isEqualTo(200);
    var rejected = run(filter, "/api/v1/stats/summary", "66.249.70.1", "1");
    assertThat(rejected.getStatus()).isEqualTo(429);
    assertThat(rejected.getContentAsString()).contains("CLIENT_RATE_LIMITED");
    // 통계가 아닌 경로는 BOT 한도(120) 안이면 계속 된다.
    assertThat(run(filter, "/api/v1/characters/search", "66.249.70.1", "1").getStatus())
        .isEqualTo(200);
  }

  @Test
  void 봇_한도는_일반_클라이언트_한도보다_클_수_없다() {
    var defaults = new ClientLimitProperties(true, 20, 60, 240, 120, true);
    assertThat(defaults.botPerMinute()).isLessThanOrEqualTo(defaults.generalPerMinute());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ClientLimitProperties(true, 20, 60, 240, 241, true))
        .isInstanceOf(IllegalArgumentException.class);
    // 끈 상태는 검사하지 않는다.
    new ClientLimitProperties(false, 0, 0, 0, 1, true);
  }

  @Test
  void 필터는_요청을_처리하는_동안만_클라이언트를_스레드에_둔다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 1, 10, 10, 10, true));
    var filter = new ClientLimitFilter(limiter);
    var request = request("/api/v1/characters/search", "203.0.113.9", null);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> {
          limiter.chargeOfficial();
          calls.incrementAndGet();
          assertThatThrownBy(limiter::chargeOfficial).isInstanceOf(BusinessException.class);
        });
    assertThat(calls).hasValue(1);
    // 요청이 끝나면 지워져 같은 스레드의 백그라운드 호출에 매기지 않는다.
    limiter.chargeOfficial();
    limiter.chargeOfficial();
  }

  @Test
  void 내부_주소_판정() {
    assertThat(ClientLimitFilter.internal("127.0.0.1")).isTrue();
    assertThat(ClientLimitFilter.internal("10.1.2.3")).isTrue();
    assertThat(ClientLimitFilter.internal("192.168.0.1")).isTrue();
    assertThat(ClientLimitFilter.internal("::1")).isTrue();
    assertThat(ClientLimitFilter.internal("fd00::1")).isTrue();
    assertThat(ClientLimitFilter.internal("203.0.113.7")).isFalse();
    assertThat(ClientLimitFilter.internal("2001:db8::1")).isFalse();
    assertThat(ClientLimitFilter.internal("not-an-ip")).isFalse();
  }

  private ClientLimiter limiter(ClientLimitProperties properties) {
    return new ClientLimiter(properties, new ClientLimitStore.Memory(), clock, registry, "key");
  }

  private static MockHttpServletResponse run(
      ClientLimitFilter filter, String path, String address, String bot) throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(request(path, address, bot), response, new MockFilterChain());
    return response;
  }

  private static MockHttpServletRequest request(String path, String address, String bot) {
    var request = new MockHttpServletRequest("GET", path);
    request.setRemoteAddr(address);
    if (bot != null) request.addHeader(ClientLimitFilter.BOT_HEADER, bot);
    return request;
  }

  private static LostArkProperties properties() {
    return new LostArkProperties(
        URI.create("https://example.com"),
        "test",
        Duration.ofSeconds(3),
        Duration.ofSeconds(5),
        90,
        4,
        Duration.ofMinutes(5));
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-04T00:00:20Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
