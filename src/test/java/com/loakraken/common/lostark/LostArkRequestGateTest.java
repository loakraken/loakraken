package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class LostArkRequestGateTest {
  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private final LostArkMetrics metrics = new LostArkMetrics(registry);

  @Test
  void 동시_호출_제한을_초과하면_대기열을_늘리지_않는다() {
    var gate = gate(Clock.systemUTC(), 90, 1);
    gate.acquire();
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
    assertThat(
            registry
                .get("loakraken.lostark.gate")
                .tag("event", "concurrency_reject")
                .counter()
                .count())
        .isEqualTo(1);
    gate.release();
    gate.acquire();
    gate.release();
  }

  @Test
  void 공식_호출_로그는_DEBUG일_때만_허가된_호출마다_종류와_경로를_한_줄씩_남긴다() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(LostArkRequestGate.CALL_LOGGER);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var previous = logger.getLevel();
    try {
      var gate = gate(new MutableClock(), 90, 10);
      // 운영처럼 INFO면 남기지 않는다.
      logger.setLevel(ch.qos.logback.classic.Level.INFO);
      gate.acquire(LostArkMetrics.Endpoint.PROFILE);
      assertThat(appender.list).isEmpty();

      logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
      gate.acquire(LostArkMetrics.Endpoint.PROFILE);
      gate.withReserve(
          40,
          () -> {
            gate.acquire(LostArkMetrics.Endpoint.MARKET_ITEMS, "category=90000 item=\"들꽃\" page=2");
            return null;
          });
      gate.withUserReserve(
          30,
          () -> {
            gate.acquire(LostArkMetrics.Endpoint.ARMORY);
            return null;
          });
      assertThat(appender.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .containsExactly(
              "official-call endpoint=PROFILE origin=USER reserve=0",
              "official-call endpoint=MARKET_ITEMS origin=BACKGROUND reserve=40 category=90000 item=\"들꽃\" page=2",
              "official-call endpoint=ARMORY origin=USER_RESERVE reserve=30");

      // 거절된 호출(예산 초과)은 공식 호출이 아니므로 남기지 않는다.
      appender.list.clear();
      var tight = gate(new MutableClock(), 1, 10);
      tight.acquire(LostArkMetrics.Endpoint.PROFILE);
      assertThatThrownBy(() -> tight.acquire(LostArkMetrics.Endpoint.PROFILE))
          .isInstanceOf(BusinessException.class);
      assertThat(appender.list).hasSize(1);
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
    }
  }

  @Test
  void 허가된_공식_호출만_경로별로_센다() {
    var gate = gate(new MutableClock(), 90, 10);
    gate.acquire(LostArkMetrics.Endpoint.PROFILE);
    gate.withReserve(
        1,
        () -> {
          gate.acquire(LostArkMetrics.Endpoint.MARKET_ITEMS);
          return null;
        });
    gate.withUserReserve(
        1,
        () -> {
          gate.acquire(LostArkMetrics.Endpoint.ARMORY);
          gate.acquire(LostArkMetrics.Endpoint.ARMORY);
          return null;
        });
    // 예산을 넘은 호출은 거절이라 세지 않는다.
    var tight = gate(new MutableClock(), 1, 10);
    tight.acquire(LostArkMetrics.Endpoint.PROFILE);
    assertThatThrownBy(() -> tight.acquire(LostArkMetrics.Endpoint.PROFILE))
        .isInstanceOf(BusinessException.class);
    assertThat(calls("user")).isEqualTo(2);
    assertThat(calls("user_reserve")).isEqualTo(2);
    assertThat(calls("background")).isEqualTo(1);
    assertThat(registry.find("loakraken.lostark.calls").counters()).hasSize(3);
  }

  private double calls(String origin) {
    return registry.get("loakraken.lostark.calls").tag("origin", origin).counter().count();
  }

  @Test
  void 최근_1분의_호출_예산을_넘지_않는다() {
    var clock = new MutableClock();
    var gate = gate(clock, 1, 1);
    gate.acquire();
    gate.release();
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
    assertThat(
            registry.get("loakraken.lostark.gate").tag("event", "budget_reject").counter().count())
        .isEqualTo(1);
    clock.now = clock.now.plusSeconds(60);
    gate.acquire();
    gate.release();
  }

  @Test
  void 저장소가_기록을_잃어도_인스턴스_상한이_분당_예산을_지킨다() {
    // 늘 허가하는 저장소(창이 계속 지워지는 경우) — 두 번째 상한만으로 90에서 막히고, 60초 뒤 다시 찬다.
    var clock = new MutableClock();
    var forgetful =
        new GateStore() {
          @Override
          public Admission admit(int budget, int reserve) {
            return Admission.ADMITTED;
          }

          @Override
          public boolean available(int budget, int reserve) {
            return true;
          }

          @Override
          public void blockUntil(Instant until) {}
        };
    var gate =
        new LostArkRequestGate(
            clock,
            new LostArkProperties(
                URI.create("https://example.com"),
                "test",
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                90,
                1,
                Duration.ofMinutes(5)),
            metrics,
            forgetful);
    for (int i = 0; i < 90; i++) {
      gate.acquire();
      gate.release();
    }
    assertThatThrownBy(gate::acquire).isInstanceOf(BusinessException.class);
    assertThat(gate.backgroundAvailable(0)).isFalse();
    // 사용자 몫을 남기는 백그라운드도 같은 상한(90 − 몫)을 본다.
    assertThat(gate.backgroundAvailable(30)).isFalse();
    clock.now = clock.now.plusSeconds(60);
    gate.acquire();
    gate.release();
    assertThat(gate.backgroundAvailable(30)).isTrue();
  }

  @Test
  void 저장소가_거절하면_인스턴스_상한의_기록도_되돌린다() {
    // 저장소가 대기(429 뒤)로 거절한 호출은 두 번째 상한에도 남지 않는다.
    var clock = new MutableClock();
    var gate = gate(clock, 2, 1);
    var headers = new org.springframework.http.HttpHeaders();
    headers.add("X-RateLimit-Remaining", "0");
    headers.add("X-RateLimit-Reset", String.valueOf(clock.now.getEpochSecond() + 10));
    gate.observe(headers, false);
    for (int i = 0; i < 5; i++)
      assertThatThrownBy(gate::acquire).isInstanceOf(BusinessException.class);
    clock.now = clock.now.plusSeconds(11);
    // 10초 대기가 끝나면(같은 60초 창 안) 예산 2를 그대로 쓴다 — 거절된 5번이 남아 있었다면 막혔을 것.
    for (int i = 0; i < 2; i++) {
      gate.acquire();
      gate.release();
    }
    assertThatThrownBy(gate::acquire).isInstanceOf(BusinessException.class);
  }

  @Test
  void 공식_100회_한도보다_낮은_90회_예산에서_후속_호출을_차단한다() {
    var gate = gate(new MutableClock(), 90, 1);
    for (int i = 0; i < 90; i++) {
      gate.acquire();
      gate.release();
    }
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
  }

  @Test
  void 남은_할당량이_0이면_서버의_리셋_시각까지_차단한다() {
    var clock = new MutableClock();
    var gate = gate(clock, 90, 1);
    var headers = new HttpHeaders();
    headers.set("X-RateLimit-Remaining", "0");
    headers.set("X-RateLimit-Reset", Long.toString(clock.now.plusSeconds(30).getEpochSecond()));
    gate.observe(headers, false);
    assertThatThrownBy(() -> gate.acquire()).isInstanceOf(BusinessException.class);
    clock.now = clock.now.plusSeconds(30);
    gate.acquire();
    gate.release();
  }

  @Test
  void 백그라운드는_사용자_몫을_남기고_한도_대기_중에는_양보한다() {
    var clock = new MutableClock();
    var gate = gate(clock, 10, 4);
    assertThat(gate.backgroundAvailable(4)).isTrue();
    for (int i = 0; i < 6; i++) {
      gate.acquire();
      gate.release();
    }
    assertThat(gate.backgroundAvailable(4)).isFalse();
    assertThat(gate.backgroundAvailable(3)).isTrue();
    clock.now = clock.now.plusSeconds(61);
    assertThat(gate.backgroundAvailable(4)).isTrue();
    var headers = new HttpHeaders();
    headers.set("X-RateLimit-Remaining", "0");
    headers.set("X-RateLimit-Reset", Long.toString(clock.now.plusSeconds(30).getEpochSecond()));
    gate.observe(headers, false);
    assertThat(gate.backgroundAvailable(0)).isFalse();
  }

  @Test
  void 사용자_몫_호출은_클라이언트_공식_한도에도_매기고_백그라운드는_매기지_않는다() {
    // 보안 감사 10-12: reserve 안 호출이 클라이언트 OFFICIAL 한도를 벗어나 원정대 불러오기가 분당 24회까지 가능했다.
    var clock = new MutableClock();
    var limiter =
        new ClientLimiter(
            new ClientLimitProperties(true, 2, 10, 10, 10, true, 2, 6, 20, 5, 5),
            new ClientLimitStore.Memory(),
            clock,
            registry,
            "key");
    var gate =
        new LostArkRequestGate(
            clock,
            new LostArkProperties(
                URI.create("https://example.com"),
                "test",
                Duration.ofSeconds(3),
                Duration.ofSeconds(5),
                90,
                4,
                Duration.ofMinutes(5)),
            metrics,
            new MemoryGateStore(clock),
            limiter);
    limiter.enter("client-a");
    try {
      Runnable call =
          () -> {
            gate.acquire();
            gate.release();
          };
      // 백그라운드형(withReserve)은 클라이언트 한도에 매기지 않는다.
      for (int i = 0; i < 3; i++)
        gate.withReserve(
            30,
            () -> {
              call.run();
              return null;
            });
      // 사용자 몫형(withUserReserve)은 분당 2회까지, 세 번째는 CLIENT_RATE_LIMITED.
      for (int i = 0; i < 2; i++)
        gate.withUserReserve(
            30,
            () -> {
              call.run();
              return null;
            });
      assertThatThrownBy(
              () ->
                  gate.withUserReserve(
                      30,
                      () -> {
                        call.run();
                        return null;
                      }))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(LostArkErrorCode.CLIENT_RATE_LIMITED);
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 재료_검색_버킷은_공식_호출마다_세고_16번째는_클라이언트_한도이며_공유_예산과_공식_호출_한도는_쓰지_않는다() {
    // 보안 점검 10-18: 재료 시세 요청 안의 공식 검색이 클라이언트 한도 밖이라 한 클라이언트가 공유 예산을 다 쓸 수 있었다.
    var clock = new MutableClock();
    var limiter = limiter(clock, 2, 15, 36);
    var gate = materialGate(clock, limiter);
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 15; i++) materialCall(gate);
      assertThatThrownBy(() -> materialCall(gate))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(LostArkErrorCode.CLIENT_RATE_LIMITED);
    } finally {
      limiter.exit();
    }
    // 보안 재확인 ①: 클라이언트 버킷은 공유 예산을 잡기 전에 센다 — 걸린 16번째는 공유 예산을 쓰지 않아, 90 − 15 = 75번이 남는다.
    for (int i = 0; i < 75; i++) userCall(gate);
    assertThatThrownBy(() -> userCall(gate)).isInstanceOf(BusinessException.class);
    // 공식 호출 한도(OFFICIAL 2)도 재료 검색으로 쓰지 않았다.
    var other = materialGate(clock, limiter);
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 2; i++) userCall(other);
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 재료_검색은_모든_클라이언트_합계_36에서_막히고_클라이언트_한도에_걸린_호출은_합계에_들어가지_않는다() {
    var clock = new MutableClock();
    var limiter = limiter(clock, 100, 15, 36);
    var gate = materialGate(clock, limiter);
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 15; i++) materialCall(gate);
      // 16번째는 클라이언트 한도에 걸려 합계(36)에도 세지 않는다.
      assertThatThrownBy(() -> materialCall(gate)).isInstanceOf(BusinessException.class);
    } finally {
      limiter.exit();
    }
    limiter.enter("client-b");
    try {
      for (int i = 0; i < 15; i++) materialCall(gate);
    } finally {
      limiter.exit();
    }
    limiter.enter("client-c");
    try {
      for (int i = 0; i < 6; i++) materialCall(gate);
      // 합계 37번째(클라이언트 C 7번째): C 자신은 15 안이지만 합계 상한 36 → 공유 예산이 막힌 것과 같은 거절.
      assertThatThrownBy(() -> materialCall(gate))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
      // 공유 예산은 36만 썼다: 남은 54는 캐릭터 검색 같은 사용자 호출이 쓴다.
      for (int i = 0; i < 54; i++) userCall(gate);
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 수집기가_분당_50을_쓰는_중에도_재료_검색이_되고_캐릭터_검색_몫_30은_남는다() {
    // PM 판정 10-18: '전체 사용 ≤ 36'이면 수집기(몫 40을 남기고 분당 최대 50)만으로 재료 검색이 늘 막혔다.
    var clock = new MutableClock();
    var limiter = limiter(clock, 100, 100, 36);
    var gate = materialGate(clock, limiter);
    for (int i = 0; i < 50; i++)
      gate.withReserve(
          40,
          () -> {
            gate.acquire();
            gate.release();
            return null;
          });
    limiter.enter("client-c");
    try {
      // 재료 검색은 캐릭터 검색 몫 30을 남기는 데까지: 호출 뒤 전체 60까지 10번.
      for (int i = 0; i < 10; i++) materialCall(gate);
      assertThatThrownBy(() -> materialCall(gate))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
      // 남은 30은 캐릭터 검색 같은 사용자 호출(몫 0)이 쓴다.
      for (int i = 0; i < 30; i++) userCall(gate);
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 게이트가_바빠_공식_호출_전에_거절하면_재료_검색_버킷과_합계를_되돌린다() {
    // 코드 리뷰 10-18: 거절된 호출까지 합계에 남으면 게이트가 바쁠 때 공식 호출 없이 합계 36이 바닥나 모든 방문자의 재료 검색이 막혔다.
    var clock = new MutableClock();
    var limiter = limiter(clock, 100, 15, 36);
    var gate = materialGate(clock, limiter);
    // 동시 호출 4개를 모두 잡아 둔다(다른 요청이 공식 호출 중).
    for (int i = 0; i < 4; i++) gate.acquire();
    for (var client : java.util.List.of("client-a", "client-b", "client-c")) {
      limiter.enter(client);
      try {
        for (int i = 0; i < 20; i++)
          assertThatThrownBy(() -> materialCall(gate))
              .isInstanceOf(BusinessException.class)
              .extracting(exception -> ((BusinessException) exception).errorCode())
              .isEqualTo(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
      } finally {
        limiter.exit();
      }
    }
    for (int i = 0; i < 4; i++) gate.release();
    // 거절된 60번은 아무것도 쓰지 않았다: 클라이언트마다 15, 합계 36까지 그대로 된다.
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 15; i++) materialCall(gate);
    } finally {
      limiter.exit();
    }
    limiter.enter("client-b");
    try {
      for (int i = 0; i < 15; i++) materialCall(gate);
    } finally {
      limiter.exit();
    }
    limiter.enter("client-c");
    try {
      for (int i = 0; i < 6; i++) materialCall(gate);
      assertThatThrownBy(() -> materialCall(gate))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    } finally {
      limiter.exit();
    }
  }

  @Test
  void 공유_예산에서_거절되면_클라이언트_버킷과_합계가_그대로이고_성공한_호출만_남는다() {
    var clock = new MutableClock();
    var store = new ClientLimitStore.Memory();
    var limiter =
        new ClientLimiter(
            new ClientLimitProperties(
                true, 100, 10, 1000, 120, true, 2, 6, 20, 5, 5, 10, 3, 15, 36),
            store,
            clock,
            registry,
            "key");
    var gate = materialGate(clock, limiter);
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 5; i++) materialCall(gate);
    } finally {
      limiter.exit();
    }
    // 공유 예산을 60까지 채운다(재료 검색은 호출 뒤 60 이하일 때만).
    for (int i = 0; i < 55; i++) userCall(gate);
    limiter.enter("client-a");
    try {
      for (int i = 0; i < 10; i++)
        assertThatThrownBy(() -> materialCall(gate))
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).errorCode())
            .isEqualTo(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    } finally {
      limiter.exit();
    }
    // 성공한 5번만 남았다(거절된 10번은 버킷·합계 모두 되돌림).
    long window = clock.millis() / 60_000;
    assertThat(peek(store, "lostark:client-limit:material_search:client-a:" + window)).isEqualTo(5);
    assertThat(peek(store, "lostark:client-limit:material_search_all:shared:" + window))
        .isEqualTo(5);
  }

  /**
   * #1065 코드 리뷰 P2: 기능 버킷 경로(재료 검색)도 확인되지 않은 봇 표시 요청이면 주소별 봇 칸(5)과 그런 요청 합계 상한(20)을 받는다. 필터를 거쳐
   * 게이트까지 간다. 사람 한도(재료 검색 주소별 15, 합계 36)만 받으면 주소마다 8번, 합계 36번까지 된다.
   */
  @Test
  void 확인되지_않은_봇_표시_요청은_재료_검색에서도_주소별_한도와_합계_상한을_받는다() throws Exception {
    var clock = new MutableClock();
    var limiter = limiter(clock, 20, 15, 36);
    var gate = materialGate(clock, limiter);
    var filter = new ClientLimitFilter(limiter, unverifiedEverywhere(clock));
    var allowed = new java.util.ArrayList<Integer>();
    for (int n = 0; n < 6; n++)
      allowed.add(materialCalls(filter, gate, "203.0.113." + (100 + n), "1", 8));
    assertThat(allowed).containsExactly(5, 5, 5, 5, 0, 0);
    // 걸린 호출은 재료 검색 합계(36)를 쓰지 않았다(20만 씀): 표시 없는 사람 클라이언트는 자기 한도 15를 다 쓴다.
    assertThat(materialCalls(filter, gate, "203.0.113.200", null, 15)).isEqualTo(15);
    // 같은 주소의 보통 공식 호출(표시 있음)도 같은 주소별 봇 칸(이미 5)이라 막힌다.
    assertThat(officialCalls(filter, gate, "203.0.113.100", "1", 1)).isZero();
  }

  /**
   * v0.3.16 P3(#1065 코드 리뷰 뒤, PM 총괄 08:4x): 크롤러로 확인된 검색봇도 기능 버킷 경로(재료 검색)에서 주소별 봇 칸(5)과 확인된 봇 합계
   * 상한(20)을 받는다. 지금은 재료 검색 버킷(주소별 15, 합계 36)만 받아 주소마다 8번, 합계 36번까지 된다.
   */
  @Test
  void 확인된_크롤러도_재료_검색에서_주소별_봇_칸과_봇_합계_상한을_받는다() throws Exception {
    var clock = new MutableClock();
    var limiter = limiter(clock, 20, 15, 36);
    var gate = materialGate(clock, limiter);
    var verifier = verifiedEverywhere(clock);
    var filter = new ClientLimitFilter(limiter, verifier);
    // 확인되지 않은 표시 요청도 같은 [5,5,5,5,0,0]이 나오므로, 여섯 주소가 정말 확인된 크롤러인지 먼저 본다(시험이 판별하게).
    for (int n = 0; n < 6; n++) assertThat(verifier.verified("66.249.70." + (10 + n))).isTrue();
    var allowed = new java.util.ArrayList<Integer>();
    for (int n = 0; n < 6; n++)
      allowed.add(materialCalls(filter, gate, "66.249.70." + (10 + n), "1", 8));
    assertThat(allowed).containsExactly(5, 5, 5, 5, 0, 0);
    // 걸린 호출은 재료 검색 합계(36)를 쓰지 않았다(20만 씀): 표시 없는 사람 클라이언트는 자기 한도 15를 다 쓴다.
    assertThat(materialCalls(filter, gate, "203.0.113.201", null, 15)).isEqualTo(15);
  }

  /** 모든 주소를 크롤러로 확인하는 확인기(역방향 이름이 허용 도메인이고 정방향이 같은 주소). */
  private CrawlerVerifier verifiedEverywhere(Clock clock) {
    var resolver =
        new CrawlerVerifier.Resolver() {
          @Override
          public String reverse(java.net.InetAddress address) {
            return "bot-" + address.getHostAddress().replace('.', '-') + ".crawler.example";
          }

          @Override
          public java.util.List<java.net.InetAddress> forward(String host) {
            try {
              var ip = host.substring(4, host.indexOf(".crawler.example")).replace('-', '.');
              return java.util.List.of(java.net.InetAddress.getByName(ip));
            } catch (java.net.UnknownHostException exception) {
              return java.util.List.of();
            }
          }
        };
    return new CrawlerVerifier(
        new CrawlerVerifyProperties(
            java.util.List.of("crawler.example"),
            Duration.ofMillis(300),
            Duration.ofHours(24),
            Duration.ofHours(1),
            100,
            8),
        resolver,
        Runnable::run,
        clock,
        registry);
  }

  /** 어느 주소도 크롤러로 확인되지 않는 확인기(역방향 이름 없음). */
  private CrawlerVerifier unverifiedEverywhere(Clock clock) {
    var resolver =
        new CrawlerVerifier.Resolver() {
          @Override
          public String reverse(java.net.InetAddress address) {
            return null;
          }

          @Override
          public java.util.List<java.net.InetAddress> forward(String host) {
            return java.util.List.of();
          }
        };
    return new CrawlerVerifier(
        new CrawlerVerifyProperties(
            java.util.List.of("crawler.example"),
            Duration.ofMillis(300),
            Duration.ofHours(24),
            Duration.ofHours(1),
            100,
            8),
        resolver,
        Runnable::run,
        clock,
        registry);
  }

  private static int materialCalls(
      ClientLimitFilter filter, LostArkRequestGate gate, String address, String bot, int tries)
      throws Exception {
    return calls(filter, address, bot, tries, () -> materialCall(gate));
  }

  private static int officialCalls(
      ClientLimitFilter filter, LostArkRequestGate gate, String address, String bot, int tries)
      throws Exception {
    return calls(filter, address, bot, tries, () -> userCall(gate));
  }

  private static int calls(
      ClientLimitFilter filter, String address, String bot, int tries, Runnable call)
      throws Exception {
    var request =
        new org.springframework.mock.web.MockHttpServletRequest(
            "POST", "/api/v1/markets/material-prices");
    request.setRemoteAddr(address);
    if (bot != null) request.addHeader(ClientLimitFilter.BOT_HEADER, bot);
    var allowed = new java.util.concurrent.atomic.AtomicInteger();
    filter.doFilter(
        request,
        new org.springframework.mock.web.MockHttpServletResponse(),
        (req, res) -> {
          for (int i = 0; i < tries; i++) {
            try {
              call.run();
              allowed.incrementAndGet();
            } catch (BusinessException rejected) {
              // 거절은 세지 않는다.
            }
          }
        });
    return allowed.get();
  }

  /** Memory 저장소 카운터 값(올렸다가 바로 내려 본다). */
  private static long peek(ClientLimitStore store, String key) {
    long value = store.increment(key, Duration.ofMinutes(1)) - 1;
    store.decrement(key);
    return value;
  }

  private ClientLimiter limiter(Clock clock, int official, int material, int materialAll) {
    return new ClientLimiter(
        new ClientLimitProperties(
            true, official, 10, 1000, 120, true, 2, 6, 20, 5, 5, 10, 3, material, materialAll),
        new ClientLimitStore.Memory(),
        clock,
        registry,
        "key");
  }

  private LostArkRequestGate materialGate(Clock clock, ClientLimiter limiter) {
    return new LostArkRequestGate(
        clock,
        new LostArkProperties(
            URI.create("https://example.com"),
            "test",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            90,
            4,
            Duration.ofMinutes(5)),
        metrics,
        new MemoryGateStore(clock),
        limiter);
  }

  private static void userCall(LostArkRequestGate gate) {
    gate.acquire();
    gate.release();
  }

  private static void materialCall(LostArkRequestGate gate) {
    // 30 = 재료 시세 CHARACTER_SEARCH_RESERVE(MaterialUnitPriceServiceTest가 같은 값임을 확인).
    gate.withClientBucket(
        30,
        ClientLimiter.Bucket.MATERIAL_SEARCH,
        ClientLimiter.Bucket.MATERIAL_SEARCH_ALL,
        () -> {
          gate.acquire();
          gate.release();
          return null;
        });
  }

  private LostArkRequestGate gate(Clock clock, int budget, int concurrency) {
    return new LostArkRequestGate(
        clock,
        new LostArkProperties(
            URI.create("https://example.com"),
            "test",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            budget,
            concurrency,
            Duration.ofMinutes(5)),
        metrics);
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-04T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
