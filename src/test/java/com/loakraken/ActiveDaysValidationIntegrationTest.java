package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * activeDays는 7·30·90만 받는다. 그 밖의 값은 경로마다 400 INVALID_REQUEST다(조건 생성자 예외가 500이 되던 문제). 값이 맞으면 400이
 * 아니다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ranking.guilds.enabled=true",
      "ranking.collectibles.enabled=true",
      "ranking.rosters.enabled=true",
      "ranking.titles.enabled=true"
    })
class ActiveDaysValidationIntegrationTest {
  @LocalServerPort int port;
  private final JsonMapper mapper = new JsonMapper();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/rankings/guilds?",
        "/api/v1/rankings/collectibles?",
        "/api/v1/rankings/collectibles/standing?characterName=NAME&",
        "/api/v1/collectibles/ownership?",
        "/api/v1/rankings/rosters?",
        "/api/v1/titles/equipped?",
        "/api/v1/guilds/SERVER/GUILD?",
        "/api/v1/stats/summary?"
      })
  void 허용하지_않는_activeDays는_400이고_허용한_값은_400이_아니다(String path) throws Exception {
    var base =
        path.replace("NAME", encode("검증캐릭터"))
            .replace("SERVER", encode("검증서버"))
            .replace("GUILD", encode("검증길드"));
    for (var bad : new String[] {"5", "0", "-7", "365"}) {
      var response = get(base + "activeDays=" + bad);
      assertThat(response.statusCode()).as(base + bad + " " + response.body()).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(base + bad)
          .isEqualTo("INVALID_REQUEST");
    }
    for (var good : new String[] {"7", "30", "90"})
      assertThat(get(base + "activeDays=" + good).statusCode()).as(base + good).isNotIn(400, 500);
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
