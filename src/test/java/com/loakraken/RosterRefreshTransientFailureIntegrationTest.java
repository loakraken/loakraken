package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.RosterTransientPort;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 원정대 전투력 불러오기의 임시 값·'불러옴' 표시 저장소가 장애면 fail closed(503)다. 표시를 못 읽거나 못 쓰면 처리정지 대원만 다르게 보이므로 성공으로 내지
 * 않는다(B5, 풀스택 PM #490 검토).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "character.roster-combat-power.marker-secret=test-roster-marker-secret-0123456789")
class RosterRefreshTransientFailureIntegrationTest {
  private static final String SERVER = "원정장애서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean RosterTransientPort transients;

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name = :s)")
          .param("s", SERVER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '원정장애%'").update();
    var keys = new ArrayList<String>();
    for (var pattern :
        List.of(
            "character:v11:원정장애*",
            "character:v1:원정장애*",
            "roster-cp:v1:*",
            "lostark:client-limit:*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 표시를_못_읽으면_공식_호출_전에_503이다() throws Exception {
    roster("원정장애가", "원정장애정지");
    stop("원정장애정지");
    when(transients.marked(anyString(), any())).thenThrow(new QueryTimeoutException("valkey down"));

    var response = post("원정장애가", "203.0.113.80");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("CHARACTER_CACHE_UNAVAILABLE");
    verify(detailsPort, never()).fetchProfile(anyString(), anyInt());
    // 쿨다운도 잡지 않는다(저장소가 돌아오면 바로 다시 누를 수 있음).
    assertThat(redis.keys("roster-cp:v1:refresh:*")).isNullOrEmpty();
  }

  @Test
  void 처리정지_대원의_임시_값이나_표시를_못_쓰면_성공으로_내지_않고_503이다() throws Exception {
    roster("원정장애나", "원정장애멈춤");
    stop("원정장애멈춤");
    when(transients.marked(anyString(), any())).thenReturn(Set.of());
    doThrow(new QueryTimeoutException("valkey down"))
        .when(transients)
        .mark(anyString(), anyString(), any());
    when(detailsPort.fetchProfile(anyString(), anyInt()))
        .thenAnswer(invocation -> profile(invocation.getArgument(0)));

    var response = post("원정장애나", "203.0.113.81");
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("CHARACTER_CACHE_UNAVAILABLE").doesNotContain("fetched");
    // 처리정지 대원은 여전히 기록하지 않는다.
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM characters WHERE character_name = '원정장애멈춤'")
                .query(Long.class)
                .single())
        .isZero();
  }

  private void roster(String... names) {
    var siblings = new ArrayList<CharacterSibling>();
    for (int i = 0; i < names.length; i++)
      siblings.add(
          new CharacterSibling(names[i], SERVER, "바드", 70, BigDecimal.valueOf(1690 - 30L * i)));
    when(detailsPort.fetchSiblings(anyString())).thenReturn(siblings);
  }

  private void stop(String name) {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
        .param("n", name)
        .param("s", SERVER)
        .update();
  }

  private static CharacterProfile profile(String name) {
    return new CharacterProfile(
        name,
        SERVER,
        "바드",
        70,
        new BigDecimal("1690"),
        new BigDecimal("3000.00"),
        null,
        null,
        null,
        null,
        List.of());
  }

  private HttpResponse<String> post(String name, String clientAddress) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:"
                          + port
                          + "/api/v1/characters/roster-combat-power/refresh?characterName="
                          + URLEncoder.encode(name, StandardCharsets.UTF_8)))
              .header("X-Forwarded-For", clientAddress)
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
