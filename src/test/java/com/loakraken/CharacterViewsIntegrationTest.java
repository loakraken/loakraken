package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "crit-speed.enabled=true")
class CharacterViewsIntegrationTest {
  private static final String NAME = "조회수캐릭터";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var key : List.of("summary", "armory")) redis.delete("character:v11:" + NAME + ":" + key);
    redis.delete("character:v1:" + NAME + ":siblings");
    redis.delete("character:v1:" + NAME + ":missing");
    jdbc.sql("DELETE FROM character_view_daily WHERE character_name = :name")
        .param("name", NAME)
        .update();
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
          .param("name", NAME)
          .update();
    jdbc.sql("DELETE FROM characters WHERE character_name = :name").param("name", NAME).update();
  }

  @Test
  void 검색_성공마다_오늘_조회수를_세고_계산_API의_내부_조회는_세지_않는다() throws Exception {
    when(detailsPort.fetchArmory(anyString())).thenReturn(character());
    for (int i = 0; i < 2; i++)
      assertThat(
              get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(NAME))
                  .statusCode())
          .isEqualTo(200);
    var crit =
        send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/characters/crit-speed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"characterName\":\"" + NAME + "\"}"))
                .build());
    assertThat(crit.statusCode()).isEqualTo(200);

    // 기록은 비동기라 반영될 때까지 잠시 기다린다.
    HttpResponse<String> response = null;
    for (int i = 0; i < 50; i++) {
      response = get("/api/v1/characters/views?characterName=" + encode(NAME));
      if (mapper.readTree(response.body()).at("/data/total").asLong() >= 2) break;
      Thread.sleep(100);
    }
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    assertThat(json.path("code").asText()).isEqualTo("CHARACTER_VIEWS_SUCCESS");
    assertThat(json.at("/data/days").size()).isEqualTo(112);
    assertThat(json.at("/data/from").asText()).isEqualTo(today.minusDays(111).toString());
    assertThat(json.at("/data/to").asText()).isEqualTo(today.toString());
    assertThat(json.at("/data/days/111/count").asLong()).isEqualTo(2);
    assertThat(json.at("/data/days/0/count").asLong()).isZero();
    assertThat(json.at("/data/rangeTotal").asLong()).isEqualTo(2);
    assertThat(json.at("/data/total").asLong()).isEqualTo(2);
    ApiContractFixture.write("character-views.json", response.body());

    var oneDay =
        mapper.readTree(
            get("/api/v1/characters/views?days=1&characterName=" + encode(NAME)).body());
    assertThat(oneDay.at("/data/days").size()).isEqualTo(1);
  }

  @Test
  void 조회_기록이_없는_캐릭터는_0으로_채운다() throws Exception {
    var json =
        mapper.readTree(
            get("/api/v1/characters/views?days=7&characterName=" + encode(NAME)).body());
    assertThat(json.at("/data/days").size()).isEqualTo(7);
    assertThat(json.at("/data/total").asLong()).isZero();
    verifyNoInteractions(detailsPort);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "days=0&characterName=a",
        "days=367&characterName=a",
        "characterName=a-b",
        "days=7"
      })
  void 잘못된_조건은_400이다(String query) throws Exception {
    assertThat(get("/api/v1/characters/views?" + query).statusCode()).as(query).isEqualTo(400);
  }

  private FetchedCharacter character() {
    var profile =
        new CharacterProfile(
            NAME,
            "카단",
            "블레이드",
            70,
            new BigDecimal("1770"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null);
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build());
  }

  private HttpResponse<String> send(HttpRequest request) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
