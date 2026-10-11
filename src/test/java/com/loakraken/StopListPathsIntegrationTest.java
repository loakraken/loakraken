package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 회귀 점검표 B5(처리정지, PM 총괄 10-12): 처리정지 목록 캐릭터가 저장·집계 경로 전부에서 빠지는지 한 묶음으로 확인한다. 같은 서버·길드에 일반 캐릭터와 처리정지
 * 캐릭터를 두고 랭킹·본인 순위·길드 순위·인기 검색·이름 자동완성·검색 기록·공개 요약 색인을 본다. 수집품 순위 응답(NOT_OBSERVED)은
 * CollectibleRankingIntegrationTest, 툴팁·공개 요약 404는 각 통합 테스트가 맡는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.guilds.enabled=true")
class StopListPathsIntegrationTest {
  private static final String SERVER = "정지경로서버";
  private static final String GUILD = "정지경로길드";
  private static final String NORMAL = "정지경로일반";
  private static final String STOPPED = "정지경로대상";
  private static final String SEARCHED = "정지경로검색";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

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
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '정지경로%'").update();
    jdbc.sql("DELETE FROM character_popular_hourly").update();
    jdbc.sql("DELETE FROM character_view_daily WHERE character_name LIKE '정지경로%'").update();
    var keys = new ArrayList<String>();
    for (var pattern : List.of("stats:v1:*", "popular:v1:*", "character:v11:정지경로*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 처리정지_캐릭터는_랭킹_본인순위_길드_인기_자동완성에서_빠진다() throws Exception {
    // 길드원 다섯 명(일반 포함) + 처리정지 한 명. 처리정지 캐릭터가 가장 높아도 빠져야 한다.
    for (int i = 0; i < 4; i++) member("정지경로길드원" + i, 1700, "3000.00");
    member(NORMAL, 1710, "3100.00");
    member(STOPPED, 1800, "4000.00");
    stop(STOPPED);
    var hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    popular(NORMAL, hour, 40);
    popular(STOPPED, hour, 90);

    var ranking = get("/api/v1/rankings?metric=COMBAT_POWER&server=" + encode(SERVER));
    assertThat(ranking.statusCode()).isEqualTo(200);
    assertThat(ranking.body()).contains(NORMAL).doesNotContain(STOPPED);
    assertThat(mapper.readTree(ranking.body()).at("/data/rows/0/characterName").asText())
        .isEqualTo(NORMAL);

    assertThat(get("/api/v1/characters/rank?characterName=" + encode(NORMAL)).statusCode())
        .isEqualTo(200);
    var rank = get("/api/v1/characters/rank?characterName=" + encode(STOPPED));
    var unknown = get("/api/v1/characters/rank?characterName=" + encode("정지경로없음"));
    assertThat(rank.statusCode()).isEqualTo(404).isEqualTo(unknown.statusCode());

    var guilds = data(get("/api/v1/rankings/guilds?server=" + encode(SERVER)));
    assertThat(guilds.at("/rows/0/guildName").asText()).isEqualTo(GUILD);
    assertThat(guilds.at("/rows/0/memberCount").asLong()).isEqualTo(5);
    assertThat(guilds.at("/rows/0/itemLevelAvg").decimalValue()).isEqualByComparingTo("1702.00");

    var popularList = get("/api/v1/characters/popular");
    assertThat(popularList.statusCode()).isEqualTo(200);
    assertThat(names(mapper.readTree(popularList.body()).path("data"))).doesNotContain(STOPPED);

    var suggest = data(get("/api/v1/characters/suggest?q=" + encode("정지경로")));
    assertThat(names(suggest)).contains(NORMAL).doesNotContain(STOPPED);
  }

  @Test
  void 처리정지_캐릭터는_검색해도_기록을_남기지_않고_공개_요약은_색인하지_않는다() throws Exception {
    when(detailsPort.fetch(anyString())).thenReturn(character(SEARCHED));
    stop(SEARCHED);
    var search = get("/api/v1/characters/search?characterName=" + encode(SEARCHED));
    assertThat(search.statusCode()).isEqualTo(200);
    Thread.sleep(1000); // 조회수·인기는 비동기로 쓴다.
    assertThat(count("SELECT COUNT(*) FROM characters WHERE character_name = :n")).isZero();
    assertThat(
            count(
                "SELECT COALESCE(SUM(view_count), 0) FROM character_view_daily WHERE character_name = :n"))
        .isZero();
    assertThat(
            count(
                "SELECT COALESCE(SUM(unique_views), 0) FROM character_popular_hourly WHERE character_name = :n"))
        .isZero();
    var summary = data(get("/api/v1/characters/public-summary?characterName=" + encode(SEARCHED)));
    assertThat(summary.path("indexable").asBoolean()).isFalse();
  }

  private void member(String name, int itemLevel, String combatPower) {
    var now = Instant.now().atOffset(ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              combat_power, guild_name, last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, '블레이드', :level, :cp, :guild, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", SERVER)
        .param("level", BigDecimal.valueOf(itemLevel))
        .param("cp", new BigDecimal(combatPower))
        .param("guild", GUILD)
        .param("at", now)
        .update();
  }

  private void stop(String name) {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
        .param("n", name)
        .param("s", SERVER)
        .update();
  }

  private void popular(String name, Instant hour, int views) {
    jdbc.sql(
            """
            INSERT INTO character_popular_hourly (character_name, server_name, hour_bucket, unique_views)
            VALUES (:n, :s, :h, :v)
            """)
        .param("n", name)
        .param("s", SERVER)
        .param("h", hour.atOffset(ZoneOffset.UTC))
        .param("v", views)
        .update();
  }

  private long count(String sql) {
    return jdbc.sql(sql).param("n", SEARCHED).query(Long.class).single();
  }

  private FetchedCharacter character(String name) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "바드",
            70,
            new BigDecimal("1700"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null);
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
  }

  private static List<String> names(JsonNode data) {
    var names = new ArrayList<String>();
    for (var field : List.of("items", "rows"))
      data.path(field).forEach(item -> names.add(item.path("characterName").asText()));
    return names;
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
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
