package com.loakraken.common.lostark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.TestcontainersConfiguration;
import com.loakraken.common.api.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;

/** 공유 호출 게이트(#107): 같은 Valkey를 쓰는 게이트 두 개(인스턴스 두 개 가정). 실제 공식 호출은 없다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SharedGateIntegrationTest {
  @Autowired StringRedisTemplate redis;
  @Autowired GateStore configured;

  @Test
  void 기본_구성은_Valkey_게이트다() {
    assertThat(configured).isInstanceOf(ValkeyGateStore.class);
  }

  @Test
  void 두_인스턴스의_호출을_합산해_분당_예산을_지킨다() {
    String keyId = "test-" + UUID.randomUUID();
    var first = gate(new ValkeyGateStore(redis, keyId), 10);
    var second = gate(new ValkeyGateStore(redis, keyId), 10);
    for (int i = 0; i < 5; i++) {
      first.acquire();
      first.release();
      second.acquire();
      second.release();
    }
    assertThatThrownBy(first::acquire).isInstanceOf(BusinessException.class);
    assertThatThrownBy(second::acquire).isInstanceOf(BusinessException.class);
    // 다른 키는 따로 센다.
    var other = gate(new ValkeyGateStore(redis, keyId + "-other"), 10);
    other.acquire();
    other.release();
  }

  @Test
  void 백그라운드는_사용자_몫을_남기고_판정과_기록을_한_번에_한다() {
    String keyId = "test-" + UUID.randomUUID();
    var user = gate(new ValkeyGateStore(redis, keyId), 10);
    var background = gate(new ValkeyGateStore(redis, keyId), 10);
    for (int i = 0; i < 6; i++) {
      background.withReserve(
          4,
          () -> {
            background.acquire();
            background.release();
            return null;
          });
    }
    // 10 − 4 = 6을 쓰면 백그라운드는 막히고 사용자 몫은 남는다.
    assertThat(background.backgroundAvailable(4)).isFalse();
    assertThatThrownBy(
            () ->
                background.withReserve(
                    4,
                    () -> {
                      background.acquire();
                      return null;
                    }))
        .isInstanceOf(BusinessException.class);
    user.acquire();
    user.release();
  }

  @Test
  void 한_인스턴스가_받은_429는_다른_인스턴스도_기다리게_한다() {
    String keyId = "test-" + UUID.randomUUID();
    var first = gate(new ValkeyGateStore(redis, keyId), 90);
    var second = gate(new ValkeyGateStore(redis, keyId), 90);
    first.observe(new HttpHeaders(), true);
    assertThatThrownBy(second::acquire).isInstanceOf(BusinessException.class);
    assertThat(second.backgroundAvailable(0)).isFalse();
    // 더 이른 대기 시각으로는 줄이지 않는다.
    var headers = new HttpHeaders();
    headers.add("X-RateLimit-Remaining", "0");
    headers.add("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 5));
    second.observe(headers, false);
    var store = new ValkeyGateStore(redis, keyId);
    assertThat(store.blockedFor()).isGreaterThan(Duration.ofSeconds(30));
  }

  @Test
  void 임대는_한_인스턴스만_얻고_소유자만_연장_해제한다() {
    String name = "test-" + UUID.randomUUID();
    var a = new ValkeyLeaderLease(redis, "instance-a");
    var b = new ValkeyLeaderLease(redis, "instance-b");
    assertThat(a.hold(name, Duration.ofSeconds(30))).isTrue();
    assertThat(b.hold(name, Duration.ofSeconds(30))).isFalse();
    assertThat(a.hold(name, Duration.ofSeconds(30))).isTrue();
    b.release(name);
    assertThat(b.hold(name, Duration.ofSeconds(30))).isFalse();
    a.release(name);
    assertThat(b.hold(name, Duration.ofSeconds(30))).isTrue();
  }

  @Test
  void 저장소_장애면_사용자_요청은_빠르게_거절하고_백그라운드와_임대는_멈춘다() {
    var factory =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("127.0.0.1", 1),
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(100)).build());
    factory.afterPropertiesSet();
    factory.start();
    try {
      var broken = new StringRedisTemplate(factory);
      var registry = new SimpleMeterRegistry();
      var gate =
          new LostArkRequestGate(
              Clock.systemUTC(),
              properties(90),
              new LostArkMetrics(registry),
              new ValkeyGateStore(broken, "broken"));
      assertThatThrownBy(gate::acquire)
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception ->
                  assertThat(exception.errorCode())
                      .isEqualTo(LostArkErrorCode.LOST_ARK_GATE_UNAVAILABLE));
      assertThat(gate.backgroundAvailable(0)).isFalse();
      assertThat(new ValkeyLeaderLease(broken, "x").hold("broken", Duration.ofSeconds(1)))
          .isFalse();
      assertThat(registry.find("loakraken.lostark.gate").tag("event", "gate_unavailable").counter())
          .isNotNull();
    } finally {
      factory.destroy();
    }
  }

  @Test
  void 키_식별자는_API_키_원문이_아니다() {
    var gate = new LostArkGateProperties("valkey", "", Duration.ofMillis(100));
    var withKey = properties(90, "secret-api-key-value");
    String id = LostArkGateConfiguration.keyId(gate, withKey);
    assertThat(id).hasSize(12).doesNotContain("secret");
    assertThat(
            LostArkGateConfiguration.keyId(
                new LostArkGateProperties("valkey", "prod-a", Duration.ofMillis(100)), withKey))
        .isEqualTo("prod-a");
    assertThat(LostArkGateConfiguration.keyId(gate, properties(90))).isEqualTo("none");
  }

  private static LostArkRequestGate gate(GateStore store, int budget) {
    return new LostArkRequestGate(
        Clock.systemUTC(),
        properties(budget),
        new LostArkMetrics(new SimpleMeterRegistry()),
        store);
  }

  private static LostArkProperties properties(int budget) {
    return properties(budget, "");
  }

  private static LostArkProperties properties(int budget, String key) {
    return new LostArkProperties(
        URI.create("http://127.0.0.1:1"),
        key,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        budget,
        4,
        Duration.ofMinutes(5));
  }
}
