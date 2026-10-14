package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.loakraken.character.application.CharacterDetailsPort detailsPort;

  private final JsonMapper mapper = new JsonMapper();
  private final Instant now = Instant.now();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = new java.util.ArrayList<String>();
    for (var pattern : java.util.List.of("stats:v1:*", "character:v*빌드*"))
      if (redis.keys(pattern) != null) keys.addAll(redis.keys(pattern));
    if (!keys.isEmpty()) redis.delete(keys);
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

    // 코어·스킬 항목: 표본이 모두 v1이라 칸이 비어 있고 표본 수·비율만 있다.
    assertThat(data.at("/details/status").asText()).isEqualTo("COLLECTING");
    assertThat(data.at("/details/detailedSamples").asLong()).isZero();
    assertThat(data.at("/details/coverage").decimalValue()).isEqualByComparingTo("0");
    assertThat(data.at("/details/arkGridCores").size()).isZero();
    assertThat(data.at("/details/skills/cells").size()).isZero();
    assertThat(data.at("/details/skillDetails").size()).isZero();

    // 두 번째 요청은 캐시에서 같은 응답이다.
    assertThat(get(path).body()).isEqualTo(body);
    ApiContractFixture.write("stats-class-builds.json", body);
  }

  /** v1 표본 44명(표본_광기_30)에 v2(코어 이름·스킬 상세) 광기 30명을 더한다. */
  private void 표본_상세_30() {
    표본_광기_30();
    for (int i = 0; i < 30; i++) {
      var orderSun = i < 22 ? "살상연희" : "불타는 일격";
      var secondTier = i < 18 ? "강화된 일격" : "약점 포착";
      var rune = i < 12 ? "{\"name\":\"질풍\",\"grade\":\"전설\"}" : "null";
      var v1 = features(ENGRAVINGS_A, "[]", 30, 140, 600, 1800, "[\"특화 +81\"]");
      var v2 =
          v1.substring(0, v1.length() - 1)
              + ",\"arkGrid\":{\"effects\":{},\"cores\":["
              + "{\"kind\":\"질서\",\"position\":\"해\",\"grade\":\"유물\",\"point\":17,\"options\":[],\"name\":\""
              + orderSun
              + "\"},{\"kind\":\"혼돈\",\"position\":\"달\",\"grade\":\"유물\",\"point\":17,\"options\":[],\"name\":null}]},"
              + "\"skills\":[{\"name\":\"윈드 컷\",\"attackType\":\"백 어택\",\"level\":14,\"tripods\":["
              // 저장값은 공식 Tier 원본(0부터). 응답은 게임 화면 단계(1부터).
              + "{\"tier\":0,\"slot\":1,\"name\":\"빠른 준비\"},{\"tier\":1,\"slot\":2,\"name\":\""
              + secondTier
              + "\"}],\"rune\":"
              + rune
              + "}"
              + (i < 3
                  ? ",{\"name\":\"서프라이즈 어택\",\"attackType\":null,\"level\":4,\"tripods\":[],\"rune\":null}"
                  : "")
              + "]}";
      summary(character("빌드상세" + i, "1700"), 1, "광기", v2, "equipment-summary-v2");
    }
  }

  @Test
  void 코어와_스킬_항목은_v2_표본만_세고_v2가_30명_미만이면_칸이_비어_있다() throws Exception {
    표본_상세_30();
    var base =
        "/api/v1/stats/classes/"
            + encode(CLASS)
            + "/builds?itemLevelMin=1640&server="
            + encode(SERVER);
    var data = data(base);
    assertThat(data.at("/meta/population").asLong()).isEqualTo(74);
    var details = data.path("details");
    assertThat(details.path("status").asText()).isEqualTo("READY");
    assertThat(details.path("detailedSamples").asLong()).isEqualTo(30);
    // 30 / 74
    assertThat(details.path("coverage").decimalValue()).isEqualByComparingTo("40.54");
    var cores = details.path("arkGridCores");
    assertThat(cores.size()).isEqualTo(6);
    assertThat(cores.at("/0/kind").asText()).isEqualTo("질서");
    assertThat(cores.at("/0/position").asText()).isEqualTo("해");
    assertCells(cores.get(0), 30, "name", "살상연희", 22, "73.33", "불타는 일격", 8, "26.67");
    // 이름을 못 읽은 칸(혼돈 달)과 없는 칸은 분모 0이다.
    assertThat(cores.at("/4/known").asLong()).isZero();
    assertThat(cores.at("/5/known").asLong()).isZero();
    // 3명만 쓴 스킬은 칸에서 빠진다(hidden 3).
    assertCells(details.path("skills"), 30, "name", "윈드 컷", 30, "100.00");
    assertThat(details.at("/skills/hidden/count").asLong()).isEqualTo(3);
    var windCut = details.at("/skillDetails/0");
    assertThat(details.path("skillDetails").size()).isEqualTo(1);
    assertThat(windCut.path("skill").asText()).isEqualTo("윈드 컷");
    assertThat(windCut.path("users").asLong()).isEqualTo(30);
    var tripods = windCut.at("/tripods/cells");
    assertThat(tripods.size()).isEqualTo(3);
    assertThat(tripods.at("/0/tier").asInt()).isEqualTo(1);
    assertThat(tripods.at("/0/name").asText()).isEqualTo("빠른 준비");
    assertThat(tripods.at("/0/count").asLong()).isEqualTo(30);
    assertThat(tripods.at("/1/tier").asInt()).isEqualTo(2);
    assertThat(tripods.at("/1/name").asText()).isEqualTo("강화된 일격");
    assertThat(tripods.at("/1/count").asLong()).isEqualTo(18);
    assertThat(tripods.at("/2/name").asText()).isEqualTo("약점 포착");
    assertThat(tripods.at("/2/count").asLong()).isEqualTo(12);
    // 룬을 안 낀 18명은 분모에만 든다.
    assertCells(windCut.path("runes"), 30, "name", "질풍", 12, "40.00");
    assertThat(data.path("definitions").path("skillDetails").asText()).contains("트라이포드");

    // 직업 각인 비기(v1 14명): 코어·스킬 칸 없음, 표본 수·비율만.
    var small = data(base + "&classEngraving=" + encode("비기"));
    assertThat(small.at("/details/status").asText()).isEqualTo("COLLECTING");
    assertThat(small.at("/details/detailedSamples").asLong()).isZero();
    assertThat(small.at("/details/arkGridCores").size()).isZero();
    // 고정 조건을 걸면 고정 뒤 표본으로 센다(돌격대장 = 광기 v1 30 + v2 30).
    var fixed = data(base + "&fixEngraving=" + encode("돌격대장"));
    assertThat(fixed.at("/meta/population").asLong()).isEqualTo(60);
    assertThat(fixed.at("/details/detailedSamples").asLong()).isEqualTo(30);
    assertThat(fixed.at("/details/coverage").decimalValue()).isEqualByComparingTo("50.00");
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
  void itemLevelMax만_1680_미만이면_공통_기본_1640을_쓰고_400이_아니다() throws Exception {
    표본();
    var below =
        get(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds?itemLevelMax=1660&server="
                + encode(SERVER));
    assertThat(below.statusCode()).as(below.body()).isEqualTo(200);
    var belowData = mapper.readTree(below.body()).path("data");
    assertThat(belowData.at("/meta/conditions/itemLevelMin").decimalValue())
        .isEqualByComparingTo("1640");
    // 1640~1660: 1660 비기 14명만.
    assertThat(belowData.at("/meta/population").asLong()).isEqualTo(14);

    var above =
        data(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds?itemLevelMax=1700&server="
                + encode(SERVER));
    assertThat(above.at("/meta/conditions/itemLevelMin").decimalValue())
        .isEqualByComparingTo("1680");
    assertThat(above.at("/meta/population").asLong()).isEqualTo(20);
  }

  @Test
  void 모르는_직업은_표본_0이다() throws Exception {
    var data = data("/api/v1/stats/classes/" + encode("없는직업") + "/builds");
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("classPopulation").asLong()).isZero();
    assertThat(data.at("/topStats/known").asLong()).isZero();
  }

  @Test
  void 캐릭터_한_명의_칸은_빌드_통계_응답의_칸과_같은_모양이고_그_칸이_통계에_있다() throws Exception {
    표본();
    var builds =
        data(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds?itemLevelMin=1640&limit=20&server="
                + encode(SERVER));
    // 광기 5명(1700, 팔찌 대상) + 비기 5명(1660, 팔찌 기준 1680 미만) = 10명.
    for (var name :
        java.util.List.of(
            "빌드광기0", "빌드광기1", "빌드광기2", "빌드광기3", "빌드광기4", "빌드비기0", "빌드비기1", "빌드비기2", "빌드비기3",
            "빌드비기4")) {
      var response =
          data(
              "/api/v1/stats/classes/"
                  + encode(CLASS)
                  + "/builds/character?itemLevelMin=1640&characterName="
                  + encode(name));
      assertThat(response.path("source").asText()).isEqualTo("STORED_OBSERVATION");
      assertThat(response.path("inSample").asBoolean()).as(name).isTrue();
      assertThat(response.path("reasons").size()).isZero();
      assertThat(response.path("ruleVersion").asText()).isEqualTo("class-builds-v1");
      var cells = response.path("cells");
      assertContains(builds.at("/classEngravings/cells"), cells.path("classEngravings"), "name");
      assertContains(
          builds.at("/engravingCombos/cells"), cells.path("engravingCombos"), "engravings");
      for (var engraving : cells.path("engravings"))
        assertContains(builds.at("/engravings/cells"), engraving, "name");
      for (var tree : java.util.List.of("evolution", "enlightenment", "leap"))
        assertContains(
            builds.at("/arkPassivePoints/" + tree + "/cells"),
            cells.at("/arkPassivePoints/" + tree),
            "value");
      for (var mode : java.util.List.of("damage", "cooldown"))
        for (var gem : cells.at("/gems/" + mode))
          assertContains(builds.at("/gems/" + mode + "/cells"), gem, "value");
      assertContains(
          builds.at("/cardSets/cells"), cells.path("cardSets"), "set", "pieces", "awakening");
      assertContains(builds.at("/topStats/cells"), cells.path("topStats"), "name");
      assertThat(cells.at("/topStats/tie").asBoolean()).isFalse();
      boolean eligible = name.startsWith("빌드광기");
      assertThat(cells.path("braceletEligible").asBoolean()).as(name).isEqualTo(eligible);
      if (eligible) {
        assertThat(cells.path("braceletStats").size()).isEqualTo(2);
        for (var stat : cells.path("braceletStats"))
          assertContains(builds.at("/braceletStats/cells"), stat, "name");
      } else assertThat(cells.path("braceletStats").isNull()).isTrue();
    }
    // 광기 0번은 마지막 요약(광기)의 칸이다(옛 비기 요약이 아님).
    var first =
        data(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds/character?characterName="
                + encode("빌드광기0"));
    assertThat(first.at("/cells/classEngravings/name").asText()).isEqualTo("광기");
    // 기본 itemLevelMin(1680)이면 1660 비기는 조건 밖이고 칸은 그대로 준다.
    var low =
        data(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds/character?characterName="
                + encode("빌드비기0"));
    assertThat(low.path("inSample").asBoolean()).isFalse();
    assertThat(low.path("reasons").toString()).isEqualTo("[\"ITEM_LEVEL_OUT_OF_RANGE\"]");
    assertThat(low.at("/conditions/itemLevelMin").decimalValue()).isEqualByComparingTo("1680");
    assertThat(low.at("/cells/classEngravings/name").asText()).isEqualTo("비기");
    var otherClass =
        data(
            "/api/v1/stats/classes/"
                + encode("다른직업")
                + "/builds/character?itemLevelMin=1640&characterName="
                + encode("빌드광기1"));
    assertThat(otherClass.path("reasons").toString()).isEqualTo("[\"OTHER_CLASS\"]");
    verifyNoInteractions(detailsPort);
  }

  /** 광기 10명을 더해 광기 30 + 비기 14 = 44명(1640 기준). */
  private void 표본_광기_30() {
    표본();
    for (int i = 20; i < 30; i++)
      summary(
          character("빌드광기" + i, "1700"),
          1,
          "광기",
          features(ENGRAVINGS_A, "[]", 30, 140, 600, 1800, "[\"특화 +81\",\"치명 +70\"]"));
  }

  @Test
  void 고정_조건을_걸면_그_표본으로_모든_항목을_다시_세고_30명_미만이면_칸이_없다() throws Exception {
    표본_광기_30();
    var base =
        "/api/v1/stats/classes/"
            + encode(CLASS)
            + "/builds?itemLevelMin=1640&server="
            + encode(SERVER);
    // 돌격대장은 광기 30명만 가진다 → 직업 각인 비율도 광기 100%.
    var fixed = data(base + "&fixEngraving=" + encode("돌격대장"));
    assertThat(fixed.at("/meta/population").asLong()).isEqualTo(30);
    assertThat(fixed.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertCells(fixed.path("classEngravings"), 30, "name", "광기", 30, "100.00");
    assertCells(fixed.path("topStats"), 30, "name", "특화", 30, "100.00");
    // 두 조건(돌격대장 + 주력 특화)도 같다. 조건 순서·중복과 무관하게 같은 결과.
    var two = data(base + "&fixTopStat=" + encode("특화") + "&fixEngraving=" + encode("돌격대장"));
    assertThat(two.at("/meta/population").asLong()).isEqualTo(30);
    // 비기(치명 주력) 14명만 남으면 30 미만 → 칸 없음.
    var small = data(base + "&fixTopStat=" + encode("치명"));
    assertThat(small.at("/meta/population").asLong()).isEqualTo(14);
    assertThat(small.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(small.at("/classEngravings/cells").size()).isZero();
    assertThat(small.at("/topStats/cells").size()).isZero();
    // 팔찌 고정은 팔찌 기준(1680) 이상만: 1660 비기는 치명 줄이 있어도 들지 않는다.
    var bracelet = data(base + "&fixBracelet=" + encode("치명"));
    assertThat(bracelet.at("/meta/population").asLong()).isEqualTo(30);
    // 처리정지 캐릭터만 가진 각인·없는 각인·없는 카드 세트는 기본 집계 칸에 없어 400이고 캐시를 만들지 않는다.
    for (var query :
        java.util.List.of(
            "&fixEngraving=" + encode("정지각인가"),
            "&fixEngraving=" + encode("없는각인"),
            "&fixCardSet=" + encode("없는 카드 세트"))) {
      for (var path : java.util.List.of(base, base.replace("/builds?", "/builds/top?"))) {
        var rejected = get(path + query);
        assertThat(rejected.statusCode()).as(path + query).isEqualTo(400);
        assertThat(rejected.body()).contains("STATS_UNKNOWN_FIX").doesNotContain("빌드정지");
      }
    }
    var fixKeys = redis.keys("stats:v1:*fix=*");
    assertThat(fixKeys == null ? java.util.Set.of() : fixKeys)
        .noneMatch(key -> key.contains("정지각인가") || key.contains("없는"));
    // 기본 집계에 있는 카드 세트는 받는다.
    assertThat(data(base + "&fixCardSet=" + encode("세상을 구하는 빛")).at("/meta/population").asLong())
        .isEqualTo(44);
    // 고정 없는 결과는 그대로(44명).
    assertThat(data(base).at("/meta/population").asLong()).isEqualTo(44);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fixEngraving=%EA%B0%80&fixEngraving=%EB%82%98&fixEngraving=%EB%8B%A4&fixTopStat=%EC%B9%98%EB%AA%85",
        "fixTopStat=%ED%8E%B8%EC%95%88",
        "fixBracelet=%EA%B0%80",
        "fixEvolution=-1",
        "fixEngraving=%25"
      })
  void 고정_조건이_3개를_넘거나_형식이_틀리면_400이다(String query) throws Exception {
    for (var path : java.util.List.of("/builds?", "/builds/top?"))
      assertThat(get("/api/v1/stats/classes/" + encode(CLASS) + path + query).statusCode())
          .as(path + query)
          .isEqualTo(400);
  }

  @Test
  void 이_세팅을_쓰는_상위_30명은_관측_전투력_순이고_30명_미만이면_목록이_없다() throws Exception {
    표본_광기_30();
    jdbc.sql("UPDATE characters SET combat_power = 3500 WHERE character_name = '빌드비기3'").update();
    jdbc.sql("UPDATE characters SET combat_power = 3400 WHERE character_name = '빌드광기7'").update();
    var base =
        "/api/v1/stats/classes/"
            + encode(CLASS)
            + "/builds/top?itemLevelMin=1640&server="
            + encode(SERVER);

    var all = data(base);
    assertThat(all.at("/meta/population").asLong()).isEqualTo(44);
    assertThat(all.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    var rows = all.path("rows");
    assertThat(rows.size()).isEqualTo(30);
    assertThat(rows.at("/0/rank").asInt()).isEqualTo(1);
    assertThat(rows.at("/0/characterName").asText()).isEqualTo("빌드비기3");
    assertThat(rows.at("/0/combatPower").decimalValue()).isEqualByComparingTo("3500");
    assertThat(rows.at("/1/characterName").asText()).isEqualTo("빌드광기7");
    // 같은 전투력이면 아이템 레벨 높은 순(1700 광기가 1660 비기보다 앞).
    assertThat(rows.at("/2/itemLevel").decimalValue()).isEqualByComparingTo("1700");
    assertThat(rows.at("/29/rank").asInt()).isEqualTo(30);
    assertThat(all.toString()).doesNotContain("빌드정지", "빌드요약없음");

    // 고정: 돌격대장(광기 30) → 30명, 비기 없음.
    var fixed = data(base + "&fixEngraving=" + encode("돌격대장"));
    assertThat(fixed.path("rows").size()).isEqualTo(30);
    assertThat(fixed.path("rows").toString()).doesNotContain("빌드비기");
    assertThat(fixed.at("/rows/0/characterName").asText()).isEqualTo("빌드광기7");
    // 30명 미만(치명 주력 비기 14) → 목록 없음.
    var small = data(base + "&fixTopStat=" + encode("치명"));
    assertThat(small.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(small.at("/meta/population").asLong()).isEqualTo(14);
    assertThat(small.path("rows").size()).isZero();
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 처리정지_캐릭터의_칸_응답은_같은_상태의_기록_없는_캐릭터와_같다() throws Exception {
    // ① 캐시도 저장도 없음: 처리정지(저장 요약 있음) = 모르는 이름 = 같은 404·같은 본문.
    표본();
    var stopped = get(characterPath("빌드정지0"));
    var unknown = get(characterPath("빌드없는캐릭터"));
    assertThat(stopped.statusCode()).isEqualTo(404).isEqualTo(unknown.statusCode());
    assertThat(stopped.body()).isEqualTo(unknown.body()).contains("CHARACTER_NOT_CACHED");

    // ② 최근 검색 캐시가 있음: 처리정지(검색 전 등록, 기록 안 됨) = 기록 없는 일반 캐릭터(검색 뒤 행 삭제) = 같은 칸.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('빌드캐시정지', :s, 'PRIVACY_STOP')")
        .param("s", SERVER)
        .update();
    search("빌드캐시정지");
    search("빌드캐시없음");
    jdbc.sql("DELETE FROM characters WHERE character_name = '빌드캐시없음'").update();
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM characters WHERE character_name = '빌드캐시정지'")
                .query(Long.class)
                .single())
        .isZero();
    var stoppedLive = mapper.readTree(get(characterPath("빌드캐시정지")).body());
    var unknownLive = mapper.readTree(get(characterPath("빌드캐시없음")).body());
    assertThat(stoppedLive.at("/data/source").asText()).isEqualTo("LIVE_CACHE");
    // 이름과 검색 시각(두 검색의 시각 차)만 다르고 나머지 응답은 같다.
    for (var response : java.util.List.of(stoppedLive, unknownLive)) {
      assertThat(response.at("/data/observedAt").isNull()).isFalse();
      ((tools.jackson.databind.node.ObjectNode) response.path("data")).remove("characterName");
      ((tools.jackson.databind.node.ObjectNode) response.path("data")).remove("observedAt");
    }
    assertThat(stoppedLive).isEqualTo(unknownLive);

    // 처리정지 캐릭터는 집계에도 없다.
    var builds =
        get(
            "/api/v1/stats/classes/"
                + encode(CLASS)
                + "/builds?itemLevelMin=1640&server="
                + encode(SERVER));
    assertThat(builds.body()).doesNotContain("빌드캐시정지", "정지각인");
  }

  private String characterPath(String name) {
    return "/api/v1/stats/classes/"
        + encode(CLASS)
        + "/builds/character?itemLevelMin=1640&characterName="
        + encode(name);
  }

  /** builds 칸 목록에 key 필드가 같은 칸이 있다. */
  private static void assertContains(JsonNode cells, JsonNode key, String... fields) {
    assertThat(key.isNull() || key.isMissingNode()).as("key " + key).isFalse();
    boolean found = false;
    for (var cell : cells) {
      boolean same = true;
      for (var field : fields) same &= cell.path(field).equals(key.path(field));
      found |= same;
    }
    assertThat(found).as(key + " in " + cells).isTrue();
  }

  private void search(String name) throws Exception {
    var response = get("/api/v1/characters/search?characterName=" + encode(name));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  private com.loakraken.character.application.FetchedCharacter fetched(String name) {
    var profile =
        new com.loakraken.character.domain.CharacterProfile(
            name,
            SERVER,
            CLASS,
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            java.util.List.of());
    var loadout =
        new com.loakraken.character.domain.CharacterLoadout(
            java.util.List.of(), null, null, null, null, null, java.util.List.of());
    return new com.loakraken.character.application.FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, java.util.List.of()), "{}", "[]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/stats/classes/CLASS/builds/character",
        "/api/v1/stats/classes/CLASS/builds/character?characterName=%25",
        "/api/v1/stats/classes/CLASS/builds/character?characterName=a&activeDays=5",
        "/api/v1/stats/classes/CLASS/builds/character?characterName=a&itemLevelMin=1700&itemLevelMax=1600",
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
    summary(id, hoursAgo, title, features, "equipment-summary-v1");
  }

  private void summary(UUID id, int hoursAgo, String title, String features, String schema) {
    jdbc.sql(
            """
        INSERT INTO character_equipment_summary (character_id, observed_at, last_confirmed_at,
          schema_version, combat_power, item_level, class_name, ark_passive_title, features, features_hash)
        VALUES (:id, :at, :at, :schema, 3000, 1700, :className, :title,
          CAST(:features AS JSONB), :hash)
        """)
        .param("id", id)
        .param("schema", schema)
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
