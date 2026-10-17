package com.loakraken.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** 시세 알림(M33)은 플래그를 켜도 VAPID 키가 없으면 경로가 없다(404). 기본은 플래그도 꺼짐이다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "alerts.push.enabled=true")
class AlertKeysMissingIntegrationTest {
  @LocalServerPort int port;

  @Test
  void 키가_없으면_시세_알림_경로가_없다() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      for (var path : new String[] {"/api/v1/alerts/vapid-public-key", "/api/v1/alerts/items"})
        assertThat(
                client
                    .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString())
                    .statusCode())
            .as(path)
            .isEqualTo(404);
    }
  }
}
