package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인기 길드(M21): 길드원 캐릭터 고유 조회 합(인기 캐릭터와 같은 원천)으로 차례를 매기고 숫자는 주지 않는다. 처리정지 캐릭터 조회는 합에서 빠지고 길드는 남는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.guilds.enabled=true")
class PopularGuildsIntegrationTest {
  private static final String SERVER = "인기길드서버";
  private static final String OTHER = "인기길드다른서버";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = redis.keys("popular:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM character_popular_hourly").update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '인기길%'").update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :o)")
        .param("s", SERVER)
        .param("o", OTHER)
        .update();
  }

  @Test
  void 길드원_고유_조회_합으로_차례를_매기고_숫자는_주지_않는다() throws Exception {
    var hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    // 가길드 10 + 5 = 15, 마길드 15(같은 합 → 길드 이름순), 다른 서버 라길드 8.
    member(SERVER, "가길드", "인기길가1", hour, 10);
    member(SERVER, "가길드", "인기길가2", hour, 5);
    member(SERVER, "마길드", "인기길마1", hour, 15);
    member(OTHER, "라길드", "인기길라1", hour, 8);
    // 나길드: 처리정지 캐릭터 조회 20은 합에서 빠지고 남은 6으로 남는다(B5).
    member(SERVER, "나길드", "인기길나정지", hour, 20);
    member(SERVER, "나길드", "인기길나1", hour, 6);
    stop("인기길나정지");
    // 다길드: 합 4라 최소 기준(24h 5) 미만.
    member(SERVER, "다길드", "인기길다1", hour, 4);
    // 바길드: 24시간 밖(7일 안)이라 24h에는 없다.
    member(SERVER, "바길드", "인기길바1", hour.minus(30, ChronoUnit.HOURS), 20);
    // 길드 없는 캐릭터 조회는 어느 길드에도 들어가지 않는다.
    member(SERVER, null, "인기길무소속", hour, 9);

    var response = get("/api/v1/guilds/popular");
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("guilds-popular.json", response.body());
    SiteNames.assertAbsent(response.body());
    // 숫자(조회 수·최소 기준)는 응답에 없다.
    assertThat(response.body()).doesNotContain("views", "minUniqueViews", "인기길나정지");
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("period").asText()).isEqualTo("24h");
    assertThat(data.path("sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("popular-guild-v1");
    var items = data.path("items");
    assertThat(items.size()).isEqualTo(4);
    assertThat(row(items.get(0))).isEqualTo("1 " + SERVER + " 가길드");
    assertThat(row(items.get(1))).isEqualTo("2 " + SERVER + " 마길드");
    assertThat(row(items.get(2))).isEqualTo("3 " + OTHER + " 라길드");
    assertThat(row(items.get(3))).isEqualTo("4 " + SERVER + " 나길드");

    // 서버를 고르면 그 서버 길드만(차례는 그 안에서).
    var other = data("/api/v1/guilds/popular?server=" + encode(OTHER));
    assertThat(other.path("items").size()).isEqualTo(1);
    assertThat(row(other.at("/items/0"))).isEqualTo("1 " + OTHER + " 라길드");

    // 7일: 바길드 20이 1위로 들어오고, 최소 기준 15라 나길드(6)는 빠진다.
    var week = data("/api/v1/guilds/popular?period=7d");
    assertThat(week.path("period").asText()).isEqualTo("7d");
    assertThat(row(week.at("/items/0"))).isEqualTo("1 " + SERVER + " 바길드");
    assertThat(week.path("items").size()).isEqualTo(3);

    // limit
    assertThat(data("/api/v1/guilds/popular?limit=2").path("items").size()).isEqualTo(2);
  }

  @Test
  void 기간_전체_고유_조회가_30_미만이면_COLLECTING이고_빈_목록이다() throws Exception {
    var hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
    member(SERVER, "가길드", "인기길가1", hour, 10);
    member(SERVER, "가길드", "인기길가2", hour, 5);
    var data = data("/api/v1/guilds/popular");
    assertThat(data.path("sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("items").size()).isZero();
  }

  @Test
  void 입력이_틀리면_400이다() throws Exception {
    assertThat(get("/api/v1/guilds/popular?period=DAY").statusCode()).isEqualTo(400);
    assertThat(get("/api/v1/guilds/popular?limit=51").statusCode()).isEqualTo(400);
    assertThat(get("/api/v1/guilds/popular?server=" + encode("서버 이름")).statusCode()).isEqualTo(400);
  }

  private static String row(JsonNode item) {
    return item.path("rank").asInt()
        + " "
        + item.path("serverName").asText()
        + " "
        + item.path("guildName").asText();
  }

  private void member(String server, String guild, String name, Instant hour, int views) {
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              guild_name, last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, '바드', 1700, :guild, now(), now())
            ON CONFLICT (server_name, character_name) DO NOTHING
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("guild", guild)
        .update();
    jdbc.sql(
            """
            INSERT INTO character_popular_hourly (character_name, server_name, hour_bucket, unique_views)
            VALUES (:name, :server, :hour, :views)
            """)
        .param("name", name)
        .param("server", server)
        .param("hour", hour.atOffset(ZoneOffset.UTC))
        .param("views", views)
        .update();
  }

  private void stop(String name) {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, NULL, 'USER_REQUEST')")
        .param("n", name)
        .update();
  }

  private JsonNode data(String pathAndQuery) throws Exception {
    var response = get(pathAndQuery);
    assertThat(response.statusCode()).as(pathAndQuery).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
