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
import java.time.LocalDate;
import java.time.ZoneId;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PopularCharactersIntegrationTest {
  private static final String SERVER = "인기테스트서버";
  private static final String SEARCHED = "인기검색캐";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = new ArrayList<String>();
    for (var pattern : List.of("popular:v1:*", "character:v11:" + SEARCHED + ":*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM character_popular_hourly").update();
    jdbc.sql("DELETE FROM public_exclusion").update();
    jdbc.sql("DELETE FROM character_view_daily WHERE character_name = :n")
        .param("n", SEARCHED)
        .update();
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name = :s)")
          .param("s", SERVER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  @Test
  void 같은_클라이언트는_하루_한_번만_세고_원본_IP는_남기지_않는다() throws Exception {
    when(detailsPort.fetchArmory(anyString())).thenReturn(character());
    search("203.0.113.7");
    redis.delete("character:v11:" + SEARCHED + ":armory");
    search("203.0.113.7");
    search("198.51.100.9");
    long views = 0;
    for (int i = 0; i < 100 && views < 2; i++) {
      views =
          jdbc.sql(
                  "SELECT COALESCE(SUM(unique_views), 0) FROM character_popular_hourly WHERE character_name = :n")
              .param("n", SEARCHED)
              .query(Long.class)
              .single();
      Thread.sleep(50);
    }
    // 검색 3회, 고유 클라이언트 2명
    assertThat(views).isEqualTo(2);
    var seen = "popular:v1:seen:" + LocalDate.now(ZoneId.of("Asia/Seoul"));
    var members = redis.opsForSet().members(seen);
    assertThat(members).hasSize(2);
    assertThat(String.join(",", members)).doesNotContain("203.0.113.7", "198.51.100.9", SEARCHED);
    assertThat(redis.getExpire(seen)).isBetween(1L, 26L * 3600);
    // 반복 포함 조회수(F-8)는 그대로 3이다.
    long panelViews = 0;
    for (int i = 0; i < 100 && panelViews < 3; i++) {
      panelViews =
          jdbc.sql(
                  "SELECT COALESCE(SUM(view_count), 0) FROM character_view_daily WHERE character_name = :n")
              .param("n", SEARCHED)
              .query(Long.class)
              .single();
      Thread.sleep(50);
    }
    assertThat(panelViews).isEqualTo(3);
  }

  @Test
  void 기간_합계_5회_이상만_올리고_제외_목록과_24시간_경계를_지킨다() throws Exception {
    var hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    insert("인기F", hour, 15);
    insert("인기A", hour, 10);
    insert("인기B", hour.minus(1, ChronoUnit.HOURS), 5);
    insert("인기B", hour, 1);
    insert("인기G", hour.minus(23, ChronoUnit.HOURS), 5); // 24시간 첫 버킷
    insert("인기H", hour.minus(24, ChronoUnit.HOURS), 5); // 24시간 밖, 7일 안
    insert("인기C", hour, 4); // 5회 미만
    insert("인기E", hour, 50); // 제외 목록
    insert("인기D", hour.minus(30, ChronoUnit.HOURS), 20);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('인기E', NULL, 'ABUSE')")
        .update();
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level, last_synced_at)
        VALUES (:id, '인기F', :s, '바드', 1780, now())
        """)
        .param("id", UUID.randomUUID())
        .param("s", SERVER)
        .update();

    var day = data("/api/v1/characters/popular");
    assertThat(day.path("period").asText()).isEqualTo("24h");
    assertThat(day.path("sampleStatus").asText()).isEqualTo("READY");
    assertThat(day.path("minUniqueViews").asInt()).isEqualTo(5);
    assertThat(day.path("ruleVersion").asText()).isEqualTo("popular-v1");
    assertThat(names(day)).containsExactly("인기F", "인기A", "인기B", "인기G");
    assertThat(day.at("/items/0/rank").asInt()).isEqualTo(1);
    assertThat(day.at("/items/0/className").asText()).isEqualTo("바드");
    assertThat(day.at("/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1780");
    assertThat(day.at("/items/1/className").isNull()).isTrue();
    // 조회 수 숫자는 응답에 없다.
    assertThat(day.at("/items/0").has("views")).isFalse();
    assertThat(day.at("/items/0").has("uniqueViews")).isFalse();
    ApiContractFixture.write("characters-popular.json", get("/api/v1/characters/popular").body());

    var week = data("/api/v1/characters/popular?period=7d&limit=5");
    assertThat(names(week)).containsExactly("인기D", "인기F", "인기A", "인기B", "인기G");

    // 10분 캐시: 데이터를 지워도 같은 결과
    jdbc.sql("DELETE FROM character_popular_hourly").update();
    assertThat(names(data("/api/v1/characters/popular")))
        .containsExactly("인기F", "인기A", "인기B", "인기G");
  }

  @Test
  void 표본이_부족하면_수집_중이고_목록을_비운다() throws Exception {
    var hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    insert("인기A", hour, 10);
    var day = data("/api/v1/characters/popular");
    assertThat(day.path("sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(day.path("items").size()).isZero();
  }

  @Test
  void 잘못된_조건은_400이다() throws Exception {
    for (var query : List.of("period=1d", "limit=0", "limit=51"))
      assertThat(get("/api/v1/characters/popular?" + query).statusCode()).as(query).isEqualTo(400);
  }

  private void insert(String name, Instant hour, int views) {
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

  private static List<String> names(JsonNode data) {
    var names = new ArrayList<String>();
    data.path("items").forEach(item -> names.add(item.path("characterName").asText()));
    return names;
  }

  private void search(String clientAddress) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/characters/search?includeSiblings=false&characterName="
                              + URLEncoder.encode(SEARCHED, StandardCharsets.UTF_8)))
                  // 테스트 클라이언트(127.0.0.1)는 신뢰 프록시라 이 헤더가 원격 주소가 된다.
                  .header("X-Forwarded-For", clientAddress)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
    }
  }

  private FetchedCharacter character() {
    var profile =
        new CharacterProfile(
            SEARCHED,
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
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }

  private JsonNode data(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
