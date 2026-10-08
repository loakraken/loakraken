package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatsIntegrationTest {
  private static final String SERVER = "통계테스트서버";
  private static final String MAIN_CLASS = "통계바드";
  private static final String SMALL_CLASS = "통계소수직업";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  private final JsonMapper mapper = new JsonMapper();
  private final Instant now = Instant.now();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  /**
   * MAIN_CLASS 35명: 아이템 레벨 1700~1734(1씩), 전투력 3000~3340(10씩), 짝수 SUPPORT·홀수 DEALER, 보석 8.00.
   * 1700·1701은 같은 원정대(1701이 대표). SMALL_CLASS 5명: 1650, 전투력 2000, 역할 미판정. 오래된 1명(40일 전)과 전투력 없는 1명은
   * 모집단 밖이다.
   */
  private void 표본() {
    var roster = UUID.randomUUID();
    for (int i = 0; i < 35; i++)
      insert(
          "통계캐" + i,
          MAIN_CLASS,
          new BigDecimal(1700 + i),
          new BigDecimal(3000 + 10 * i),
          i % 2 == 0 ? "SUPPORT" : "DEALER",
          new BigDecimal("8.00"),
          i <= 1 ? roster : null,
          now.minus(1, ChronoUnit.DAYS));
    for (int i = 0; i < 5; i++)
      insert(
          "통계소수" + i,
          SMALL_CLASS,
          new BigDecimal("1650"),
          new BigDecimal("2000"),
          null,
          null,
          null,
          now.minus(2, ChronoUnit.DAYS));
    insert(
        "통계오래된캐",
        MAIN_CLASS,
        new BigDecimal("1800"),
        new BigDecimal("9000"),
        "DEALER",
        null,
        null,
        now.minus(40, ChronoUnit.DAYS));
    insert(
        "통계발견만",
        MAIN_CLASS,
        new BigDecimal("1790"),
        null,
        null,
        null,
        roster,
        now.minus(1, ChronoUnit.DAYS));
  }

  @Test
  void 요약_직업_서버_분포는_최근_확인한_관측_캐릭터로_집계하고_표본이_작으면_값을_비운다() throws Exception {
    표본();
    var summary = data("/api/v1/stats/summary?server=" + encode(SERVER));
    assertThat(summary.at("/meta/population").asLong()).isEqualTo(40);
    assertThat(summary.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(summary.at("/meta/ruleVersion").asText()).isEqualTo("stats-v1");
    assertThat(summary.at("/meta/minSample").asInt()).isEqualTo(30);
    assertThat(summary.at("/meta/conditions/activeDays").asInt()).isEqualTo(30);
    assertThat(summary.at("/meta/conditions/itemLevelMin").decimalValue())
        .isEqualByComparingTo("1640");
    assertThat(summary.at("/meta/coverage/observedCharacters").asLong()).isGreaterThanOrEqualTo(41);
    assertThat(summary.path("dealers").asLong()).isEqualTo(17);
    assertThat(summary.path("supports").asLong()).isEqualTo(18);
    assertThat(summary.path("unknownRole").asLong()).isEqualTo(5);
    assertThat(summary.path("supportShare").decimalValue()).isEqualByComparingTo("51.43");
    assertThat(summary.path("gemMeanLevel").decimalValue()).isEqualByComparingTo("8.00");
    assertThat(summary.path("gemSample").asLong()).isEqualTo(35);
    // 아이템 레벨 중앙값: 1650×5 + 1700..1734 → 40개의 20·21번째 = 1714, 1715
    assertThat(summary.path("itemLevelMedian").decimalValue()).isEqualByComparingTo("1714.5");
    ApiContractFixture.write(
        "stats-summary.json", get("/api/v1/stats/summary?server=" + encode(SERVER)).body());

    var classes = data("/api/v1/stats/classes?server=" + encode(SERVER));
    assertThat(classes.at("/rows/0/className").asText()).isEqualTo(MAIN_CLASS);
    assertThat(classes.at("/rows/0/count").asLong()).isEqualTo(35);
    assertThat(classes.at("/rows/0/share").decimalValue()).isEqualByComparingTo("87.50");
    assertThat(classes.at("/rows/0/sampleStatus").asText()).isEqualTo("READY");
    assertThat(classes.at("/rows/0/combatPower/p50").decimalValue()).isEqualByComparingTo("3170");
    assertThat(classes.at("/rows/0/supportShare").isNull()).isTrue(); // 통계바드는 겸용 직업 목록에 없다.
    assertThat(classes.at("/rows/1/className").asText()).isEqualTo(SMALL_CLASS);
    assertThat(classes.at("/rows/1/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(classes.at("/rows/1/combatPower").isNull()).isTrue();
    assertThat(classes.at("/rows/1/itemLevelMean").isNull()).isTrue();
    ApiContractFixture.write(
        "stats-classes.json", get("/api/v1/stats/classes?server=" + encode(SERVER)).body());

    var servers = data("/api/v1/stats/servers?server=" + encode(SERVER));
    assertThat(servers.at("/rows/0/serverName").asText()).isEqualTo(SERVER);
    assertThat(servers.at("/rows/0/count").asLong()).isEqualTo(40);

    var distribution =
        data(
            "/api/v1/stats/distribution?metric=ITEM_LEVEL&width=10&percentileOf=1730&server="
                + encode(SERVER));
    assertThat(distribution.path("width").decimalValue()).isEqualByComparingTo("10");
    assertThat(distribution.at("/bins/0/from").decimalValue()).isEqualByComparingTo("1650");
    assertThat(distribution.at("/bins/0/to").decimalValue()).isEqualByComparingTo("1660");
    assertThat(distribution.at("/bins/0/count").asLong()).isEqualTo(5);
    assertThat(distribution.at("/bins/1/from").decimalValue()).isEqualByComparingTo("1700");
    assertThat(distribution.at("/bins/1/count").asLong()).isEqualTo(10);
    // 1730보다 큰 캐릭터 4명(1731~1734) → 5위 / 40명 = 상위 12.5%
    assertThat(distribution.at("/percentileOf/rank").asLong()).isEqualTo(5);
    assertThat(distribution.at("/percentileOf/topPercent").decimalValue())
        .isEqualByComparingTo("12.5");
    assertThat(distribution.at("/percentileOf/tier").asText()).isEqualTo("TOP25");
    ApiContractFixture.write(
        "stats-distribution.json",
        get("/api/v1/stats/distribution?metric=ITEM_LEVEL&width=10&percentileOf=1730&server="
                + encode(SERVER))
            .body());

    // 대표 캐릭터만: 같은 원정대의 대표는 아이템 레벨이 가장 높은 1790(프로필 없음)이라 1700·1701 둘 다 빠지고 1790도 모집단 밖이다.
    var representative =
        data("/api/v1/stats/summary?representativeOnly=true&server=" + encode(SERVER));
    assertThat(representative.at("/meta/population").asLong()).isEqualTo(38);
    // 기간 90일이면 40일 전 캐릭터가 들어온다.
    assertThat(
            data("/api/v1/stats/summary?activeDays=90&server=" + encode(SERVER))
                .at("/meta/population")
                .asLong())
        .isEqualTo(41);
    // 역할·직업·레벨 필터
    var supportsOnly =
        data("/api/v1/stats/summary?role=SUPPORT&itemLevelMin=1710&server=" + encode(SERVER));
    assertThat(supportsOnly.at("/meta/population").asLong()).isEqualTo(13);
    assertThat(supportsOnly.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(supportsOnly.path("itemLevelMedian").isNull()).isTrue();
  }

  @Test
  void 랭킹과_캐릭터_순위는_동률을_같은_순위로_주고_상위_퍼센트로_티어를_정한다() throws Exception {
    표본();
    var ranking = data("/api/v1/rankings?metric=COMBAT_POWER&size=3&server=" + encode(SERVER));
    assertThat(ranking.at("/meta/population").asLong()).isEqualTo(40);
    assertThat(ranking.path("metric").asText()).isEqualTo("COMBAT_POWER");
    assertThat(ranking.at("/rows").size()).isEqualTo(3);
    assertThat(ranking.at("/rows/0/characterName").asText()).isEqualTo("통계캐34");
    assertThat(ranking.at("/rows/0/rank").asLong()).isEqualTo(1);
    assertThat(ranking.at("/rows/0/topPercent").decimalValue()).isEqualByComparingTo("2.5");
    assertThat(ranking.at("/rows/0/tier").asText()).isEqualTo("TOP5");
    ApiContractFixture.write(
        "rankings.json",
        get("/api/v1/rankings?metric=COMBAT_POWER&size=3&server=" + encode(SERVER)).body());

    // 소수 직업 5명은 전투력이 모두 같아 36위 동률이다.
    var tail = data("/api/v1/rankings?metric=COMBAT_POWER&size=10&page=4&server=" + encode(SERVER));
    assertThat(tail.at("/rows/0/rank").asLong()).isEqualTo(31);
    assertThat(tail.at("/rows/5/rank").asLong()).isEqualTo(36);
    assertThat(tail.at("/rows/9/rank").asLong()).isEqualTo(36);
    assertThat(tail.at("/rows/9/tier").asText()).isEqualTo("REST");

    var rank = data("/api/v1/characters/rank?characterName=" + encode("통계캐30"));
    assertThat(rank.path("serverName").asText()).isEqualTo(SERVER);
    assertThat(rank.path("included").asBoolean()).isTrue();
    assertThat(rank.path("entries").size()).isEqualTo(6);
    JsonNode serverCombat = null;
    for (var entry : rank.path("entries"))
      if (entry.path("metric").asText().equals("COMBAT_POWER")
          && entry.path("scope").asText().equals("SERVER")) serverCombat = entry;
    assertThat(serverCombat).isNotNull();
    assertThat(serverCombat.path("scopeValue").asText()).isEqualTo(SERVER);
    assertThat(serverCombat.path("rank").asLong()).isEqualTo(5);
    assertThat(serverCombat.path("total").asLong()).isEqualTo(40);
    assertThat(serverCombat.path("topPercent").decimalValue()).isEqualByComparingTo("12.5");
    assertThat(serverCombat.path("tier").asText()).isEqualTo("TOP25");
    ApiContractFixture.write(
        "character-rank.json",
        get("/api/v1/characters/rank?characterName=" + encode("통계캐30")).body());

    // 기간 밖 캐릭터는 순위 없이 included=false
    var old = data("/api/v1/characters/rank?characterName=" + encode("통계오래된캐"));
    assertThat(old.path("included").asBoolean()).isFalse();
    assertThat(old.path("entries").size()).isZero();
  }

  @Test
  void 같은_조건은_10분_캐시를_쓴다() throws Exception {
    표본();
    var first = data("/api/v1/stats/summary?server=" + encode(SERVER));
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var second = data("/api/v1/stats/summary?server=" + encode(SERVER));
    assertThat(second.at("/meta/population").asLong()).isEqualTo(40);
    assertThat(second.at("/meta/aggregatedAt").asText())
        .isEqualTo(first.at("/meta/aggregatedAt").asText());
    var other = data("/api/v1/stats/summary?activeDays=7&server=" + encode(SERVER));
    assertThat(other.at("/meta/population").asLong()).isZero();
    assertThat(other.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/stats/summary?activeDays=15",
        "/api/v1/stats/summary?role=TANK",
        "/api/v1/stats/summary?server=a-b",
        "/api/v1/stats/summary?itemLevelMin=2001",
        "/api/v1/stats/distribution",
        "/api/v1/stats/distribution?metric=ITEM_LEVEL&width=0",
        "/api/v1/rankings?size=101",
        "/api/v1/rankings?page=0",
        "/api/v1/characters/rank"
      })
  void 잘못된_조건은_400이다(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path).isEqualTo(400);
  }

  @Test
  void 범위_역전_구간_과다_미기록_캐릭터는_업무_오류다() throws Exception {
    표본();
    var range = get("/api/v1/stats/summary?itemLevelMin=1700&itemLevelMax=1650");
    assertThat(range.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(range.body()).path("code").asText())
        .isEqualTo("STATS_INVALID_RANGE");
    var bins =
        get("/api/v1/stats/distribution?metric=COMBAT_POWER&width=0.01&server=" + encode(SERVER));
    assertThat(bins.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(bins.body()).path("code").asText()).isEqualTo("STATS_TOO_MANY_BINS");
    var missing = get("/api/v1/characters/rank?characterName=" + encode("없는통계캐릭"));
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(missing.body()).path("code").asText())
        .isEqualTo("STATS_CHARACTER_NOT_OBSERVED");
    var snapshots = data("/api/v1/stats/snapshots");
    assertThat(snapshots.at("/snapshots/0/snapshot").asText()).isEqualTo("latest");
  }

  private void insert(
      String name,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String position,
      BigDecimal gem,
      UUID roster,
      Instant refreshed) {
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
          combat_power, last_synced_at, profile_refreshed_at, position, gem_mean_level, roster_key)
        VALUES (:id, :name, :server, :className, :itemLevel, :combatPower, :at, :at, :position, :gem, :roster)
        """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", SERVER)
        .param("className", className)
        .param("itemLevel", itemLevel)
        .param("combatPower", combatPower)
        .param("at", refreshed.atOffset(ZoneOffset.UTC))
        .param("position", position)
        .param("gem", gem)
        .param("roster", roster)
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
