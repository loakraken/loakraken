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
    var limiter = limiter(new ClientLimitProperties(true, 2, 2, 3, 3, true, 2, 6, 20, 5, 5));
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
    var limiter = limiter(new ClientLimitProperties(true, 2, 2, 3, 3, true, 2, 6, 20, 5, 5));
    String key = limiter.clientKey("203.0.113.7");
    assertThat(key).hasSize(32).doesNotContain("203").isEqualTo(limiter.clientKey("203.0.113.7"));
    assertThat(key).isNotEqualTo(limiter.clientKey("203.0.113.8"));
  }

  @Test
  void 저장소_장애면_제한하지_않고_지표를_남긴다() {
    var limiter =
        new ClientLimiter(
            new ClientLimitProperties(true, 1, 1, 1, 1, true, 2, 6, 20, 5, 5),
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
    var limiter = limiter(new ClientLimitProperties(true, 2, 10, 10, 10, true, 2, 6, 20, 5, 5));
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
  void 남은_공식_칸을_올리지_않고_읽고_일괄_불러오기는_6을_남기고_멈춘다() {
    // #614: 공식 칸 10. 세지 않는 경로(클라이언트 없음)는 막지 않으므로 '남은 칸'도 끝이 없다.
    var limiter = limiter(new ClientLimitProperties(true, 10, 10, 10, 10, true, 2, 6, 20, 5, 5));
    var gate =
        new LostArkRequestGate(
            clock, properties(), new LostArkMetrics(registry), new MemoryGateStore(clock), limiter);
    assertThat(gate.remainingClientOfficial()).isEqualTo(Integer.MAX_VALUE);
    assertThat(gate.bulkMayContinue()).isTrue();
    limiter.enter("client-bulk");
    try {
      assertThat(gate.remainingClientOfficial()).isEqualTo(10);
      for (int i = 0; i < 3; i++) {
        gate.acquire();
        gate.release();
      }
      // 읽기는 칸을 쓰지 않는다.
      assertThat(gate.remainingClientOfficial()).isEqualTo(7);
      assertThat(gate.remainingClientOfficial()).isEqualTo(7);
      assertThat(gate.bulkMayContinue()).isTrue();
      gate.acquire();
      gate.release();
      // 남은 6 = BULK_KEEP_OFFICIAL이면 더 부르지 않는다.
      assertThat(gate.remainingClientOfficial()).isEqualTo(LostArkRequestGate.BULK_KEEP_OFFICIAL);
      assertThat(gate.bulkMayContinue()).isFalse();
      // 창이 바뀌면 다시 찬다.
      clock.now = clock.now.plusSeconds(60);
      assertThat(gate.remainingClientOfficial()).isEqualTo(10);
    } finally {
      limiter.exit();
    }
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void 거절_로그는_버킷_경로_키_앞_6자리만_남기고_한_분에_처음_몇_건만이다(
      org.springframework.boot.test.system.CapturedOutput output) {
    // #616: 경로는 자원명까지만(이름이 들어갈 수 있는 뒤쪽은 …), 클라이언트는 HMAC 키 앞 6자리, 주소·이름 없음.
    var limiter = limiter(new ClientLimitProperties(true, 2, 2, 1, 1, true, 2, 6, 20, 5, 5));
    var client = limiter.clientKey("203.0.113.77");
    limiter.enterRoute("GET", "/api/v1/characters/검증캐릭터/siblings");
    try {
      for (int i = 0; i < 1 + ClientLimiter.REJECT_LOGS_PER_WINDOW + 3; i++)
        limiter.check(ClientLimiter.Bucket.GENERAL, client);
    } finally {
      limiter.exitRoute();
    }
    var lines =
        output.getOut().lines().filter(line -> line.contains("event=client_limit_reject")).toList();
    assertThat(lines).hasSize(ClientLimiter.REJECT_LOGS_PER_WINDOW);
    assertThat(lines.getFirst())
        .contains("bucket=general")
        .contains("route=GET /api/v1/characters/…")
        .contains("client=" + client.substring(0, 6))
        .doesNotContain("검증캐릭터")
        .doesNotContain("203.0.113.77")
        .doesNotContain(client.substring(0, 7));
    // 지표는 거절을 전부 센다.
    assertThat(registry.get("loakraken.client.limit").tag("event", "reject").counter().count())
        .isEqualTo(ClientLimiter.REJECT_LOGS_PER_WINDOW + 3);
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(
      org.springframework.boot.test.system.OutputCaptureExtension.class)
  void 되돌리는_버킷의_거절도_한_분에_처음_몇_건만_로그에_남긴다(
      org.springframework.boot.test.system.CapturedOutput output) {
    // 되돌리는 버킷(재료 검색 클라이언트 몫)은 거절 때 카운터를 되돌려 한도 + 1에 머물므로 로그 상한을 따로 센다.
    var limiter =
        limiter(new ClientLimitProperties(true, 2, 2, 10, 10, true, 2, 6, 20, 5, 5, 10, 3, 1, 36));
    var client = limiter.clientKey("203.0.113.78");
    limiter.enter(client);
    limiter.enterRoute("POST", "/api/v1/markets/material-prices");
    int rejected = 0;
    try {
      for (int i = 0; i < 1 + ClientLimiter.REJECT_LOGS_PER_WINDOW + 3; i++)
        try {
          limiter.chargeRefundable(ClientLimiter.Bucket.MATERIAL_SEARCH);
        } catch (com.loakraken.common.api.BusinessException exception) {
          rejected++;
        }
    } finally {
      limiter.exitRoute();
      limiter.exit();
    }
    assertThat(rejected).isEqualTo(ClientLimiter.REJECT_LOGS_PER_WINDOW + 3);
    var lines =
        output.getOut().lines().filter(line -> line.contains("event=client_limit_reject")).toList();
    assertThat(lines).hasSize(ClientLimiter.REJECT_LOGS_PER_WINDOW);
    assertThat(lines.getFirst())
        .contains("bucket=material_search")
        .contains("route=POST /api/v1/markets/…")
        .contains("client=" + client.substring(0, 6))
        .doesNotContain("203.0.113.78");
  }

  @Test
  void 로그용_경로와_키는_값을_남기지_않는다() {
    assertThat(ClientLimiter.routeHint("POST", "/api/v1/characters/roster-combat-power/refresh"))
        .isEqualTo("POST /api/v1/characters/…");
    assertThat(ClientLimiter.routeHint("GET", "/api/v1/guilds/서버/길드"))
        .isEqualTo("GET /api/v1/guilds/…");
    assertThat(ClientLimiter.routeHint("GET", "/api/v1/rankings"))
        .isEqualTo("GET /api/v1/rankings");
    assertThat(ClientLimiter.routeHint("GET", "/api/v1/Name")).isEqualTo("GET /api/v1/*");
    // 알려진 자원명이 아니면 소문자여도 남기지 않는다.
    assertThat(ClientLimiter.routeHint("GET", "/api/v1/secret-token/x"))
        .isEqualTo("GET /api/v1/*/…");
    assertThat(ClientLimiter.routeHint("GET", "/other/v9/characters")).isEqualTo("GET *");
    assertThat(ClientLimiter.routeHint("GET", "/api")).isEqualTo("GET *");
    assertThat(ClientLimiter.shortKey("0123456789abcdef")).isEqualTo("012345");
    assertThat(ClientLimiter.shortKey("bot:fedcba9876543210")).isEqualTo("bot:fedcba");
    assertThat(ClientLimiter.shortKey("all")).isEqualTo("bot-all");
    assertThat(ClientLimiter.shortKey("shared")).isEqualTo("shared");
    // 요청 밖(백그라운드)은 경로가 없다.
    assertThat(ClientLimiter.route()).isEqualTo("-");
  }

  @Test
  void 끄면_세지_않는다() {
    var limiter = limiter(new ClientLimitProperties(false, 0, 0, 0, 0, true, 2, 6, 20, 5, 5));
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
    var limiter = limiter(new ClientLimitProperties(true, 2, 1, 3, 3, true, 2, 6, 20, 5, 5));
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
  void 봇은_주소별로_세어_한_주소가_다른_봇_주소를_막지_않는다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 2, 10, 10, 2, true, 2, 6, 20, 5, 5));
    var filter = new ClientLimitFilter(limiter);
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.1", "1").getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.1", "1").getStatus())
        .isEqualTo(200);
    // 같은 주소의 세 번째 봇 요청은 그 주소의 BOT 한도(2)에 걸린다.
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.1", "1").getStatus())
        .isEqualTo(429);
    // 다른 봇 주소는 막히지 않는다.
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.2", "1").getStatus())
        .isEqualTo(200);
    // 사람 요청은 BOT과 따로 센다.
    assertThat(run(filter, "/api/v1/characters/search", "66.249.66.1", null).getStatus())
        .isEqualTo(200);
  }

  @Test
  void 봇_표시_요청도_통계_경로는_그_주소의_STATS_한도를_받는다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 20, 60, 240, 120, true, 2, 6, 20, 5, 5));
    var filter = new ClientLimitFilter(limiter);
    for (int i = 0; i < 60; i++)
      assertThat(run(filter, "/api/v1/rankings", "66.249.70.1", "1").getStatus()).isEqualTo(200);
    var rejected = run(filter, "/api/v1/stats/summary", "66.249.70.1", "1");
    assertThat(rejected.getStatus()).isEqualTo(429);
    assertThat(rejected.getContentAsString()).contains("CLIENT_RATE_LIMITED");
    // 통계가 아닌 경로는 BOT 한도(120) 안이면 계속 되고, 다른 봇 주소의 통계 경로도 된다.
    assertThat(run(filter, "/api/v1/characters/search", "66.249.70.1", "1").getStatus())
        .isEqualTo(200);
    assertThat(run(filter, "/api/v1/stats/summary", "66.249.70.2", "1").getStatus()).isEqualTo(200);
  }

  @Test
  void 봇_공식_호출은_주소별_한도와_모든_봇_합계_상한을_함께_센다() {
    // 봇 주소별 공식 호출 2, 모든 봇 합계 3.
    var limiter = limiter(new ClientLimitProperties(true, 2, 10, 10, 10, true, 2, 6, 3, 2, 5));
    Runnable charge = limiter::chargeOfficial;
    limiter.enter(limiter.botKey("66.249.66.1"));
    try {
      charge.run();
      charge.run();
      // 같은 봇 주소의 세 번째는 주소별 한도.
      assertThatThrownBy(charge::run).isInstanceOf(BusinessException.class);
    } finally {
      limiter.exit();
    }
    limiter.enter(limiter.botKey("66.249.66.2"));
    try {
      // 주소별로 거절된 호출은 합계에 세지 않는다. 앞 주소 2회 + 이 주소 1회 = 합계 3까지 되고, 이 주소의 두 번째(주소별 2 이내)는
      // 모든 봇 합계 상한 3에 걸린다.
      charge.run();
      assertThatThrownBy(charge::run).isInstanceOf(BusinessException.class);
    } finally {
      limiter.exit();
    }
    // 사람 클라이언트는 봇 합계 상한과 관계없다.
    limiter.enter(limiter.clientKey("203.0.113.9"));
    try {
      charge.run();
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 봇_한_주소는_기본값으로_공식_호출_5회까지이고_사람_클라이언트는_20회다() {
    // 한 봇 주소가 모든 봇 합계 상한(20)을 혼자 다 쓰지 못한다.
    var limiter = limiter(new ClientLimitProperties(true, 20, 60, 240, 120, true, 2, 5, 20, 5, 5));
    limiter.enter(limiter.botKey("66.249.66.3"));
    try {
      for (int i = 0; i < 5; i++) limiter.chargeOfficial();
      assertThatThrownBy(limiter::chargeOfficial).isInstanceOf(BusinessException.class);
    } finally {
      limiter.exit();
    }
    limiter.enter(limiter.botKey("66.249.66.4"));
    try {
      limiter.chargeOfficial();
    } finally {
      limiter.exit();
    }
    limiter.enter(limiter.clientKey("203.0.113.10"));
    try {
      for (int i = 0; i < 20; i++) limiter.chargeOfficial();
      assertThatThrownBy(limiter::chargeOfficial).isInstanceOf(BusinessException.class);
    } finally {
      limiter.exit();
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ClientLimitProperties(true, 20, 60, 240, 120, true, 2, 5, 20, 20, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 봇_한도는_일반_클라이언트_한도보다_클_수_없다() {
    var defaults = new ClientLimitProperties(true, 20, 60, 240, 120, true, 2, 6, 20, 5, 5);
    assertThat(defaults.botPerMinute()).isLessThanOrEqualTo(defaults.generalPerMinute());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ClientLimitProperties(true, 20, 60, 240, 241, true, 2, 6, 20, 5, 5))
        .isInstanceOf(IllegalArgumentException.class);
    // 끈 상태는 검사하지 않는다.
    new ClientLimitProperties(false, 0, 0, 0, 1, true, 2, 6, 20, 5, 5);
  }

  @Test
  void 필터는_요청을_처리하는_동안만_클라이언트를_스레드에_둔다() throws Exception {
    var limiter = limiter(new ClientLimitProperties(true, 1, 10, 10, 10, true, 2, 6, 20, 5, 5));
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
