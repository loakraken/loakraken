package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/** 첫 운영 릴리스 플래그(PM-백엔드 10-10 09:00): 끄면 경로와 OpenAPI 계약에서 모두 빠진다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gem.enabled=false",
      "crit-speed.enabled=false",
      "receipt.enabled=false",
      "combat-power-sim.enabled=false"
    })
class FeatureFlagIntegrationTest {
  @LocalServerPort int port;
  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void 꺼진_젬_가공과_치명_계산은_404이고_계약에_없다() throws Exception {
    assertThat(post("/api/v1/gems/synthesis-plans").statusCode()).isEqualTo(404);
    assertThat(post("/api/v1/characters/crit-speed").statusCode()).isEqualTo(404);
    assertThat(post("/api/v1/characters/combat-power/simulate").statusCode()).isEqualTo(404);
    var docs =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
            HttpResponse.BodyHandlers.ofString());
    var paths = new JsonMapper().readTree(docs.body()).path("paths");
    assertThat(paths.has("/api/v1/gems/synthesis-plans")).isFalse();
    assertThat(paths.has("/api/v1/characters/crit-speed")).isFalse();
    assertThat(paths.has("/api/v1/characters/receipt")).isFalse();
    assertThat(paths.has("/api/v1/characters/combat-power/simulate")).isFalse();
    assertThat(paths.has("/api/v1/rankings/collectibles")).isFalse();
    // 다른 캐릭터 경로는 그대로다.
    assertThat(paths.has("/api/v1/characters/search")).isTrue();
  }

  private HttpResponse<String> post(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
