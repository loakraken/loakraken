package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.specup.domain.SpecUpCostRules;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 깨달음 카르마 (f)(spec-up.karma-cp.enabled 켬, PM 총괄 11-15): 딜러 카르마 줄은 레벨마다 무기 공격력 +0.1%(W 항)로 Δ전투력을 내고,
 * 공개 시뮬레이터에 같은 변경(ARK_PASSIVE_POINT 깨달음 karmaLevel)을 넣은 Δ와 같다. 추천 순서에 카르마 단계가 들어가고, 서포터는 점수만(크라켄 점수
 * 추천 순서). 기준 캐릭터 원문(l01 딜러 카르마 29, l04 서포터 카르마 24). 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "combat-power-sim.enabled=true",
      "spec-up.support-order.enabled=true",
      "spec-up.karma-cp.enabled=true"
    })
class SpecUpKarmaCpIntegrationTest {
  private static final String SERVER = "카르마전투력서버";
  private static final String DEALER = "카르마딜러";
  private static final String SUPPORTER = "카르마서폿";
  private static final String MAXED = "카르마만렙";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  @Autowired com.loakraken.character.application.CombatPowerSimulationService simulator;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("*카르마*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 딜러_카르마_줄은_W_항_Δ전투력이고_시뮬레이터에_같은_변경을_넣은_값과_같다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(DEALER, "l01", "블레이드", "4052.77");
    var candidates = guide(DEALER, "&sort=DELTA&limit=200").path("candidates");
    var row = find(candidates, "KARMA_LEVEL:30");
    assertThat(row.path("status").asText()).as(row.toString()).isEqualTo("OK");
    assertThat(row.path("change").isNull()).isTrue();
    var simulated =
        simulate(
            DEALER, "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 30}]");
    assertThat(simulated.statusCode()).as(simulated.body()).isEqualTo(200);
    var data = mapper.readTree(simulated.body()).path("data");
    assertThat(data.path("partial").asBoolean()).isFalse();
    assertThat(row.path("deltaCombatPower").decimalValue())
        .isEqualByComparingTo(data.path("delta").decimalValue());
    // 29 → 30: 무기 공격력 % +0.1%p만큼 오른다(실제 원문 기댓값 +2.13은 계산 검증이 실제 경로로 확인, 이 원문은 기본 공격력 툴팁을 합성).
    assertThat(row.path("deltaCombatPower").decimalValue()).isPositive();
    assertThat(row.path("cost").path("amount").asLong())
        .isEqualTo(Math.round(SpecUpCostRules.karmaGold(29, 30)));
    assertThat(row.path("combatPowerPer10kGold").isNull()).isFalse();
  }

  @Test
  void 딜러_추천_순서에_카르마_단계가_들어가고_단계_변경을_다시_재면_같다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(DEALER, "l01", "블레이드", "4052.77");
    for (var sort : List.of("EFFICIENCY", "KRAKEN")) {
      var steps = path(DEALER, sort).path("steps");
      var bySlot = new LinkedHashMap<String, String>();
      JsonNode karma = null;
      for (var step : steps) {
        var id = step.path("candidateId").asText();
        bySlot.put(id.substring(0, id.lastIndexOf(':')), step.path("changes").toString());
        if (!id.startsWith("KARMA_LEVEL:")) continue;
        karma = step;
        break;
      }
      assertThat(karma).as(sort + " 카르마 단계").isNotNull();
      assertThat(karma.path("kind").asText()).isEqualTo("KARMA_LEVEL");
      assertThat(karma.path("candidateId").asText()).isEqualTo("KARMA_LEVEL:30");
      var change = karma.path("changes").get(0);
      assertThat(change.path("kind").asText()).isEqualTo("ARK_PASSIVE_POINT");
      assertThat(change.path("tree").asText()).isEqualTo("깨달음");
      assertThat(change.path("karmaLevel").asInt()).isEqualTo(30);
      assertThat(change.path("level").isNull()).isTrue();
      assertThat(change.path("points").isMissingNode() || change.path("points").isNull()).isTrue();
      assertThat(karma.path("cost").path("amount").asLong())
          .isEqualTo(Math.round(SpecUpCostRules.karmaGold(29, 30)));
      // 웹이 단계 changes를 칸마다 마지막 것만 남겨 다시 재면 단계 전투력과 같다.
      var all = new StringBuilder("[");
      for (var changes : bySlot.values()) {
        if (all.length() > 1) all.append(',');
        all.append(changes, 1, changes.length() - 1);
      }
      var simulated = simulate(DEALER, all.append(']').toString());
      assertThat(simulated.statusCode()).as(simulated.body()).isEqualTo(200);
      assertThat(mapper.readTree(simulated.body()).path("data").path("after").decimalValue())
          .as(sort)
          .isEqualByComparingTo(karma.path("combatPowerAfter").decimalValue());
    }
  }

  @Test
  void 서포터_카르마_줄은_점수만이고_KRAKEN_추천_순서에서만_점수로_고른다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(SUPPORTER, "l04", "도화가", "3000.00");
    var candidates = guide(SUPPORTER, "&sort=DELTA&limit=200").path("candidates");
    int karma = 0;
    for (var candidate : candidates) {
      if (!candidate.path("id").asText().startsWith("KARMA_LEVEL:")) continue;
      karma++;
      assertThat(candidate.path("status").asText()).isEqualTo("UNSUPPORTED");
      // 서포터 목록은 지금처럼 모든 줄이 역할 이유(전투력 없음).
      assertThat(candidate.path("reasonCode").asText()).isEqualTo("SUPPORTER_MODEL_PENDING");
      assertThat(candidate.path("deltaCombatPower").isNull()).isTrue();
    }
    assertThat(karma).isEqualTo(6);
    var steps = path(SUPPORTER, "KRAKEN").path("steps");
    boolean found = false;
    for (var step : steps)
      if ("KARMA_LEVEL".equals(step.path("kind").asText())) {
        found = true;
        assertThat(step.path("deltaKrakenScore").decimalValue()).isPositive();
        assertThat(step.path("deltaCombatPower").isNull()).isTrue();
      }
    assertThat(found).as(steps.toString()).isTrue();
  }

  /**
   * 답이 카르마·연마 단계를 후보로 봤는지(프론트 11-21, PM 총괄 v0.3.15): 카르마 스위치 켬, 연마 스위치 꺼짐. 딜러는 모든 순서와 목표 경로에서 카르마
   * true, 서포터는 KRAKEN 순서에서만 true. 고른 카르마 단계가 없어도(limit=1) true. 연마는 스위치가 꺼져 있어 늘 false.
   */
  @Test
  void 카르마_스위치가_켜지면_딜러와_서포터_KRAKEN_답이_카르마를_본다고_알린다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(DEALER, "l01", "블레이드", "4052.77");
    search(SUPPORTER, "l04", "도화가", "3000.00");
    for (var sort : List.of("EFFICIENCY", "DELTA", "KRAKEN")) {
      var dealer = path(DEALER, sort);
      assertThat(dealer.path("karmaStepsConsidered").isBoolean()).as(sort).isTrue();
      assertThat(dealer.path("karmaStepsConsidered").asBoolean()).as(sort).isTrue();
      assertThat(dealer.path("polishStepsConsidered").isBoolean()).as(sort).isTrue();
      assertThat(dealer.path("polishStepsConsidered").asBoolean()).as(sort).isFalse();
      // 같은 키의 두 번째 답(캐시)도 값을 채운다.
      var again = path(DEALER, sort);
      assertThat(again.path("karmaStepsConsidered").asBoolean()).as(sort + " 캐시").isTrue();
      assertThat(again.path("polishStepsConsidered").isBoolean()).as(sort + " 캐시").isTrue();
    }
    // 고른 카르마 단계가 없어도 본 것은 본 것(웹 '켰지만 고르지 않음' 표본).
    // 카르마 30레벨 딜러: 고를 카르마 단계가 없어도 답은 카르마를 봤다(웹 '켰지만 고르지 않음' 표본).
    var raw =
        (tools.jackson.databind.node.ObjectNode)
            mapper
                .readTree(
                    new ClassPathResource("kraken-score/l01.json")
                        .getContentAsString(StandardCharsets.UTF_8))
                .path("raw");
    int edited = 0;
    for (var point : raw.path("ArkPassive").path("Points"))
      if ("깨달음".equals(point.path("Name").asText())) {
        var description = point.path("Description").asText();
        assertThat(description).contains("29레벨");
        ((tools.jackson.databind.node.ObjectNode) point)
            .put("Description", description.replace("29레벨", "30레벨"));
        edited++;
      }
    assertThat(edited).isEqualTo(1);
    search(MAXED, raw, "블레이드", "4052.77");
    var maxed = get(pathUri(MAXED, "EFFICIENCY", 30));
    assertThat(maxed.statusCode()).as(maxed.body()).isEqualTo(200);
    var maxedData = mapper.readTree(maxed.body()).path("data");
    assertThat(maxedData.path("karmaStepsConsidered").asBoolean()).isTrue();
    assertThat(maxedData.path("steps").size()).isPositive();
    for (var step : maxedData.path("steps"))
      assertThat(step.path("kind").asText()).isNotEqualTo("KARMA_LEVEL");
    ApiContractFixture.write("spec-up-path-karma-considered-no-step.json", maxed.body());
    var supporterKraken = path(SUPPORTER, "KRAKEN");
    assertThat(supporterKraken.path("sort").asText()).isEqualTo("KRAKEN");
    assertThat(supporterKraken.path("karmaStepsConsidered").asBoolean()).isTrue();
    var supporterEfficiency = path(SUPPORTER, "EFFICIENCY");
    assertThat(supporterEfficiency.path("karmaStepsConsidered").isBoolean()).isTrue();
    assertThat(supporterEfficiency.path("karmaStepsConsidered").asBoolean()).isFalse();
    // 목표 경로는 전투력으로 고르므로 딜러만.
    var goal = get(goalUri(DEALER, "4100"));
    assertThat(goal.statusCode()).as(goal.body()).isEqualTo(200);
    var goalData = mapper.readTree(goal.body()).path("data");
    assertThat(goalData.path("karmaStepsConsidered").asBoolean()).isTrue();
    assertThat(goalData.path("polishStepsConsidered").isBoolean()).isTrue();
    assertThat(goalData.path("polishStepsConsidered").asBoolean()).isFalse();
    var goalAgain = mapper.readTree(get(goalUri(DEALER, "4100")).body()).path("data");
    assertThat(goalAgain.path("karmaStepsConsidered").asBoolean()).as("목표 캐시").isTrue();
    var supporterGoal = get(goalUri(SUPPORTER, "3100"));
    assertThat(supporterGoal.statusCode()).as(supporterGoal.body()).isEqualTo(200);
    assertThat(
            mapper
                .readTree(supporterGoal.body())
                .path("data")
                .path("karmaStepsConsidered")
                .asBoolean())
        .isFalse();
  }

  private static String pathUri(String name, String sort, int limit) {
    return "/api/v1/characters/spec-up/path?characterName="
        + encode(name)
        + "&sort="
        + sort
        + "&limit="
        + limit;
  }

  private static String goalUri(String name, String target) {
    return "/api/v1/characters/spec-up/goal?characterName="
        + encode(name)
        + "&targetCombatPower="
        + target;
  }

  @Test
  void 시뮬레이터_카르마_변경은_0에서_30까지이고_points와_함께_쓸_수_없다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(DEALER, "l01", "블레이드", "4052.77");
    assertThat(
            simulate(
                    DEALER,
                    "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 31}]")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            simulate(
                    DEALER,
                    "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 30, \"points\": 90}]")
                .statusCode())
        .isEqualTo(400);
    // 카르마 레벨은 자기 필드로만(PM 총괄 11-19): level과 함께, 다른 트리·종류에 쓰면 400.
    for (var bad :
        List.of(
            "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 30, \"level\": 30}]",
            "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"진화\", \"karmaLevel\": 30}]",
            "[{\"kind\": \"GEM_LEVEL\", \"slot\": 0, \"level\": 9, \"karmaLevel\": 30}]"))
      assertThat(simulate(DEALER, bad).statusCode()).as(bad).isEqualTo(400);
    // 깨달음 포인트(points) 변경은 그대로(카르마 레벨과 섞이지 않음).
    var points =
        simulate(DEALER, "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"points\": 90}]");
    assertThat(points.statusCode()).as(points.body()).isEqualTo(200);
    // 지금 레벨과 같으면 Δ 0.
    var same =
        simulate(
            DEALER, "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 29}]");
    assertThat(same.statusCode()).as(same.body()).isEqualTo(200);
    assertThat(mapper.readTree(same.body()).path("data").path("delta").decimalValue())
        .isEqualByComparingTo(BigDecimal.ZERO);
  }

  /**
   * 스위치가 꺼져 있으면 /simulate에 카르마 변경을 바로 넣어도 계산하지 않는다(코드 리뷰 #1004 P2): 200, partial, 그 변경은
   * UNSUPPORTED이고 Δ 0. 검사(400)는 스위치와 상관없이 그대로.
   */
  @Test
  void 스위치가_꺼져_있으면_시뮬레이터도_카르마를_계산하지_않는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(DEALER, "l01", "블레이드", "4052.77");
    org.springframework.test.util.ReflectionTestUtils.setField(simulator, "karmaCpEnabled", false);
    try {
      var off =
          simulate(
              DEALER, "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 30}]");
      assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
      var data = mapper.readTree(off.body()).path("data");
      assertThat(data.path("partial").asBoolean()).as(off.body()).isTrue();
      assertThat(data.path("delta").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(data.path("contributions").get(0).path("status").asText())
          .isEqualTo("UNSUPPORTED");
      var bad =
          simulate(
              DEALER, "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 31}]");
      assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(simulator, "karmaCpEnabled", true);
    }
  }

  /** 아크 패시브가 없는 캐릭터는 카르마 레벨을 읽은 것이 아니다(코드 리뷰 #1004 P3): 0으로 보고 30까지 +3%를 내지 않고 UNSUPPORTED. */
  @Test
  void 아크_패시브가_없으면_카르마를_계산하지_않는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(DEALER, "l01", "블레이드", "4052.77", true);
    var none =
        simulate(
            DEALER, "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"karmaLevel\": 30}]");
    assertThat(none.statusCode()).as(none.body()).isEqualTo(200);
    var data = mapper.readTree(none.body()).path("data");
    assertThat(data.path("delta").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(data.path("contributions").get(0).path("status").asText()).isEqualTo("UNSUPPORTED");
  }

  private static JsonNode find(JsonNode candidates, String id) {
    for (var candidate : candidates) if (id.equals(candidate.path("id").asText())) return candidate;
    throw new AssertionError(id + " 후보가 없습니다");
  }

  private JsonNode guide(String name, String query) throws Exception {
    var response = get("/api/v1/characters/spec-up?characterName=" + encode(name) + query);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    SiteNames.assertAbsent(response.body());
    return mapper.readTree(response.body()).path("data");
  }

  private JsonNode path(String name, String sort) throws Exception {
    var response =
        get(
            "/api/v1/characters/spec-up/path?characterName="
                + encode(name)
                + "&sort="
                + sort
                + "&limit=30");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    SiteNames.assertAbsent(response.body());
    return mapper.readTree(response.body()).path("data");
  }

  private void search(String name, String fixture, String className, String combatPower)
      throws Exception {
    search(name, fixture, className, combatPower, false);
  }

  private void search(
      String name, String fixture, String className, String combatPower, boolean noArkPassive)
      throws Exception {
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/" + fixture + ".json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    if (noArkPassive) ((tools.jackson.databind.node.ObjectNode) raw).remove("ArkPassive");
    search(name, raw, className, combatPower);
  }

  private void search(String name, JsonNode raw, String className, String combatPower)
      throws Exception {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            className,
            70,
            new BigDecimal("1770.00"),
            new BigDecimal(combatPower),
            null,
            null,
            null,
            null,
            stats(raw));
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(name)).statusCode())
        .isEqualTo(200);
  }

  /** 원문 프로필 특성. 저장한 원문은 툴팁이 잘려 있어 재련 모델 W 항에 필요한 공격력 툴팁의 기본 공격력을 합성으로 넣는다(값은 공격력 합보다 작은 임의 값). */
  private static List<CharacterProfile.Stat> stats(JsonNode raw) {
    var stats = new java.util.ArrayList<CharacterProfile.Stat>();
    for (var stat : raw.path("ArmoryProfile").path("Stats")) {
      var tooltip = new java.util.ArrayList<String>();
      for (var line : stat.path("Tooltip")) tooltip.add(line.asText());
      if ("공격력".equals(stat.path("Type").asText()) && tooltip.isEmpty())
        tooltip.add("힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 180000 입니다.");
      stats.add(
          new CharacterProfile.Stat(
              stat.path("Type").asText(), stat.path("Value").asText(), tooltip));
    }
    return stats;
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> simulate(String name, String changes) throws Exception {
    var body = "{\"characterName\": \"" + name + "\", \"changes\": " + changes + "}";
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + port + "/api/v1/characters/combat-power/simulate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
