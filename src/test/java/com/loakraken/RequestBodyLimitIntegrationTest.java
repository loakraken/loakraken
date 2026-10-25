package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * 요청 본문 상한(실제 Tomcat). 길이를 알린 본문은 읽기 전에, 길이를 알리지 않은(chunked) 본문은 상한까지 읽은 뒤 413 REQUEST_TOO_LARGE다.
 * 상한 안의 본문은 지금처럼 처리된다. 공식 호출은 없다(본문 검증에서 끝나는 요청만 보낸다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "http.max-request-body=1KB")
class RequestBodyLimitIntegrationTest {
  private static final String PATH = "/api/v1/markets/material-prices";
  @LocalServerPort private int port;
  private final HttpClient http = HttpClient.newHttpClient();

  /** 상한(1KB)을 넘는, 문법은 맞는 JSON. */
  private static byte[] big() {
    return ("{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"names\":[\"" + "가".repeat(1000) + "\"]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private HttpResponse<String> post(HttpRequest.BodyPublisher body) throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
            .header("Content-Type", "application/json")
            .POST(body)
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void 길이를_알린_본문이_상한을_넘으면_413이다() throws Exception {
    var response = post(HttpRequest.BodyPublishers.ofByteArray(big()));
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).contains("\"code\":\"REQUEST_TOO_LARGE\"");
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("application/json");
  }

  @Test
  void 길이를_알리지_않은_본문도_상한을_넘으면_413이다() throws Exception {
    var body = big();
    var response =
        post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(response.body()).contains("\"code\":\"REQUEST_TOO_LARGE\"");
  }

  @Test
  void 상한_안의_본문은_지금처럼_검증한다() throws Exception {
    // 이름이 빈 요청은 본문 검증에서 400이다(공식 호출 없음). 길이를 알린 것과 알리지 않은 것 모두 같다.
    var small =
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"names\":[]}".getBytes(StandardCharsets.UTF_8);
    assertThat(post(HttpRequest.BodyPublishers.ofByteArray(small)).statusCode()).isEqualTo(400);
    assertThat(
            post(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(small)))
                .statusCode())
        .isEqualTo(400);
  }
}
