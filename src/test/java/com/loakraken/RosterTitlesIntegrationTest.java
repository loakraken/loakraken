package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RosterTitlesIntegrationTest {
  private static final String SERVER = "칭호테스트서버";
  private static final String MAIN = "칭호본캐";
  private static final String ALT = "칭호부캐";
  private static final String EMPTY = "칭호신규";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @Autowired CharacterHistoryPort history;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var name : List.of(MAIN, ALT, EMPTY)) redis.delete("character:v1:" + name + ":siblings");
    for (var table : List.of("character_title_observation", "character_observation"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name IN (:s))")
          .param("s", List.of(SERVER, "칭호다른서버"))
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s)")
        .param("s", List.of(SERVER, "칭호다른서버"))
        .update();
  }

  @Test
  void 원정대가_장착한_칭호를_처음_확인한_순서로_합친다() throws Exception {
    var roster =
        List.of(
            sibling(MAIN, SERVER),
            sibling(ALT, SERVER),
            sibling(EMPTY, SERVER),
            sibling("칭호다른서버캐", "칭호다른서버"));
    for (var name : List.of(MAIN, ALT, EMPTY))
      when(detailsPort.fetchSiblings(name)).thenReturn(roster);
    var t1 = Instant.parse("2026-10-01T00:00:00Z");
    var t2 = Instant.parse("2026-10-03T00:00:00Z");
    var t3 = Instant.parse("2026-10-05T00:00:00Z");
    history.record(profile(ALT, SERVER, "별을 제패한 자"), t1);
    history.record(profile(MAIN, SERVER, "이클립스"), t2);
    history.record(profile(MAIN, SERVER, "별을 제패한 자"), t3);
    history.record(profile("칭호다른서버캐", "칭호다른서버", "다른 서버 칭호"), t1);

    var response = get("/api/v1/characters/titles?characterName=" + encode(MAIN));
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("title-record-v1");
    assertThat(data.path("rosterCharacters").asInt()).isEqualTo(3);
    assertThat(data.path("observedCharacters").asInt()).isEqualTo(2);
    assertThat(data.path("titles").size()).isEqualTo(2);
    var first = data.at("/titles/0");
    assertThat(first.path("title").asText()).isEqualTo("별을 제패한 자");
    assertThat(first.path("observedOrder").asInt()).isEqualTo(1);
    assertThat(first.path("recordedBy").asText()).isEqualTo(ALT);
    assertThat(first.path("firstObservedAt").asText()).startsWith("2026-10-01");
    // KST 날짜(#175)
    assertThat(first.path("firstObservedDate").asText()).isEqualTo("2026-10-01");
    assertThat(first.path("lastObservedAt").asText()).startsWith("2026-10-05");
    assertThat(first.path("source").asText()).isEqualTo("OBSERVED");
    assertThat(data.at("/titles/1/title").asText()).isEqualTo("이클립스");
    assertThat(response.body()).doesNotContain("acquiredAt", "다른 서버 칭호");
    ApiContractFixture.write("character-titles.json", response.body());

    var empty =
        mapper.readTree(get("/api/v1/characters/titles?characterName=" + encode(EMPTY)).body());
    assertThat(empty.at("/data/titles").size()).isEqualTo(2);
  }

  @Test
  void 제외_목록에_든_원정대_동료의_저장된_칭호는_보이지_않는다() throws Exception {
    var roster = List.of(sibling(MAIN, SERVER), sibling(ALT, SERVER));
    for (var name : List.of(MAIN, ALT)) when(detailsPort.fetchSiblings(name)).thenReturn(roster);
    history.record(profile(ALT, SERVER, "별을 제패한 자"), Instant.parse("2026-10-01T00:00:00Z"));
    history.record(profile(MAIN, SERVER, "이클립스"), Instant.parse("2026-10-03T00:00:00Z"));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
        .param("n", ALT)
        .param("s", SERVER)
        .update();
    try {
      var data =
          mapper
              .readTree(get("/api/v1/characters/titles?characterName=" + encode(MAIN)).body())
              .path("data");
      assertThat(data.path("observedCharacters").asInt()).isEqualTo(1);
      assertThat(data.path("titles").size()).isEqualTo(1);
      assertThat(data.at("/titles/0/title").asText()).isEqualTo("이클립스");
      assertThat(data.toString()).doesNotContain(ALT).doesNotContain("별을 제패한 자");
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE character_name = :n").param("n", ALT).update();
    }
  }

  @Test
  void 잘못된_이름은_400이다() throws Exception {
    assertThat(get("/api/v1/characters/titles?characterName=a-b").statusCode()).isEqualTo(400);
  }

  private static CharacterSibling sibling(String name, String server) {
    return new CharacterSibling(name, server, "바드", 70, new BigDecimal("1700"));
  }

  private static CharacterProfile profile(String name, String server, String title) {
    return new CharacterProfile(
        name,
        server,
        "바드",
        70,
        new BigDecimal("1700"),
        new BigDecimal("3000"),
        200,
        null,
        title,
        null,
        List.of());
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
