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

/** 길드 탭 집계는 길드 랭킹 플래그(기본 꺼짐)를 따른다. 꺼져 있으면 경로가 없다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuildDetailFlagOffIntegrationTest {
  @LocalServerPort int port;

  @Test
  void 길드_랭킹이_꺼져_있으면_길드_탭은_404다() throws Exception {
    var path =
        "/api/v1/guilds/"
            + URLEncoder.encode("탭서버", StandardCharsets.UTF_8)
            + "/"
            + URLEncoder.encode("새벽길드", StandardCharsets.UTF_8);
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(404);
    }
  }
}
