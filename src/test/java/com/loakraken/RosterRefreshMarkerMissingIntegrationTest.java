package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loakraken.character.application.CharacterDetailsPort;
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

/** 표시 비밀값이 없으면 원정대 전투력 불러오기를 열지 않는다(처리정지 대원만 매번 대상이 되어 드러나는 것을 막음, B5). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "character.roster-combat-power.marker-secret=")
class RosterRefreshMarkerMissingIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean CharacterDetailsPort detailsPort;

  @Test
  void 표시_비밀값이_없으면_불러오기는_404이고_공식_호출이_없다() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/characters/roster-combat-power/refresh?characterName="
                              + URLEncoder.encode("원정전투비밀없음", StandardCharsets.UTF_8)))
                  .header("X-Forwarded-For", "203.0.113.90")
                  .POST(HttpRequest.BodyPublishers.noBody())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(404);
      assertThat(response.body()).contains("ROSTER_REFRESH_UNAVAILABLE");
    }
    verify(detailsPort, never()).fetchSiblings(anyString());
    verify(detailsPort, never()).fetchProfile(anyString(), anyInt());
  }
}
