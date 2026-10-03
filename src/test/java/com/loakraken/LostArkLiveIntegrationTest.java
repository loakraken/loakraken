package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "LOSTARK_LIVE_TEST", matches = "true")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "lostark.base-url=https://developer-lostark.game.onstove.com",
      "lostark.requests-per-minute=2",
      "lostark.max-concurrent-requests=1"
    })
class LostArkLiveIntegrationTest {
  @LocalServerPort private int port;
  @Autowired private JsonMapper mapper;
  @Autowired private JdbcClient jdbc;

  @MockitoSpyBean(name = "lostArkRestClient")
  private RestClient client;

  @Test
  void 실제_종합_조회_후_재조회는_추가_외부_호출과_저장이_없다() throws Exception {
    String name = System.getenv("LOSTARK_TEST_CHARACTER");
    assertThat(name).as("LOSTARK_TEST_CHARACTER 설정 필요").isNotBlank();
    assertThat(
            System.getenv("LOSTARK_API_KEY") != null && !System.getenv("LOSTARK_API_KEY").isBlank())
        .as("LOSTARK_API_KEY 설정 필요")
        .isTrue();
    var uri =
        URI.create(
            "http://localhost:"
                + port
                + "/api/v1/characters/search?characterName="
                + URLEncoder.encode(name, StandardCharsets.UTF_8));
    try (var http = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).GET().build();
      var first = http.send(request, HttpResponse.BodyHandlers.ofString());
      var body = mapper.readTree(first.body());
      assertThat(first.statusCode()).as("실조회 응답 코드: %s", body.path("code").asText()).isEqualTo(200);
      assertThat(body.path("code").asText()).isEqualTo("CHARACTER_SEARCH_SUCCESS");
      assertThat(body.at("/data/characterName").asText()).isEqualTo(name);
      assertThat(body.at("/data/loadout").isObject()).isTrue();
      assertThat(body.at("/data/siblings").isArray()).isTrue();
      assertThat(body.at("/data/analysis").isObject()).isTrue();
      assertThat(body.at("/data/fetchedAt").asText()).isNotBlank();
      var second = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(second.statusCode()).isEqualTo(200);
      assertThat(second.body()).isEqualTo(first.body());
      verify(client, times(2)).get();
      assertThat(
              jdbc.sql(
                      "SELECT count(*) FROM character_snapshot s JOIN characters c USING (character_id) WHERE c.character_name = :name")
                  .param("name", name)
                  .query(Integer.class)
                  .single())
          .isEqualTo(1);
      ApiContractFixture.write("character-live.json", first.body());
      var docs =
          http.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(docs.statusCode()).isEqualTo(200);
      ApiContractFixture.write("openapi-live.json", docs.body());
    }
  }
}
