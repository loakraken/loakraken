package com.loakraken.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.TestcontainersConfiguration;
import com.loakraken.alerts.infrastructure.PushTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 시세 알림 구독 만들기는 클라이언트 분당 상한(시험 설정 2)을 넘으면 429다. 확인 알림은 가짜 전송이 받는다(실제 푸시 서비스 호출 없음). */
@Import({TestcontainersConfiguration.class, AlertRateLimitIntegrationTest.Fakes.class})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "alerts.push.enabled=true",
      "alerts.push.vapid-subject=mailto:ops@example.com",
      "alerts.push.dispatch-interval=1h",
      "alerts.push.cleanup-interval=1h",
      "lostark.client-limit.exempt-internal=false",
      "lostark.client-limit.alert-subscribe-per-minute=2"
    })
class AlertRateLimitIntegrationTest {
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final String[] KEYS = keys();

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    registry.add("alerts.push.vapid-public-key", () -> KEYS[0]);
    registry.add("alerts.push.vapid-private-key", () -> KEYS[1]);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    @Bean
    @Primary
    PushTransport acceptingTransport() {
      return (endpoint, headers, body) -> 201;
    }
  }

  @Test
  void 구독_만들기는_분당_상한을_넘으면_429다() throws Exception {
    jdbc.sql("DELETE FROM push_subscription").update();
    int[] statuses = new int[3];
    try (var client = HttpClient.newHttpClient()) {
      for (int i = 0; i < 3; i++) {
        var body =
            "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/limit-"
                + i
                + "\",\"keys\":{\"p256dh\":\""
                + KEYS[2]
                + "\",\"auth\":\""
                + URL.encodeToString(new byte[16])
                + "\"}}";
        statuses[i] =
            client
                .send(
                    HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/alerts/subscriptions"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode();
      }
    }
    assertThat(statuses).containsExactly(200, 200, 429);
    jdbc.sql("DELETE FROM push_subscription").update();
  }

  private static String[] keys() {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      var vapid = generator.generateKeyPair();
      var ua = generator.generateKeyPair();
      return new String[] {
        URL.encodeToString(point((ECPublicKey) vapid.getPublic())),
        URL.encodeToString(scalar((ECPrivateKey) vapid.getPrivate())),
        URL.encodeToString(point((ECPublicKey) ua.getPublic()))
      };
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static byte[] point(ECPublicKey key) {
    var result = new byte[65];
    result[0] = 0x04;
    copy32(key.getW().getAffineX().toByteArray(), result, 1);
    copy32(key.getW().getAffineY().toByteArray(), result, 33);
    return result;
  }

  private static byte[] scalar(ECPrivateKey key) {
    var result = new byte[32];
    copy32(key.getS().toByteArray(), result, 0);
    return result;
  }

  private static void copy32(byte[] raw, byte[] target, int offset) {
    int from = Math.max(0, raw.length - 32);
    int length = raw.length - from;
    System.arraycopy(raw, from, target, offset + 32 - length, length);
  }
}
