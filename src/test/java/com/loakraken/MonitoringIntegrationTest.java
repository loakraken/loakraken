package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * 운영 감시(#143): 운영과 같이 관리 포트를 따로 열면 Prometheus는 관리 포트에서만 보이고 앱 포트에는 actuator가 없다. 요청 지연 히스토그램과 요청 상관
 * ID도 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.endpoints.web.exposure.include=health,prometheus"
    })
class MonitoringIntegrationTest {
  @LocalServerPort private int port;
  @LocalManagementPort private int managementPort;
  private final HttpClient http = HttpClient.newHttpClient();

  @Test
  void Prometheus는_관리_포트에서만_보이고_요청_지연_히스토그램을_낸다() throws Exception {
    var api = get(port, "/api/v1/markets/tabs", "web-request-0001");
    assertThat(api.statusCode()).isEqualTo(200);
    assertThat(api.headers().firstValue("X-Request-Id")).hasValue("web-request-0001");
    assertThat(get(port, "/actuator/prometheus", null).statusCode()).isEqualTo(404);
    var metrics = get(managementPort, "/actuator/prometheus", null);
    assertThat(metrics.statusCode()).isEqualTo(200);
    assertThat(metrics.body())
        .contains("http_server_requests_seconds_bucket")
        .contains("uri=\"/api/v1/markets/tabs\"");
    // 지표에 요청 ID·IP 같은 높은 기수 값이 태그로 들어가지 않는다.
    assertThat(metrics.body()).doesNotContain("web-request-0001").doesNotContain("127.0.0.1");
  }

  @Test
  void 요청_ID가_없거나_형식이_틀리면_새로_만든다() throws Exception {
    var none = get(port, "/api/v1/markets/tabs", null);
    var invalid = get(port, "/api/v1/markets/tabs", "bad id");
    assertThat(none.headers().firstValue("X-Request-Id")).get().asString().hasSize(36);
    assertThat(invalid.headers().firstValue("X-Request-Id"))
        .get()
        .asString()
        .hasSize(36)
        .isNotEqualTo("bad id");
  }

  private HttpResponse<String> get(int target, String path, String requestId) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).GET();
    if (requestId != null) request.header("X-Request-Id", requestId);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }
}
