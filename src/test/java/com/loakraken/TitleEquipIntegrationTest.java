package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 칭호 장착 확인 비율(#311): 분모 = 프로필을 관측한 원정대, 분자 = 그 칭호 장착이 확인된 원정대(같은 원정대 여러 캐릭터는 1번), 공개 제외·기간 밖 제외, 표본
 * 30 미만 참고값. 표본 4개(정상·빈 결과·30 미만·같은 원정대 중복)를 build/generated에 남긴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.titles.enabled=true")
class TitleEquipIntegrationTest {
  private static final String SERVER = "칭호서버";
  private static final String BIG_SERVER = "칭호표본서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :b)")
        .param("s", SERVER)
        .param("b", BIG_SERVER)
        .update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '칭호%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 관측_원정대_중_그_칭호_장착이_확인된_원정대_수와_비율을_준다() throws Exception {
    // 원정대 A: 두 캐릭터가 같은 칭호 → 원정대는 1번만 센다.
    var a = UUID.randomUUID();
    character(SERVER, "칭호가", a, "빛을 꺼트리는 자", Instant.now());
    character(SERVER, "칭호가둘", a, "빛을 꺼트리는 자", Instant.now());
    // 원정대 B: 다른 칭호.
    var b = UUID.randomUUID();
    character(SERVER, "칭호나", b, "혼돈의 지배자", Instant.now());
    // 원정대 C: 칭호 없음(분모에만).
    character(SERVER, "칭호다", UUID.randomUUID(), null, Instant.now());
    // 원정대 D: 공개 제외 캐릭터뿐 → 분모·분자에서 빠진다.
    character(SERVER, "칭호제외", UUID.randomUUID(), "빛을 꺼트리는 자", Instant.now());
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('칭호제외', NULL, 'USER_REQUEST')")
        .update();
    // 원정대 E: 관측이 오래됨 → 빠진다.
    character(
        SERVER, "칭호옛날", UUID.randomUUID(), "빛을 꺼트리는 자", Instant.now().minus(Duration.ofDays(40)));

    var response = get("/api/v1/titles/equipped?server=" + encode(SERVER));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("titles-equipped-collecting.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(3);
    assertThat(data.at("/meta/populationUnit").asText()).isEqualTo("ROSTER");
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("titles").size()).isEqualTo(2);
    assertThat(data.at("/titles/0/title").asText()).isEqualTo("빛을 꺼트리는 자");
    assertThat(data.at("/titles/0/equippedRosters").asLong()).isEqualTo(1);
    assertThat(data.at("/titles/0/equippedPercent").decimalValue()).isEqualByComparingTo("33.33");
    assertThat(data.at("/titles/1/title").asText()).isEqualTo("혼돈의 지배자");
    assertThat(response.body()).doesNotContain("칭호제외").doesNotContain("보유율");
    SiteNames.assertAbsent(response.body());

    var one =
        data(
            get(
                "/api/v1/titles/equipped?server="
                    + encode(SERVER)
                    + "&title="
                    + encode("혼돈의 지배자")));
    assertThat(one.path("title").asText()).isEqualTo("혼돈의 지배자");
    assertThat(one.path("titles").size()).isEqualTo(1);
    assertThat(one.at("/meta/population").asLong()).isEqualTo(3);
  }

  @Test
  void 같은_원정대_여러_캐릭터가_같은_칭호면_한_번만_센다() throws Exception {
    var a = UUID.randomUUID();
    for (int i = 0; i < 3; i++) character(SERVER, "칭호같은" + i, a, "여명의 빛", Instant.now());
    var response = get("/api/v1/titles/equipped?server=" + encode(SERVER));
    ApiContractFixture.write("titles-equipped-same-roster.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(1);
    assertThat(data.at("/titles/0/equippedRosters").asLong()).isEqualTo(1);
    assertThat(data.at("/titles/0/equippedPercent").decimalValue()).isEqualByComparingTo("100.00");
  }

  @Test
  void 원정대가_30개_이상이면_READY다() throws Exception {
    for (int i = 0; i < 31; i++)
      character(
          BIG_SERVER, "칭호표본" + i, UUID.randomUUID(), i < 10 ? "빛을 꺼트리는 자" : null, Instant.now());
    var response = get("/api/v1/titles/equipped?server=" + encode(BIG_SERVER));
    ApiContractFixture.write("titles-equipped.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(31);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.at("/titles/0/equippedRosters").asLong()).isEqualTo(10);
    assertThat(data.at("/titles/0/equippedPercent").decimalValue()).isEqualByComparingTo("32.26");
  }

  @Test
  void 기간_밖_기록도_관측으로_세고_최근_30일_첫_기록과_캐릭터_수를_준다() throws Exception {
    var now = Instant.now();
    // 원정대 A: 지금 X 장착(최근 첫 기록). 같은 원정대의 다른 캐릭터는 40일 전에 X를 장착했다(프로필도 오래됨).
    var a = UUID.randomUUID();
    var a1 = character(SERVER, "칭호관측가", a, "X 칭호", now);
    var a2 = character(SERVER, "칭호관측가둘", a, null, now.minus(Duration.ofDays(40)));
    title(a2, "X 칭호", now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(40)));
    // 원정대 B: 지금은 Y, 40일 전에만 Z를 장착했다.
    var b = UUID.randomUUID();
    var b1 = character(SERVER, "칭호관측나", b, "Y 칭호", now);
    title(b1, "Z 칭호", now.minus(Duration.ofDays(45)), now.minus(Duration.ofDays(40)));

    var data = data(get("/api/v1/titles/equipped?server=" + encode(SERVER)));
    assertThat(data.at("/meta/population").asLong()).isEqualTo(2);
    var rows = new java.util.HashMap<String, JsonNode>();
    data.path("titles").forEach(row -> rows.put(row.path("title").asText(), row));
    // X: 기간 안 장착 1원정대, 관측 1원정대, 캐릭터 2명, 원정대의 첫 기록은 40일 전이라 최근 30일 아님.
    assertThat(rows.get("X 칭호").path("equippedRosters").asLong()).isEqualTo(1);
    assertThat(rows.get("X 칭호").path("observedRosters").asLong()).isEqualTo(1);
    assertThat(rows.get("X 칭호").path("observedCharacters").asLong()).isEqualTo(2);
    // 표본 기준(30명) 미만이면 보유율·희소도는 참고값이다.
    assertThat(rows.get("X 칭호").path("sampleReady").isBoolean()).isTrue();
    assertThat(rows.get("X 칭호").path("sampleReady").asBoolean()).isFalse();
    assertThat(rows.get("X 칭호").path("newRosters30d").asLong()).isZero();
    // Y: 지금 처음 기록 → 최근 30일.
    assertThat(rows.get("Y 칭호").path("newRosters30d").asLong()).isEqualTo(1);
    // Z: 기간 밖 장착만 → 장착 0, 관측 1(50.00%).
    assertThat(rows.get("Z 칭호").path("equippedRosters").asLong()).isZero();
    assertThat(rows.get("Z 칭호").path("observedRosters").asLong()).isEqualTo(1);
    assertThat(rows.get("Z 칭호").path("observedPercent").decimalValue())
        .isEqualByComparingTo("50.00");
    assertThat(a1).isNotNull();
  }

  @Test
  void 관측_원정대가_없으면_빈_목록이다() throws Exception {
    var response = get("/api/v1/titles/equipped?server=" + encode("칭호없음서버"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("titles-equipped-empty.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(data.path("titles").size()).isZero();
  }

  private void title(UUID id, String title, Instant first, Instant last) {
    jdbc.sql(
            """
            INSERT INTO character_title_observation (character_id, title, first_observed_at, last_observed_at)
            VALUES (:id, :title, :first, :last)
            """)
        .param("id", id)
        .param("title", title)
        .param("first", first.atOffset(ZoneOffset.UTC))
        .param("last", last.atOffset(ZoneOffset.UTC))
        .update();
  }

  private UUID character(String server, String name, UUID roster, String title, Instant at) {
    var id = UUID.randomUUID();
    var time = at.atOffset(ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              last_synced_at, profile_refreshed_at, roster_key, roster_scanned_at)
            VALUES (:id, :name, :server, '블레이드', :level, :at, :at, :roster, :at)
            """)
        .param("id", id)
        .param("name", name)
        .param("server", server)
        .param("level", new BigDecimal("1700"))
        .param("at", time)
        .param("roster", roster)
        .update();
    if (title == null) return id;
    jdbc.sql(
            """
            INSERT INTO character_title_observation (character_id, title, first_observed_at, last_observed_at)
            VALUES (:id, :title, :at, :at)
            """)
        .param("id", id)
        .param("title", title)
        .param("at", time)
        .update();
    return id;
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
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
