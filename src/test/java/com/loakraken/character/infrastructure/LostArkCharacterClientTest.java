package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LostArkCharacterClientTest {
  private HttpServer server;
  private ExecutorService executor;
  private final AtomicInteger requests = new AtomicInteger();
  private volatile int status = 200;
  private volatile String body =
      """
      {"CharacterName":"모험가","ServerName":"루페온","CharacterClassName":"바드",
       "CharacterLevel":70,"ItemAvgLevel":"1,700.50","CombatPower":"12,345.67",
       "Stats":[{"Type":"치명","Value":"100","Tooltip":[]}],"FutureField":"ignored"}
      """;
  private volatile String authorization;
  private volatile String requestPath;
  private volatile long delayMillis;

  @BeforeEach
  void 설정() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          authorization = exchange.getRequestHeaders().getFirst("Authorization");
          requestPath = exchange.getRequestURI().getRawPath();
          try {
            if (delayMillis > 0) Thread.sleep(delayMillis);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 429) exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void 정리() {
    server.stop(0);
    executor.close();
  }

  @Test
  void 인증_헤더와_한글_경로를_전송하고_외부_숫자를_변환한다() {
    var result = client("test-token", Duration.ofSeconds(2)).fetch("모험가");
    assertThat(authorization).isEqualTo("Bearer test-token");
    assertThat(requestPath).contains("%EB%AA%A8%ED%97%98%EA%B0%80");
    assertThat(result.itemLevel()).isEqualByComparingTo("1700.50");
    assertThat(result.combatPower()).isEqualByComparingTo("12345.67");
    assertThat(result.stats()).hasSize(1);
    assertThat(requests).hasValue(1);
  }

  @Test
  void 빈_프로필은_미존재로_처리한다() {
    body = "null";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.CHARACTER_NOT_FOUND);
  }

  @Test
  void 필수_필드_누락은_외부_응답_오류다() {
    body = "{}";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 잘못된_JSON은_외부_응답_오류다() {
    body = "secret-invalid-json";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 키가_없으면_외부_요청을_보내지_않는다() {
    assertError(client("", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_NOT_CONFIGURED);
    assertThat(requests).hasValue(0);
  }

  @Test
  void 호출_제한은_재시도하지_않고_후속_요청도_차단한다() {
    status = 429;
    var client = client("test", Duration.ofSeconds(2));
    assertError(client, CharacterErrorCode.LOST_ARK_RATE_LIMITED);
    assertError(client, CharacterErrorCode.LOST_ARK_RATE_LIMITED);
    assertThat(requests).hasValue(1);
  }

  @Test
  void 응답_지연은_타임아웃으로_처리한다() {
    delayMillis = 500;
    assertError(client("test", Duration.ofMillis(100)), CharacterErrorCode.LOST_ARK_API_TIMEOUT);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 500, 502, 503})
  void 외부_오류를_그대로_노출하거나_재시도하지_않는다(int responseStatus) {
    status = responseStatus;
    body = "secret-upstream-error";
    assertError(client("test", Duration.ofSeconds(2)), CharacterErrorCode.LOST_ARK_API_ERROR);
    assertThat(requests).hasValue(1);
  }

  @Test
  void 미존재_응답_뒤에도_다음_요청을_처리할_수_있다() {
    var client = client("test", Duration.ofSeconds(2));
    status = 404;
    assertError(client, CharacterErrorCode.CHARACTER_NOT_FOUND);
    status = 200;
    assertThat(client.fetch("모험가").characterName()).isEqualTo("모험가");
  }

  private LostArkCharacterClient client(String key, Duration timeout) {
    var properties =
        new LostArkProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            key,
            Duration.ofSeconds(2),
            timeout,
            90,
            1,
            Duration.ofMinutes(5));
    return new LostArkCharacterClient(
        new CharacterConfiguration().lostArkRestClient(properties),
        properties,
        new LostArkRequestGate(Clock.systemUTC(), properties));
  }

  private void assertError(LostArkCharacterClient client, CharacterErrorCode code) {
    assertThatThrownBy(() -> client.fetch("모험가"))
        .isInstanceOfSatisfying(
            CharacterException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(code));
  }
}
