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

/** 최고 전투력 통계(L5). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BestCombatPowerStatsIntegrationTest {
  private static final String SERVER = "최고통계서버";
  private static final String MAIN = "최고통계직업";
  private static final String SMALL = "최고통계소수";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();
  private final Instant now = Instant.now();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '최고통계%'").update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  /**
   * MAIN 30명: 지금 전투력 3000, 최고 전투력 4000~4290(10씩). SMALL 4명: 최고 5000. 최고 전투력 없는 1명, 기간 밖 1명(최고
   * 9999), 처리정지 1명(최고 9000).
   */
  private void 표본() {
    for (int i = 0; i < 30; i++) insert("최고통계캐" + i, MAIN, "3000", "" + (4000 + 10 * i), now);
    for (int i = 0; i < 4; i++) insert("최고통계소" + i, SMALL, "3000", "5000", now);
    insert("최고통계없음", MAIN, "3000", null, now);
    insert("최고통계오래", MAIN, "3000", "9999", now.minus(Duration.ofDays(40)));
    insert("최고통계정지", MAIN, "3000", "9000", now);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('최고통계정지', :s, 'REQUEST')")
        .param("s", SERVER)
        .update();
  }

  @Test
  void 관측_이력_최고값의_전체와_직업별_분위수를_내고_작은_칸은_값을_숨긴다() throws Exception {
    표본();
    var path = "/api/v1/stats/best-combat-power?server=" + encode(SERVER);
    var body = get(path).body();
    var data = mapper.readTree(body).path("data");

    // 전체 34명(MAIN 30 + SMALL 4). 최고 전투력 없음·기간 밖·처리정지는 빠진다.
    assertThat(data.at("/meta/population").asLong()).isEqualTo(34);
    var overall = data.path("overall");
    assertThat(overall.path("count").asLong()).isEqualTo(34);
    assertThat(overall.path("sampleStatus").asText()).isEqualTo("READY");
    assertThat(overall.path("max").decimalValue()).isEqualByComparingTo("5000");
    // (4000+…+4290 = 124350) + 20000 = 144350 / 34 = 4245.59
    assertThat(overall.path("mean").decimalValue()).isEqualByComparingTo("4245.59");
    // 34개 정렬값의 중앙값 = 17·18번째(4160·4170) 평균
    assertThat(overall.at("/percentiles/p50").decimalValue()).isEqualByComparingTo("4165");

    var classes = data.path("classes");
    assertThat(classes.size()).isEqualTo(2);
    assertThat(classes.at("/0/className").asText()).isEqualTo(MAIN);
    var main = classes.at("/0/value");
    assertThat(main.path("count").asLong()).isEqualTo(30);
    assertThat(main.path("sampleStatus").asText()).isEqualTo("READY");
    assertThat(main.path("max").decimalValue()).isEqualByComparingTo("4290");
    assertThat(main.path("mean").decimalValue()).isEqualByComparingTo("4145");
    // percentile_cont: p10 = 4000 + 0.1×29×10 = 4029, p90 = 4261
    assertThat(main.at("/percentiles/p10").decimalValue()).isEqualByComparingTo("4029");
    assertThat(main.at("/percentiles/p50").decimalValue()).isEqualByComparingTo("4145");
    assertThat(main.at("/percentiles/p90").decimalValue()).isEqualByComparingTo("4261");

    // 30 미만 칸은 인원과 COLLECTING만.
    assertThat(classes.at("/1/className").asText()).isEqualTo(SMALL);
    var small = classes.at("/1/value");
    assertThat(small.path("count").asLong()).isEqualTo(4);
    assertThat(small.path("sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(small.path("mean").isNull()).isTrue();
    assertThat(small.path("max").isNull()).isTrue();
    assertThat(small.path("percentiles").isNull()).isTrue();

    // 처리정지·기간 밖 값은 어디에도 없다.
    assertThat(overall.toString() + classes).doesNotContain("9000", "9999");
    // 같은 모집단: 직업 통계 인원(최고 전투력 없는 1명 포함)과 처리정지 제외가 같다.
    var classRows = data("/api/v1/stats/classes?server=" + encode(SERVER)).path("rows");
    assertThat(classRows.get(0).path("count").asLong()).isEqualTo(31);

    assertThat(get(path).body()).isEqualTo(body);
    ApiContractFixture.write("stats-best-combat-power.json", body);
  }

  @Test
  void 전체도_30_미만이면_값을_숨기고_전투력_필터는_지금_전투력에_걸린다() throws Exception {
    표본();
    // 지금 전투력은 모두 3000이라 3001 이상이면 0명이다(최고 전투력 4000대여도).
    var none = data("/api/v1/stats/best-combat-power?combatPowerMin=3001&server=" + encode(SERVER));
    assertThat(none.at("/overall/count").asLong()).isZero();
    assertThat(none.at("/overall/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(none.at("/overall/max").isNull()).isTrue();
    assertThat(none.path("classes").size()).isZero();

    var small =
        data(
            "/api/v1/stats/best-combat-power?className="
                + encode(SMALL)
                + "&server="
                + encode(SERVER));
    assertThat(small.at("/overall/count").asLong()).isEqualTo(4);
    assertThat(small.at("/overall/mean").isNull()).isTrue();
    assertThat(small.at("/overall/percentiles").isNull()).isTrue();
  }

  @Test
  void 잘못된_입력은_400이다() throws Exception {
    assertThat(get("/api/v1/stats/best-combat-power?activeDays=5").statusCode()).isEqualTo(400);
    assertThat(get("/api/v1/stats/best-combat-power?itemLevelMin=-1").statusCode()).isEqualTo(400);
    assertThat(
            get("/api/v1/stats/best-combat-power?combatPowerMin=500&combatPowerMax=100")
                .statusCode())
        .isEqualTo(400);
  }

  private void insert(
      String name, String className, String combatPower, String best, Instant refreshed) {
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
          combat_power, best_combat_power, last_synced_at, profile_refreshed_at)
        VALUES (:id, :name, :server, :className, 1700, :cp, :best, :at, :at)
        """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", SERVER)
        .param("className", className)
        .param("cp", new BigDecimal(combatPower))
        .param("best", best == null ? null : new BigDecimal(best))
        .param("at", refreshed.atOffset(ZoneOffset.UTC))
        .update();
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

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
