package com.loakraken.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.TestcontainersConfiguration;
import com.loakraken.alerts.application.AlertService;
import com.loakraken.alerts.infrastructure.PushTransport;
import com.loakraken.market.application.MarketPricesObserved;
import com.loakraken.market.domain.MarketPrice;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 시세 알림 웹 푸시(M33). 발송은 가짜 전송으로 받고 실제 푸시 서비스는 부르지 않는다. */
@Import({TestcontainersConfiguration.class, AlertIntegrationTest.Fakes.class})
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "alerts.push.enabled=true",
      "alerts.push.vapid-subject=mailto:ops@example.com",
      "alerts.push.max-subscriptions=3",
      "alerts.push.sends-per-minute=2",
      "alerts.push.dispatch-interval=1h",
      "alerts.push.cleanup-interval=1h"
    })
@ExtendWith(OutputCaptureExtension.class)
class AlertIntegrationTest {
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final String VAPID_PUBLIC;
  private static final String VAPID_PRIVATE;
  private static final String UA_PUBLIC;
  private static final String UA_AUTH = URL.encodeToString(new byte[16]);
  private static final String ENDPOINT =
      "https://fcm.googleapis.com/fcm/send/secret-endpoint-token-1";
  private static final String ENDPOINT_2 =
      "https://updates.push.services.mozilla.com/wpush/v2/secret-2";
  private static final long ITEM = 66102006L;

  static {
    try {
      var generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      var vapid = generator.generateKeyPair();
      VAPID_PUBLIC = URL.encodeToString(point((ECPublicKey) vapid.getPublic()));
      VAPID_PRIVATE = URL.encodeToString(scalar((ECPrivateKey) vapid.getPrivate()));
      UA_PUBLIC = URL.encodeToString(point((ECPublicKey) generator.generateKeyPair().getPublic()));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    registry.add("alerts.push.vapid-public-key", () -> VAPID_PUBLIC);
    registry.add("alerts.push.vapid-private-key", () -> VAPID_PRIVATE);
  }

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired AlertService alerts;
  @Autowired ApplicationEventPublisher events;
  @Autowired FakeTransport transport;
  @Autowired MutableClock clock;
  private final JsonMapper mapper = new JsonMapper();

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    @Bean
    @Primary
    FakeTransport fakeTransport() {
      return new FakeTransport();
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  static final class FakeTransport implements PushTransport {
    final List<URI> endpoints = new ArrayList<>();
    final List<Map<String, String>> headers = new ArrayList<>();
    final List<byte[]> bodies = new ArrayList<>();
    int status = 201;
    boolean fail;

    @Override
    public int post(URI endpoint, Map<String, String> headers, byte[] body) throws IOException {
      if (fail) throw new IOException("connect failed to " + endpoint);
      endpoints.add(endpoint);
      this.headers.add(Map.copyOf(headers));
      bodies.add(body);
      return status;
    }
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-14T00:00:10Z");

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

  @BeforeEach
  void 정리() {
    jdbc.sql("DELETE FROM push_subscription").update();
    jdbc.sql("DELETE FROM alert_item_price").update();
    transport.endpoints.clear();
    transport.headers.clear();
    transport.bodies.clear();
    transport.status = 201;
    transport.fail = false;
    clock.now = Instant.parse("2026-10-14T00:00:10Z");
  }

  @Test
  void 구독과_조건을_만들고_거짓에서_참으로_바뀔_때만_보낸다() throws Exception {
    observe("1000");
    assertThat(data(get("/api/v1/alerts/vapid-public-key")).path("publicKey").asText())
        .isEqualTo(VAPID_PUBLIC);
    var items = data(get("/api/v1/alerts/items"));
    assertThat(items.get(0).path("itemId").asLong()).isEqualTo(ITEM);
    assertThat(items.get(0).path("currentMinPrice").decimalValue()).isEqualByComparingTo("1000");

    var sub = subscribe(ENDPOINT);
    var id = sub.path("subscriptionId").asText();
    var token = sub.path("manageToken").asText();
    // 토큰 원문은 저장하지 않는다(해시만).
    var stored =
        jdbc.sql("SELECT manage_token_hash FROM push_subscription").query(String.class).single();
    assertThat(stored).hasSize(64).isNotEqualTo(token);

    // 이미 참(1000 ≤ 1200)이면 보내지 않고 시작한다.
    var condition =
        data(post("/api/v1/alerts/subscriptions/" + id + "/conditions", token, condition(1200)));
    assertThat(condition.path("matchedNow").asBoolean()).isTrue();
    observe("1100");
    assertThat(alerts.dispatch()).isZero();

    // 거짓(1300) → 참(1150): 한 번 보낸다.
    observe("1300");
    observe("1150");
    assertThat(alerts.dispatch()).isEqualTo(1);
    assertThat(transport.endpoints).containsExactly(URI.create(ENDPOINT));
    var headers = transport.headers.getFirst();
    assertThat(headers).containsEntry("Content-Encoding", "aes128gcm").containsKey("TTL");
    assertThat(headers.get("Authorization")).startsWith("vapid t=").endsWith(", k=" + VAPID_PUBLIC);
    assertThat(transport.bodies.getFirst()[20]).isEqualTo((byte) 65);
    // 참이 이어지는 동안은 다시 보내지 않는다.
    observe("1100");
    assertThat(alerts.dispatch()).isZero();

    // 재발송 간격(6시간) 안의 거짓 → 참은 보내지 않는다.
    clock.now = clock.now.plus(Duration.ofHours(1));
    observe("1300");
    observe("1100");
    assertThat(alerts.dispatch()).isZero();
    // 6시간이 지난 뒤 거짓 → 참은 보낸다.
    clock.now = clock.now.plus(Duration.ofHours(6));
    observe("1300");
    observe("1100");
    assertThat(alerts.dispatch()).isEqualTo(1);

    var list = data(get("/api/v1/alerts/subscriptions/" + id + "/conditions", token));
    assertThat(list.size()).isEqualTo(1);
    assertThat(list.get(0).path("lastSentAt").isNull()).isFalse();
  }

  @Test
  void 토큰이_없거나_틀리거나_구독이_없으면_모두_같은_404다() throws Exception {
    observe("1000");
    var sub = subscribe(ENDPOINT);
    var id = sub.path("subscriptionId").asText();
    var path = "/api/v1/alerts/subscriptions/" + id + "/conditions";
    var none = get(path, null);
    var wrong = get(path, "wrong-token");
    var unknown =
        get("/api/v1/alerts/subscriptions/00000000-0000-0000-0000-000000000000/conditions", "x");
    assertThat(none.statusCode()).isEqualTo(404);
    assertThat(wrong.body()).isEqualTo(none.body()).isEqualTo(unknown.body());
    assertThat(none.body()).contains("ALERT_SUBSCRIPTION_NOT_FOUND");
  }

  @Test
  void 같은_주소로_다시_구독하면_새_토큰을_주고_옛_토큰과_조건은_무효다() throws Exception {
    observe("1000");
    var first = subscribe(ENDPOINT);
    var id = first.path("subscriptionId").asText();
    var oldToken = first.path("manageToken").asText();
    data(post("/api/v1/alerts/subscriptions/" + id + "/conditions", oldToken, condition(900)));

    var second = subscribe(ENDPOINT);
    assertThat(second.path("subscriptionId").asText()).isEqualTo(id);
    var newToken = second.path("manageToken").asText();
    assertThat(newToken).isNotEqualTo(oldToken);
    assertThat(get("/api/v1/alerts/subscriptions/" + id + "/conditions", oldToken).statusCode())
        .isEqualTo(404);
    assertThat(data(get("/api/v1/alerts/subscriptions/" + id + "/conditions", newToken)).size())
        .isZero();
  }

  @Test
  void 입력과_상한을_지킨다() throws Exception {
    observe("1000");
    // 허용 목록 밖 주소·잘못된 키는 400.
    assertThat(
            postRaw(
                    "/api/v1/alerts/subscriptions",
                    null,
                    body("http://fcm.googleapis.com/x", UA_PUBLIC))
                .statusCode())
        .isEqualTo(400);
    assertThat(postRaw("/api/v1/alerts/subscriptions", null, body(ENDPOINT, "AAAA")).statusCode())
        .isEqualTo(400);

    var sub = subscribe(ENDPOINT);
    var id = sub.path("subscriptionId").asText();
    var token = sub.path("manageToken").asText();
    var conditions = "/api/v1/alerts/subscriptions/" + id + "/conditions";
    // 관측하지 않은 품목은 걸 수 없다.
    var unknownItem =
        postRaw(conditions, token, "{\"itemId\":1,\"comparator\":\"AT_OR_BELOW\",\"price\":10}");
    assertThat(unknownItem.statusCode()).isEqualTo(400);
    assertThat(unknownItem.body()).contains("ALERT_ITEM_NOT_ALERTABLE");
    // 오래전에 관측한 품목도 걸 수 없다.
    clock.now = clock.now.plus(Duration.ofHours(3));
    assertThat(postRaw(conditions, token, condition(900)).statusCode()).isEqualTo(400);
    observe("1000");
    // 구독당 10개까지.
    for (int i = 0; i < 10; i++) data(post(conditions, token, condition(900 + i)));
    var eleventh = postRaw(conditions, token, condition(999));
    assertThat(eleventh.statusCode()).isEqualTo(400);
    assertThat(eleventh.body()).contains("ALERT_TOO_MANY_CONDITIONS");
    // 잘못된 비교·가격은 400.
    assertThat(
            postRaw(
                    conditions,
                    token,
                    "{\"itemId\":66102006,\"comparator\":\"BELOW\",\"price\":10}")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            postRaw(
                    conditions,
                    token,
                    "{\"itemId\":66102006,\"comparator\":\"AT_OR_BELOW\",\"price\":0}")
                .statusCode())
        .isEqualTo(400);

    // 전체 구독 상한(시험 설정 3): 넘으면 503, 같은 주소 재구독은 수가 늘지 않아 된다.
    subscribe(ENDPOINT_2);
    subscribe("https://web.push.apple.com/secret-3");
    var full =
        postRaw(
            "/api/v1/alerts/subscriptions",
            null,
            body("https://web.push.apple.com/secret-4", UA_PUBLIC));
    assertThat(full.statusCode()).isEqualTo(503);
    assertThat(full.body()).contains("ALERTS_FULL");
    assertThat(
            postRaw("/api/v1/alerts/subscriptions", null, body(ENDPOINT, UA_PUBLIC)).statusCode())
        .isEqualTo(200);
  }

  @Test
  void 분당_발송_상한을_넘으면_다음_분으로_미룬다() throws Exception {
    observe("1300");
    for (var endpoint : List.of(ENDPOINT, ENDPOINT_2, "https://web.push.apple.com/secret-3")) {
      var sub = subscribe(endpoint);
      data(
          post(
              "/api/v1/alerts/subscriptions/" + sub.path("subscriptionId").asText() + "/conditions",
              sub.path("manageToken").asText(),
              condition(1200)));
    }
    observe("1100");
    assertThat(alerts.dispatch()).isEqualTo(2);
    assertThat(alerts.dispatch()).isZero();
    clock.now = clock.now.plus(Duration.ofMinutes(1));
    assertThat(alerts.dispatch()).isEqualTo(1);
    assertThat(transport.endpoints).hasSize(3);
  }

  @Test
  void 푸시_서비스가_구독_없음이면_즉시_지우고_실패_로그에_주소가_없다(CapturedOutput output) throws Exception {
    observe("1300");
    var sub = subscribe(ENDPOINT);
    var id = sub.path("subscriptionId").asText();
    var token = sub.path("manageToken").asText();
    data(post("/api/v1/alerts/subscriptions/" + id + "/conditions", token, condition(1200)));

    // 연결 실패: 조건은 남고, 로그에는 주소·키·토큰 원문이 없다.
    transport.fail = true;
    observe("1100");
    alerts.dispatch();
    assertThat(output.getAll())
        .contains("시세 알림 발송에 실패했습니다")
        .doesNotContain("secret-endpoint-token-1", UA_PUBLIC, token, VAPID_PRIVATE);

    // 410: 구독과 조건을 물리 삭제한다.
    transport.fail = false;
    transport.status = 410;
    observe("1300");
    clock.now = clock.now.plus(Duration.ofHours(7));
    observe("1100");
    alerts.dispatch();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Long.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM price_alert").query(Long.class).single()).isZero();
    assertThat(output.getAll()).doesNotContain("secret-endpoint-token-1");
  }

  @Test
  void 구독을_끄면_즉시_지우고_오래_안_쓴_구독은_정리한다() throws Exception {
    observe("1000");
    var sub = subscribe(ENDPOINT);
    var id = sub.path("subscriptionId").asText();
    var token = sub.path("manageToken").asText();
    data(post("/api/v1/alerts/subscriptions/" + id + "/conditions", token, condition(900)));
    var deleted = request("DELETE", "/api/v1/alerts/subscriptions/" + id, token, null);
    assertThat(deleted.statusCode()).isEqualTo(200);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM push_subscription").query(Long.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM price_alert").query(Long.class).single()).isZero();

    subscribe(ENDPOINT_2);
    clock.now = clock.now.plus(Duration.ofDays(89));
    assertThat(alerts.cleanup()).isZero();
    clock.now = clock.now.plus(Duration.ofDays(2));
    assertThat(alerts.cleanup()).isEqualTo(1);
  }

  @Test
  void OpenAPI는_안쪽까지_required이고_없을_수_있는_값만_nullable이며_토큰은_헤더다() throws Exception {
    var document = mapper.readTree(get("/v3/api-docs").body());
    var schemas = document.at("/components/schemas");
    assertThat(schemas.at("/ConditionResponse/required").toString())
        .contains(
            "conditionId",
            "itemId",
            "itemName",
            "comparator",
            "price",
            "matchedNow",
            "currentMinPrice",
            "observedAt",
            "lastSentAt",
            "createdAt");
    for (var field : List.of("currentMinPrice", "lastSentAt"))
      assertThat(schemas.at("/ConditionResponse/properties/" + field + "/anyOf/1/type").asText())
          .as(field)
          .isEqualTo("null");
    assertThat(schemas.at("/SubscriptionResponse/required").toString())
        .contains("subscriptionId", "manageToken");
    assertThat(schemas.at("/AlertItemResponse/properties/currentMinPrice/anyOf/1/type").asText())
        .isEqualTo("null");
    var parameters =
        document.at(
            "/paths/~1api~1v1~1alerts~1subscriptions~1{subscriptionId}~1conditions/get/parameters");
    assertThat(parameters.toString()).contains("\"X-Alert-Token\"", "\"in\":\"header\"");
    // 토큰은 주소(경로·쿼리)에 들어가지 않는다.
    assertThat(document.path("paths").toString()).doesNotContain("manageToken=", "token=");
  }

  @Test
  void 저장하는_열은_정해진_것뿐이다() {
    var columns =
        jdbc.sql(
                """
            SELECT table_name || '.' || column_name FROM information_schema.columns
            WHERE table_name IN ('push_subscription', 'price_alert', 'alert_item_price')
            ORDER BY 1
            """)
            .query(String.class)
            .list();
    assertThat(columns)
        .containsExactlyInAnyOrder(
            "alert_item_price.current_min_price",
            "alert_item_price.item_id",
            "alert_item_price.item_name",
            "alert_item_price.observed_at",
            "price_alert.alert_id",
            "price_alert.armed",
            "price_alert.comparator",
            "price_alert.created_at",
            "price_alert.item_id",
            "price_alert.last_sent_at",
            "price_alert.pending_price",
            "price_alert.price",
            "price_alert.subscription_id",
            "push_subscription.auth",
            "push_subscription.created_at",
            "push_subscription.endpoint",
            "push_subscription.last_used_at",
            "push_subscription.manage_token_hash",
            "push_subscription.p256dh",
            "push_subscription.subscription_id");
  }

  private void observe(String price) {
    events.publishEvent(
        new MarketPricesObserved(
            clock.instant(),
            Duration.ofSeconds(30),
            List.of(
                new MarketPrice(
                    ITEM, "운명의 파괴석", "일반", null, 1, null, null, null, new BigDecimal(price)))));
  }

  private JsonNode subscribe(String endpoint) throws Exception {
    return data(postRaw("/api/v1/alerts/subscriptions", null, body(endpoint, UA_PUBLIC)));
  }

  private static String body(String endpoint, String p256dh) {
    return "{\"endpoint\":\""
        + endpoint
        + "\",\"keys\":{\"p256dh\":\""
        + p256dh
        + "\",\"auth\":\""
        + UA_AUTH
        + "\"}}";
  }

  private static String condition(int price) {
    return "{\"itemId\":" + ITEM + ",\"comparator\":\"AT_OR_BELOW\",\"price\":" + price + "}";
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    return request("GET", path, null, null);
  }

  private HttpResponse<String> get(String path, String token) throws Exception {
    return request("GET", path, token, null);
  }

  private HttpResponse<String> post(String path, String token, String json) throws Exception {
    return request("POST", path, token, json);
  }

  private HttpResponse<String> postRaw(String path, String token, String json) throws Exception {
    return request("POST", path, token, json);
  }

  private HttpResponse<String> request(String method, String path, String token, String json)
      throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) builder.header("X-Alert-Token", token);
    if (json != null)
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json));
    else builder.method(method, HttpRequest.BodyPublishers.noBody());
    try (var client = HttpClient.newHttpClient()) {
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
