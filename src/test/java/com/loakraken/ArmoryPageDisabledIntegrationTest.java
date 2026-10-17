package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.loakraken.character.application.ArmoryPagePort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** 전투정보실 페이지 정보(#531) 기본값: 꺼져 있으면 캐시 여부와 상관없이 200에 값 null이고 공식 페이지를 부르지 않는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArmoryPageDisabledIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean ArmoryPagePort pages;

  @Test
  void 꺼져_있으면_200에_값이_null이다() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/api/v1/characters/armory-page?characterName="
                                + URLEncoder.encode("꺼짐캐릭", StandardCharsets.UTF_8)))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-armory-page-disabled.json", response.body());
    var data = new JsonMapper().readTree(response.body()).path("data");
    assertThat(data.path("characterName").asText()).isEqualTo("꺼짐캐릭");
    assertThat(data.path("enabled").asBoolean()).isFalse();
    assertThat(data.path("source").isNull()).isTrue();
    assertThat(data.path("fetchedAt").isNull()).isTrue();
    assertThat(data.path("lifeSkills").isNull()).isTrue();

    // 스킬 코드(#532)도 꺼짐: 200에 값 null.
    var code =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/api/v1/characters/armory-page/skill-code?characterName="
                                + URLEncoder.encode("꺼짐캐릭", StandardCharsets.UTF_8)))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(code.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-skill-code-disabled.json", code.body());
    var codeData = new JsonMapper().readTree(code.body()).path("data");
    assertThat(codeData.path("enabled").asBoolean()).isFalse();
    assertThat(codeData.path("skillCode").isNull()).isTrue();
    assertThat(codeData.path("fetchedAt").isNull()).isTrue();
    verifyNoInteractions(pages);
  }
}
