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

/**
 * 길드 랭킹 API는 길드 탭·인기 길드와 따로 켠다(#581). 길드 탭 스위치만 켜고 랭킹 스위치(기본 꺼짐)는 그대로면, 랭킹 경로는 다른 꺼진 기능처럼 없고 길드 탭은
 * 열린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.guilds.enabled=true")
class GuildRankingFlagOffIntegrationTest {
  @LocalServerPort int port;

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  void 길드_탭만_켜면_길드_랭킹은_404이고_길드_탭은_열린다() throws Exception {
    var ranking = get("/api/v1/rankings/guilds");
    assertThat(ranking.statusCode()).isEqualTo(404);
    assertThat(ranking.body()).doesNotContain("STATS_GUILD_NOT_OBSERVED");
    var guildTab =
        "/api/v1/guilds/"
            + URLEncoder.encode("탭서버", StandardCharsets.UTF_8)
            + "/"
            + URLEncoder.encode("새벽길드", StandardCharsets.UTF_8);
    // 길드 탭 경로는 열려 있어 모르는 길드에 길드 탭 자신의 404(관측 없음)를 준다.
    var tab = get(guildTab);
    assertThat(tab.statusCode()).isEqualTo(404);
    assertThat(tab.body()).contains("STATS_GUILD_NOT_OBSERVED");
  }
}
