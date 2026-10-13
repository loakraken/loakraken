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

/** 인기 길드(M21)는 길드 랭킹 플래그가 꺼져 있으면(인기 캐릭터만 켜짐) 경로가 없다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PopularGuildsGuildFlagOffIntegrationTest {
  @LocalServerPort int port;

  @Test
  void 길드_랭킹이_꺼져_있으면_인기_길드는_404다() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/guilds/popular"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(404);
  }
}
