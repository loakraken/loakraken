package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

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

/** 스펙업 가이드 플래그가 꺼져 있으면 캐시 없음과 다른 코드(SPEC_UP_DISABLED, 404)다(#315, PM-프론트 16:27 ⑦). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpecUpDisabledIntegrationTest {
  @LocalServerPort int port;

  @Test
  void 꺼져_있으면_SPEC_UP_DISABLED다() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/api/v1/characters/spec-up?characterName="
                                + URLEncoder.encode("스펙꺼짐", StandardCharsets.UTF_8)))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(404);
    ApiContractFixture.write("character-spec-up-disabled.json", response.body());
    assertThat(response.body()).contains("SPEC_UP_DISABLED").doesNotContain("CHARACTER_NOT_CACHED");
    // 목표 경로(L31)도 같은 플래그를 따른다.
    var goal =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/api/v1/characters/spec-up/goal?targetCombatPower=3100&characterName="
                                + URLEncoder.encode("스펙꺼짐", StandardCharsets.UTF_8)))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(goal.statusCode()).isEqualTo(404);
    assertThat(goal.body()).contains("SPEC_UP_DISABLED");
  }
}
