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

/** 직업 상세 빌드 통계(M12 1단계). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClassBuildsIntegrationTest {
  private static final String SERVER = "빌드테스트서버";
  private static final String CLASS = "빌드테스트직업";
  private static final String ENGRAVINGS_A =
      "[{\"name\":\"원한\"},{\"name\":\"아드레날린\"},{\"name\":\"예리한 둔기\"},{\"name\":\"돌격대장\"},{\"name\":\"저주받은 인형\"}]";
  private static final String ENGRAVINGS_B =
      "[{\"name\":\"원한\"},{\"name\":\"아드레날린\"},{\"name\":\"예리한 둔기\"},{\"name\":\"기습의 대가\"},{\"name\":\"저주받은 인형\"}]";
  private static final String ENGRAVINGS_STOPPED = "[{\"name\":\"정지각인가\"},{\"name\":\"정지각인나\"}]";
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
    jdbc.sql("DELETE FROM public_exclusion WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  /**
   * 광기 20명(아이템 레벨 1700, 팔찌 특화·치명), 비기 14명(1660, 팔찌 치명), 장비 요약 없는 1명, 처리정지 5명(비기, 다른 각인 조합). 광기 0번은
   * 옛 요약(비기)이 하나 더 있어 마지막 요약만 센다.
   */
  private void 표본() {
    for (int i = 0; i < 20; i++) {
      var id = character("빌드광기" + i, "1700");
      summary(
          id,
          1,
          "광기",
          features(
              ENGRAVINGS_A,
              "[{\"kind\":\"겁화\",\"level\":10,\"mode\":\"DAMAGE\"},{\"kind\":\"광휘\",\"level\":9,\"mode\":\"DAMAGE\"},"
                  + "{\"kind\":\"작열\",\"level\":8,\"mode\":\"COOLDOWN\"},{\"kind\":\"광휘\",\"level\":8,\"mode\":\"COOLDOWN\"},"
                  + "{\"kind\":\"멸화\",\"level\":10,\"mode\":\"DAMAGE\"}]",
              30,
              140,
              600,
              1800,
              "[\"특화 +81\",\"치명 +70\",\"치명타 피해 +8.40%\"]"));
      if (i == 0) summary(id, 48, "비기", features(ENGRAVINGS_B, "[]", 18, 120, 1800, 600, "[]"));
    }
    for (int i = 0; i < 14; i++)
      summary(
          character("빌드비기" + i, "1660"),
          2,
          "비기",
          features(
              ENGRAVINGS_B,
              "[{\"kind\":\"겁화\",\"level\":9,\"mode\":\"DAMAGE\"}]",
              18,
              120,
              1800,
              600,
              "[\"치명 +90\"]"));
    character("빌드요약없음", "1700");
    for (int i = 0; i < 5; i++) {
      summary(
          character("빌드정지" + i, "1700"),
          1,
          "비기",
          features(ENGRAVINGS_STOPPED, "[]", 18, 99, 1800, 600, "[\"신속 +99\"]"));
      jdbc.sql(
              "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'REQUEST')")
          .param("n", "빌드정지" + i)
          .param("s", SERVER)
          .update();
    }
  }

  @Test
  void 마지막_장비_요약으로_항목별_인원과_비율을_세고_처리정지는_통계_어디에도_없다() throws Exception {
    표본();
    var path =
        "/api/v1/stats/classes/"
            + encode(CLASS)
            + "/builds?itemLevelMin=1640&server="
            + encode(SERVER);
    var body = get(path).body();
    var data = data(path);

    assertThat(data.at("/meta/population").asLong()).isEqualTo(34);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.at("/meta/ruleVersion").asText()).isEqualTo("class-builds-v1");
    assertThat(data.path("className").asText()).isEqualTo(CLASS);
    assertThat(data.path("classEngraving").isNull()).isTrue();
    assertThat(data.path("classPopulation").asLong()).isEqualTo(35);
    assertThat(data.path("minCell").asInt()).isEqualTo(5);
    assertThat(data.path("braceletItemLevelMin").decimalValue()).isEqualByComparingTo("1680");
    assertThat(data.path("classEngravingStatus").asText()).isEqualTo("READY");
    assertThat(data.path("definitions").path("topStats").asText()).contains("ties");

    // 직업 각인: 광기 0번은 마지막 요약(광기)만 센다. 처리정지 5명(비기)은 빠진다.
    assertCells(data.path("classEngravings"), 34, "name", "광기", 20, "58.82", "비기", 14, "41.18");
    var combos = data.at("/engravingCombos/cells");
    assertThat(combos.size()).isEqualTo(2);
    assertThat(combos.at("/0/engravings").toString())
        .isEqualTo("[\"돌격대장\",\"아드레날린\",\"예리한 둔기\",\"원한\",\"저주받은 인형\"]");
    assertThat(combos.at("/0/count").asLong()).isEqualTo(20);
    assertThat(data.at("/engravings/cells/0/name").asText()).isEqualTo("아드레날린");
    assertThat(data.at("/engravings/cells/0/share").decimalValue()).isEqualByComparingTo("100.00");
    assertCells(
        data.at("/arkPassivePoints/evolution"), 34, "value", 140, 20, "58.82", 120, 14, "41.18");
    // 보석: 효과 종류별, 광휘 포함, 3티어(멸화) 제외. 피해 = 겁화10·광휘9 ×20 + 겁화9 ×14.
    assertCells(data.at("/gems/damage"), 54, "value", 9, 34, "62.96", 10, 20, "37.04");
    assertCells(data.at("/gems/cooldown"), 40, "value", 8, 40, "100.00");
    var cards = data.at("/cardSets/cells");
    assertThat(cards.at("/0/set").asText()).isEqualTo("세상을 구하는 빛");
    assertThat(cards.at("/0/pieces").asInt()).isEqualTo(6);
    assertThat(cards.at("/0/awakening").asInt()).isEqualTo(30);
    assertThat(cards.at("/1/awakening").asInt()).isEqualTo(18);
    assertThat(data.at("/topStats/ties").asLong()).isZero();
    assertCells(data.path("topStats"), 34, "name", "특화", 20, "58.82", "치명", 14, "41.18");
    // 팔찌: 1680 이상(광기 20명)만. 한 캐릭터가 여러 특성에 세어 합이 100%를 넘는다.
    assertCells(data.path("braceletStats"), 20, "name", "치명", 20, "100.00", "특화", 20, "100.00");
    assertThat(data.at("/braceletStats/unread").asLong()).isZero();
    assertThat(data.at("/topStats/hidden/count").asLong()).isZero();
    assertThat(data.at("/topStats/hidden/share").decimalValue()).isEqualByComparingTo("0");
    assertThat(body).doesNotContain("정지각인", "빌드정지");
    assertThat(data.at("/braceletStats/cells").toString()).doesNotContain("신속");

    // 같은 모집단: /stats/classes 인원 = classPopulation(처리정지 없음).
    var classes = data("/api/v1/stats/classes?server=" + encode(SERVER)).path("rows");
    assertThat(classes.get(0).path("className").asText()).isEqualTo(CLASS);
    assertThat(classes.get(0).path("count").asLong()).isEqualTo(35);
    var servers = data("/api/v1/stats/servers?server=" + encode(SERVER)).path("rows");
    assertThat(servers.get(0).path("count").asLong()).isEqualTo(35);
    assertThat(servers.get(0).path("knownCharacters").asLong()).isEqualTo(35);
    assertThat(
            data("/api/v1/stats/summary?server=" + encode(SERVER)).at("/meta/population").asLong())
        .isEqualTo(35);

    // 두 번째 요청은 캐시에서 같은 응답이다.
    assertThat(get(path).body()).isEqualTo(body);
    ApiContractFixture.write("stats-class-builds.json", body);
  }

  @Test
  void 직업_각인_필터_표본이_작으면_COLLECTING이고_직업_각인_비율만_준다() throws Exception {
    표본();
    var data =
        data(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds?itemLevelMin=1640&server="
                + encode(SERVER)
                + "&classEngraving="
                + encode("비기"));
    assertThat(data.at("/meta/population").asLong()).isEqualTo(14);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("classEngraving").asText()).isEqualTo("비기");
    assertThat(data.path("classEngravingStatus").asText()).isEqualTo("READY");
    assertThat(data.at("/classEngravings/cells").size()).isEqualTo(2);
    assertThat(data.at("/engravingCombos/cells").size()).isZero();
    assertThat(data.at("/gems/damage/cells").size()).isZero();
    assertThat(data.at("/topStats/cells").size()).isZero();

    var limited =
        data(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds?itemLevelMin=1640&limit=1&braceletItemLevelMin=1600&server="
                + encode(SERVER));
    assertThat(limited.at("/engravingCombos/cells").size()).isEqualTo(1);
    // 팔찌 기준은 itemLevelMin(1640)보다 낮출 수 없다. 1660 비기도 들어간다.
    assertThat(limited.path("braceletItemLevelMin").decimalValue()).isEqualByComparingTo("1640");
    assertThat(limited.at("/braceletStats/known").asLong()).isEqualTo(34);
  }

  @Test
  void itemLevelMin을_주지_않으면_빌드_통계만_1680을_쓴다() throws Exception {
    표본();
    var data = data("/api/v1/stats/classes/" + encode(CLASS) + "/builds?server=" + encode(SERVER));
    // 1660 비기 14명은 빠지고 1700 광기 20명만 표본이다(30 미만이라 COLLECTING).
    assertThat(data.at("/meta/conditions/itemLevelMin").decimalValue())
        .isEqualByComparingTo("1680");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(20);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    // 요약 없는 1700 캐릭터 1명을 더한 모집단. 같은 기준을 준 /stats/classes 인원과 같다.
    assertThat(data.path("classPopulation").asLong()).isEqualTo(21);
    var classes =
        data("/api/v1/stats/classes?itemLevelMin=1680&server=" + encode(SERVER)).path("rows");
    assertThat(classes.get(0).path("count").asLong()).isEqualTo(21);
    assertThat(data.path("definitions").path("sample").asText()).contains("1680");
    // 다른 통계 기본값은 1640 그대로.
    assertThat(
            data("/api/v1/stats/classes?server=" + encode(SERVER))
                .at("/meta/conditions/itemLevelMin")
                .decimalValue())
        .isEqualByComparingTo("1640");
  }

  @Test
  void 모르는_직업은_표본_0이다() throws Exception {
    var data = data("/api/v1/stats/classes/" + encode("없는직업") + "/builds");
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("classPopulation").asLong()).isZero();
    assertThat(data.at("/topStats/known").asLong()).isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/stats/classes/%EC%A7%81%EC%97%851/builds",
        "/api/v1/stats/classes/CLASS/builds?classEngraving=%25",
        "/api/v1/stats/classes/CLASS/builds?limit=21",
        "/api/v1/stats/classes/CLASS/builds?limit=0",
        "/api/v1/stats/classes/CLASS/builds?braceletItemLevelMin=3000",
        "/api/v1/stats/classes/CLASS/builds?activeDays=5"
      })
  void 잘못된_입력은_400이다(String path) throws Exception {
    assertThat(get(path.replace("CLASS", encode(CLASS))).statusCode()).isEqualTo(400);
  }

  private static void assertCells(JsonNode section, long known, String keyField, Object... cells) {
    assertThat(section.path("known").asLong()).isEqualTo(known);
    var actual = section.path("cells");
    assertThat(actual.size()).isEqualTo(cells.length / 3);
    for (int i = 0; i < cells.length / 3; i++) {
      var cell = actual.get(i);
      assertThat(cell.path(keyField).asText()).isEqualTo(String.valueOf(cells[i * 3]));
      assertThat(cell.path("count").asLong()).isEqualTo(((Number) cells[i * 3 + 1]).longValue());
      assertThat(cell.path("share").decimalValue()).isEqualByComparingTo((String) cells[i * 3 + 2]);
    }
  }

  private static String features(
      String engravings,
      String gems,
      int awakening,
      int evolution,
      int crit,
      int spec,
      String bracelet) {
    return "{\"engravings\":"
        + engravings
        + ",\"gems\":"
        + gems
        + ",\"cards\":{\"set\":\"세상을 구하는 빛\",\"pieces\":6,\"awakening\":"
        + awakening
        + ",\"elementPct\":0},\"arkPassive\":{\"points\":{\"evolution\":"
        + evolution
        + ",\"enlightenment\":101,\"leap\":70}},\"stats\":{\"crit\":"
        + crit
        + ",\"spec\":"
        + spec
        + ",\"swift\":60,\"domination\":60,\"endurance\":60,\"expertise\":60},\"braceletLines\":"
        + bracelet
        + "}";
  }

  private UUID character(String name, String itemLevel) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
          combat_power, last_synced_at, profile_refreshed_at, position)
        VALUES (:id, :name, :server, :className, :itemLevel, 3000, :at, :at, 'DEALER')
        """)
        .param("id", id)
        .param("name", name)
        .param("server", SERVER)
        .param("className", CLASS)
        .param("itemLevel", new BigDecimal(itemLevel))
        .param("at", now.minus(1, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
        .update();
    return id;
  }

  private void summary(UUID id, int hoursAgo, String title, String features) {
    jdbc.sql(
            """
        INSERT INTO character_equipment_summary (character_id, observed_at, last_confirmed_at,
          schema_version, combat_power, item_level, class_name, ark_passive_title, features, features_hash)
        VALUES (:id, :at, :at, 'equipment-summary-v1', 3000, 1700, :className, :title,
          CAST(:features AS JSONB), :hash)
        """)
        .param("id", id)
        .param("at", now.minus(hoursAgo, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
        .param("className", CLASS)
        .param("title", title)
        .param("features", features)
        .param("hash", "a".repeat(64))
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
