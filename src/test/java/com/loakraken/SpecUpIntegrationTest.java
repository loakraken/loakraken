package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterLoadout.EffectGrade;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketAuctionService.GemCell;
import com.loakraken.market.application.RelicBookScan;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스펙업 가이드 1차판(#315): 캐릭터 캐시 + 시세 캐시만 쓰고(공식 호출 없음), 후보마다 시뮬레이터 변화 하나로 Δ전투력, 보석 비용(다음 레벨 구매가 − 지금 보석
 * 판매 기대가), 1만 골드당 전투력. 계산할 수 없는 후보는 reasonCode·reason과 함께 남긴다. 표본: 정상(UNSUPPORTED 섞임)·시세 PENDING·캐시
 * 없음.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spec-up.enabled=true")
class SpecUpIntegrationTest {
  private static final String SERVER = "스펙서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired com.loakraken.market.application.MarketCache marketCache;
  @Autowired com.loakraken.refining.application.RefiningPlanService plans;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.loakraken.character.application.CombatPowerSimulationService simulator;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*스펙*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    redis.delete("market:v1:refining-materials");
    var goals = redis.keys("spec-up:v1:goal:*");
    if (goals != null && !goals.isEmpty()) redis.delete(goals);
  }

  @Test
  void 후보마다_Δ전투력과_비용과_효율을_주고_계산할_수_없는_후보는_이유와_함께_남긴다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙캐시");
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙캐시"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-spec-up.json", response.body());
    SiteNames.assertAbsent(response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("spec-up-v1");
    assertThat(data.path("simulatorRuleVersion").asText()).isEqualTo("combat-power-sim-v8");
    assertThat(data.path("combatPower").decimalValue()).isEqualByComparingTo("3000.00");
    assertThat(data.path("sort").asText()).isEqualTo("EFFICIENCY");
    // 보석은 단계별 후보(8→9, 8→10)가 각각 나오고, 빈 칸 10개는 가장 앞 빈 칸(1번) 하나로 6~10레벨, 무기 품질(90 → 100)도 계산한다.
    assertThat(data.path("total").asInt()).isEqualTo(8);
    var candidates = data.path("candidates");
    // 효율 순: 빈 칸 6·7·8·9·10레벨(24.68·24.56·4.93·2.23·0.83) → 보석 8→9(0.39) → 8→10(0.18) → 품질(비용 모름).
    assertThat(
            java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> candidates.get(i).path("id").asText())
                .toList())
        .containsExactly(
            "GEM_LEVEL:1:6",
            "GEM_LEVEL:1:7",
            "GEM_LEVEL:1:8",
            "GEM_LEVEL:1:9",
            "GEM_LEVEL:1:10",
            "GEM_LEVEL:0:9",
            "GEM_LEVEL:0:10",
            "EQUIPMENT_QUALITY:0:100");
    // 빈 칸 10레벨: 3000 × 1.0704 × (1 + 4.50%)/(1 + 3.30%) = 3248.50. 비용은 10레벨 겁화 즉시 구매 최저가 그대로.
    var empty10 = find(candidates, "GEM_LEVEL:1:10");
    assertThat(empty10.path("kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(empty10.path("emptySlot").asBoolean()).isTrue();
    assertThat(empty10.path("target").asText()).isEqualTo("빈 보석 칸 (10칸 비어 있음)");
    assertThat(empty10.path("from").asText()).isEqualTo("빈 칸");
    assertThat(empty10.path("to").asText()).isEqualTo("10레벨 겁화");
    assertThat(empty10.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("248.50");
    assertThat(empty10.at("/cost/code").asText()).isEqualTo("GEM_BUY");
    assertThat(empty10.at("/cost/amount").asLong()).isEqualTo(3_000_000);
    assertThat(empty10.path("baseAttackPctFrom").decimalValue()).isEqualByComparingTo("0");
    assertThat(empty10.path("baseAttackPctTo").decimalValue()).isEqualByComparingTo("1.20");
    assertThat(empty10.at("/change/slot").asInt()).isEqualTo(1);
    assertThat(find(candidates, "GEM_LEVEL:1:6").path("deltaCombatPower").decimalValue())
        .isEqualByComparingTo("148.05");
    // 빈 칸은 1~5레벨 후보를 내지 않고, 다른 빈 칸(2번~)을 반복하지 않는다.
    for (var candidate : candidates)
      assertThat(candidate.path("id").asText()).isNotIn("GEM_LEVEL:1:5", "GEM_LEVEL:2:6");
    // 보석 8 → 9: 3000 × (1.0640/1.0576) × (1 + 3.50%)/(1 + 3.30%) = 3024.00.
    var gem = find(candidates, "GEM_LEVEL:0:9");
    assertThat(gem.path("id").asText()).isEqualTo("GEM_LEVEL:0:9");
    assertThat(gem.path("kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(gem.path("target").asText()).isEqualTo("테스트 스킬 · 8레벨 겁화");
    assertThat(gem.path("skillName").asText()).isEqualTo("테스트 스킬");
    assertThat(gem.path("from").asText()).isEqualTo("8레벨");
    assertThat(gem.path("to").asText()).isEqualTo("9레벨");
    assertThat(gem.path("status").asText()).isEqualTo("OK");
    assertThat(gem.path("emptySlot").asBoolean()).isFalse();
    assertThat(gem.path("baseAttackPctFrom").decimalValue()).isEqualByComparingTo("0.80");
    assertThat(gem.path("baseAttackPctTo").decimalValue()).isEqualByComparingTo("1.00");
    assertThat(gem.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("24.00");
    assertThat(gem.path("deltaPercent").decimalValue()).isEqualByComparingTo("0.800");
    // 비용: 9레벨 1,000,000 − (8레벨 400,000 − 거래소 수수료 20,000) = 620,000. 효율 = 24.00 × 10000 / 620000 =
    // 0.39.
    // 10000 / 615000 = 0.39.
    assertThat(gem.at("/cost/status").asText()).isEqualTo("OK");
    assertThat(gem.at("/cost/code").asText()).isEqualTo("GEM_UPGRADE");
    assertThat(gem.at("/cost/amount").asLong()).isEqualTo(620_000);
    assertThat(gem.path("combatPowerPer10kGold").decimalValue()).isEqualByComparingTo("0.39");
    assertThat(gem.at("/change/kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(gem.at("/change/slot").asInt()).isZero();
    assertThat(gem.at("/change/level").asInt()).isEqualTo(9);
    // 8 → 10: 시뮬레이터 표본과 같은 48.07, 비용 3,000,000 − 380,000 = 2,620,000, 효율 0.18(효율 순이라 8 → 9 뒤).
    var gem10 = find(candidates, "GEM_LEVEL:0:10");
    assertThat(gem10.path("id").asText()).isEqualTo("GEM_LEVEL:0:10");
    assertThat(gem10.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("48.07");
    assertThat(gem10.at("/cost/amount").asLong()).isEqualTo(2_620_000);
    assertThat(gem10.at("/cost/unit").asText()).isEqualTo("GOLD");
    assertThat(gem10.path("combatPowerPer10kGold").decimalValue()).isEqualByComparingTo("0.18");
    assertThat(gem10.path("suggestedTo").isNull()).isTrue();
    // 무기 품질 90 → 100: 3000 × (1 + 30%)/(1 + 26.2%) = 3090.33. 비용 근거가 없어 UNKNOWN이고 효율이 없어 보석 뒤.
    var quality = find(candidates, "EQUIPMENT_QUALITY:0:100");
    assertThat(quality.path("emptySlot").isNull()).isTrue();
    assertThat(quality.path("baseAttackPctTo").isNull()).isTrue();
    assertThat(quality.path("id").asText()).isEqualTo("EQUIPMENT_QUALITY:0:100");
    assertThat(quality.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("90.33");
    assertThat(quality.at("/cost/status").asText()).isEqualTo("UNKNOWN");
    assertThat(quality.at("/cost/code").asText()).isEqualTo("QUALITY_COST_UNCONFIRMED");
    assertThat(quality.path("combatPowerPer10kGold").isNull()).isTrue();
    // 나머지는 UNSUPPORTED이고, 같은 코드는 같은 문장이다.
    var codes = new ArrayList<String>();
    for (int i = 8; i < candidates.size(); i++) {
      var candidate = candidates.get(i);
      assertThat(candidate.path("status").asText()).isEqualTo("UNSUPPORTED");
      assertThat(candidate.path("deltaCombatPower").isNull()).isTrue();
      assertThat(candidate.path("cost").isNull()).isTrue();
      assertThat(candidate.path("reasonCode").asText()).isNotBlank();
      codes.add(candidate.path("id").asText() + "=" + candidate.path("reasonCode").asText());
    }
    assertThat(codes)
        .contains(
            "EQUIPMENT_REFINE:0:21=MODEL_UNCONFIRMED",
            "BRACELET_EFFECT=MODEL_UNCONFIRMED",
            "POLISH=MODEL_UNCONFIRMED",
            "ARK_GRID_CORE=MODEL_UNCONFIRMED",
            "ARK_PASSIVE_POINT=MODEL_UNCONFIRMED",
            "KARMA_LEVEL=MODEL_UNCONFIRMED",
            "ABILITY_STONE=MODEL_UNCONFIRMED");
    var refine = find(candidates, "EQUIPMENT_REFINE:0:21");
    var bracelet = find(candidates, "BRACELET_EFFECT");
    assertThat(refine.path("reason").asText()).isEqualTo(bracelet.path("reason").asText());
    assertThat(refine.at("/change/refine").asInt()).isEqualTo(21);
    assertThat(bracelet.path("change").isNull()).isTrue();
    // 공식 호출은 처음 검색 한 번뿐이다.
    verify(detailsPort, times(1)).fetch(anyString());
  }

  @Test
  void 유물_각인_한_레벨_비용은_각인서_5권이고_시세가_없으면_PENDING이다() throws Exception {
    // L30: 비용 = 5권 × 유물 각인서 즉시 구매 최저가(예열 캐시). 원한 3 → 4 = 3000 × 1.24/1.2325 = 3018.26.
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    redis.delete("market:v1:relic-books");
    search("스펙각인");
    var pending =
        find(
            mapper
                .readTree(get("/api/v1/characters/spec-up?characterName=" + encode("스펙각인")).body())
                .at("/data/candidates"),
            "ENGRAVING_LEVEL:원한:4");
    assertThat(pending.path("status").asText()).isEqualTo("OK");
    assertThat(pending.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("18.26");
    assertThat(pending.at("/cost/status").asText()).isEqualTo("PENDING");
    assertThat(pending.at("/cost/code").asText()).isEqualTo("PRICE_PENDING");
    assertThat(pending.at("/cost/basis").asText()).contains("각인서 시세");
    // 공식 각인 응답에는 아이콘이 없다. 각인서 시세 캐시가 없으면 아이콘도 없다(따로 공식 호출 안 함).
    assertThat(pending.path("icon").isNull()).isTrue();

    marketCache.saveRelicBooks(
        new RelicBookScan(
            Instant.now(),
            1,
            List.of(
                new MarketPrice(
                    1,
                    "유물 원한 각인서",
                    "유물",
                    "https://cdn.example/use_9_25.png",
                    1,
                    null,
                    null,
                    null,
                    new BigDecimal("12000")))),
        Duration.ofMinutes(5));
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙각인"));
    var engraving =
        find(mapper.readTree(response.body()).at("/data/candidates"), "ENGRAVING_LEVEL:원한:4");
    assertThat(engraving.at("/cost/status").asText()).isEqualTo("OK");
    assertThat(engraving.at("/cost/code").asText()).isEqualTo("ENGRAVING_BOOK_BUY");
    assertThat(engraving.at("/cost/amount").asLong()).isEqualTo(60_000);
    // 같은 이름 유물 각인서의 공식 아이콘.
    assertThat(engraving.path("icon").asText()).isEqualTo("https://cdn.example/use_9_25.png");
    // 18.26 × 10000 / 60000 = 3.04
    assertThat(engraving.path("combatPowerPer10kGold").decimalValue()).isEqualByComparingTo("3.04");
    assertThat(mapper.readTree(response.body()).at("/data/pricesAt").isNull()).isFalse();
    redis.delete("market:v1:relic-books");
  }

  @Test
  void 재련_후보가_계산되면_시세_칸이_차도_비어도_500이_아니다() throws Exception {
    // P0 10-12(B8): 재련 모델이 OK를 내는 캐릭터에서 재련 후보 비용이 null이라 cost.amount()에서 NPE(500)가 났다.
    // 재련 후보만 시뮬레이터가 OK(Δ+12.34)를 내게 하고, 보석 시세 칸이 찬 상태·빈 상태를 모두 본다.
    stubRefineOk();
    search("스펙재련");
    for (boolean ready : new boolean[] {true, false}) {
      when(auctions.cachedGems()).thenReturn(gemCells(ready));
      var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙재련"));
      assertThat(response.statusCode()).as("시세 칸 " + ready).isEqualTo(200);
      var refine =
          find(mapper.readTree(response.body()).at("/data/candidates"), "EQUIPMENT_REFINE:0:21");
      assertThat(refine.path("status").asText()).isEqualTo("OK");
      assertThat(refine.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("12.34");
      assertThat(refine.at("/cost/status").asText()).isEqualTo("UNKNOWN");
      assertThat(refine.at("/cost/code").asText()).isEqualTo("REFINE_COST_UNCONFIRMED");
      assertThat(refine.path("combatPowerPer10kGold").isNull()).isTrue();
    }
    org.mockito.Mockito.reset(simulator);
  }

  @Test
  void 업화_무기_재련_후보는_재련_계획_기대_소모와_재료_시세로_비용을_준다() throws Exception {
    stubRefineOk();
    // L30 후속: +20 업화 무기 → +21. 숨결 없음과 최대 사용 중 기대 비용이 작은 쪽(재료 1개 즉시 구매 최저가 × 기대 수량 + 기대 골드).
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙업화");
    // 시세 예열 전이면 PENDING이다.
    var pending =
        find(
            mapper
                .readTree(get("/api/v1/characters/spec-up?characterName=" + encode("스펙업화")).body())
                .at("/data/candidates"),
            "EQUIPMENT_REFINE:0:21");
    assertThat(pending.at("/cost/status").asText()).isEqualTo("PENDING");
    assertThat(pending.at("/cost/code").asText()).isEqualTo("PRICE_PENDING");

    var observedAt = Instant.parse("2026-10-12T02:00:00Z");
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            observedAt,
            List.of(
                material(1, "운명의 파괴석", 100, "152"),
                material(2, "운명의 돌파석", 1, "4"),
                material(3, "아비도스 융화 재료", 1, "210"),
                material(4, "운명의 파편 주머니(소)", 1, "87"),
                material(5, "운명의 파편 주머니(대)", 1, "219"),
                material(6, "용암의 숨결", 1, "459"))),
        java.time.Duration.ofMinutes(5));
    var units =
        java.util.Map.of(
            "운명의 파괴석", new BigDecimal("1.52"),
            "운명의 돌파석", new BigDecimal("4"),
            "아비도스 융화 재료", new BigDecimal("210"),
            // 파편은 주머니 가운데 1개당 가장 싼 값: (대) 219 ÷ 3000 = 0.073 < (소) 87 ÷ 1000.
            "운명의 파편", new BigDecimal("0.073"),
            "용암의 숨결", new BigDecimal("459"));
    long expected = Long.MAX_VALUE;
    for (boolean breath : new boolean[] {false, true}) {
      var plan =
          plans.plan(
              com.loakraken.refining.domain.RefiningRules.Grade.UPHWA,
              com.loakraken.refining.domain.RefiningRules.EquipmentType.WEAPON,
              new com.loakraken.refining.domain.RefiningPlan.Options(
                  20, 21, breath, false, true, 0, 0));
      var total = plan.expectedGold();
      for (var quantity : plan.expectedMaterials())
        total = total.add(quantity.amount().multiply(units.get(quantity.name())));
      expected = Math.min(expected, total.setScale(0, java.math.RoundingMode.CEILING).longValue());
    }
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙업화"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-spec-up-refine-cost.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    var refine = find(data.path("candidates"), "EQUIPMENT_REFINE:0:21");
    assertThat(refine.at("/cost/status").asText()).isEqualTo("OK");
    assertThat(refine.at("/cost/code").asText()).isEqualTo("REFINE_EXPECTED");
    assertThat(refine.at("/cost/amount").asLong()).isEqualTo(expected);
    assertThat(expected).isPositive();
    // 가장 이른 시세 관측 시각이 보석 표 시각보다 이르면 그 시각이다.
    assertThat(Instant.parse(data.path("pricesAt").asText())).isBeforeOrEqualTo(observedAt);
    SiteNames.assertAbsent(response.body());
    org.mockito.Mockito.reset(simulator);
  }

  /** 재련 후보만 시뮬레이터가 OK(Δ+12.34)를 내게 한다. 나머지 후보는 실제 계산. 테스트 끝에 reset(simulator). */
  private void stubRefineOk() {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              List<com.loakraken.character.application.CombatPowerSimulationService.Change>
                  changes = invocation.getArgument(1);
              var change = changes.getFirst();
              if (change.kind()
                  != com.loakraken.character.application.CombatPowerSimulationService.Kind
                      .EQUIPMENT_REFINE) return invocation.callRealMethod();
              var ok =
                  new com.loakraken.character.application.CombatPowerSimulationService.Contribution(
                      change.kind().name(),
                      "무기",
                      "+20",
                      "+21",
                      new BigDecimal("12.34"),
                      com.loakraken.character.application.CombatPowerSimulationService.Status.OK,
                      "재련",
                      null);
              return new com.loakraken.character.application.CombatPowerSimulationService.Result(
                  "스펙재련",
                  Instant.now(),
                  new BigDecimal("3000.00"),
                  null,
                  new BigDecimal("3012.34"),
                  new BigDecimal("12.34"),
                  new BigDecimal("0.411"),
                  false,
                  List.of(ok),
                  0,
                  List.of());
            })
        .when(simulator)
        .simulate(
            org.mockito.ArgumentMatchers.any(
                com.loakraken.character.application.CharacterResult.class),
            org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 전율_장비에는_상급_재련_후보를_만들지_않고_업화_장비에는_만든다() throws Exception {
    // E21: 전율 장비에는 상급 재련이 없다. 툴팁에 상급 재련 0단계가 있어도 후보를 만들지 않는다.
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    for (var name : List.of("스펙전율", "스펙업화")) {
      search(name);
      var response = get("/api/v1/characters/spec-up?characterName=" + encode(name));
      assertThat(response.statusCode()).isEqualTo(200);
      var ids = new ArrayList<String>();
      for (var candidate : mapper.readTree(response.body()).at("/data/candidates"))
        ids.add(candidate.path("id").asText());
      if (name.contains("전율"))
        assertThat(ids).noneMatch(id -> id.startsWith("EQUIPMENT_ADVANCED_REFINE:"));
      else assertThat(ids).contains("EQUIPMENT_ADVANCED_REFINE:0:1");
      // 일반 재련 후보는 둘 다 있다.
      assertThat(ids).contains("EQUIPMENT_REFINE:0:21");
    }
  }

  private static com.loakraken.market.domain.MarketPrice material(
      long id, String name, int bundle, String currentMin) {
    return new com.loakraken.market.domain.MarketPrice(
        id, name, "영웅", null, bundle, null, null, null, new BigDecimal(currentMin));
  }

  @Test
  void 지금_레벨_매물이_없으면_판매_기대가를_몰라_PENDING이다() throws Exception {
    // PM-백엔드 10-12 05:01 P2: 지금 레벨(8) 칸은 있지만 최저가가 없으면 GEM_BUY(판매가 빼지 않음)가 아니라 PRICE_PENDING.
    var cells = new ArrayList<GemCell>();
    for (var cell : gemCells(true))
      cells.add(
          cell.level() == 8
              ? new GemCell(
                  cell.kind(),
                  cell.level(),
                  cell.itemName(),
                  new AuctionCell(null, 0, Instant.parse("2026-10-11T07:00:00Z")),
                  null,
                  MarketAuctionService.Status.READY)
              : cell);
    when(auctions.cachedGems()).thenReturn(cells);
    search("스펙매물없음");
    var gem =
        find(
            mapper
                .readTree(
                    get("/api/v1/characters/spec-up?characterName=" + encode("스펙매물없음")).body())
                .at("/data/candidates"),
            "GEM_LEVEL:0:9");
    assertThat(gem.at("/cost/status").asText()).isEqualTo("PENDING");
    assertThat(gem.at("/cost/code").asText()).isEqualTo("PRICE_PENDING");
  }

  @Test
  void 시세_칸이_비면_비용만_PENDING이고_효율은_없다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(false));
    search("스펙대기");
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙대기"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-spec-up-pending.json", response.body());
    var gem = find(mapper.readTree(response.body()).at("/data/candidates"), "GEM_LEVEL:0:9");
    assertThat(gem.path("status").asText()).isEqualTo("OK");
    assertThat(gem.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("24.00");
    assertThat(gem.at("/cost/status").asText()).isEqualTo("PENDING");
    assertThat(gem.at("/cost/code").asText()).isEqualTo("PRICE_PENDING");
    assertThat(gem.at("/cost/amount").isNull()).isTrue();
    assertThat(gem.path("combatPowerPer10kGold").isNull()).isTrue();
    assertThat(mapper.readTree(response.body()).at("/data/pricesAt").isNull()).isTrue();
  }

  @Test
  void limit은_OK_후보에만_적용하고_UNSUPPORTED는_모두_남는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙제한");
    var all =
        mapper
            .readTree(get("/api/v1/characters/spec-up?characterName=" + encode("스펙제한")).body())
            .at("/data/candidates")
            .size();
    var data =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up?sort=DELTA&limit=1&characterName=" + encode("스펙제한"))
                    .body())
            .path("data");
    assertThat(data.path("sort").asText()).isEqualTo("DELTA");
    assertThat(data.path("limit").asInt()).isEqualTo(1);
    assertThat(data.path("total").asInt()).isEqualTo(8);
    assertThat(data.path("candidates").size()).isEqualTo(all - 7);
    // Δ 순이라 빈 칸 10레벨(248.50)이 먼저 남는다.
    assertThat(data.at("/candidates/0/id").asText()).isEqualTo("GEM_LEVEL:1:10");
  }

  @Test
  void 보석이_하나도_없으면_빈_칸_보석_장착_후보를_6에서_10레벨까지_낸다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙빈칸");
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙빈칸"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-spec-up-empty-gems.json", response.body());
    SiteNames.assertAbsent(response.body());
    var candidates = mapper.readTree(response.body()).at("/data/candidates");
    // 보석 칸 11개가 모두 비었다: 0번 칸 하나로 6~10레벨. 기본 공격력 % 합은 스톤 1.50 + 완갑 1.00 = 2.50.
    // 6레벨: 3000 × 1.0448 × (1 + 2.95%)/(1 + 2.50%) = 3148.16, 10레벨: 3000 × 1.0704 × 1.037/1.025 = 3248.79.
    var expected =
        java.util.Map.of(
            6, new String[] {"148.16", "60000", "24.69", "0.45"},
            7, new String[] {"172.06", "70000", "24.58", "0.60"},
            8, new String[] {"197.56", "400000", "4.94", "0.80"},
            9, new String[] {"223.14", "1000000", "2.23", "1.00"},
            10, new String[] {"248.79", "3000000", "0.83", "1.20"});
    for (var entry : expected.entrySet()) {
      var candidate = find(candidates, "GEM_LEVEL:0:" + entry.getKey());
      var values = entry.getValue();
      assertThat(candidate.path("status").asText()).isEqualTo("OK");
      assertThat(candidate.path("emptySlot").asBoolean()).isTrue();
      assertThat(candidate.path("target").asText()).isEqualTo("빈 보석 칸 (11칸 비어 있음)");
      assertThat(candidate.path("deltaCombatPower").decimalValue()).isEqualByComparingTo(values[0]);
      assertThat(candidate.at("/cost/code").asText()).isEqualTo("GEM_BUY");
      assertThat(candidate.at("/cost/amount").asLong()).isEqualTo(Long.parseLong(values[1]));
      assertThat(candidate.path("combatPowerPer10kGold").decimalValue())
          .isEqualByComparingTo(values[2]);
      assertThat(candidate.path("baseAttackPctFrom").decimalValue()).isEqualByComparingTo("0");
      assertThat(candidate.path("baseAttackPctTo").decimalValue()).isEqualByComparingTo(values[3]);
    }
    for (var candidate : candidates)
      assertThat(candidate.path("id").asText()).doesNotStartWith("GEM_LEVEL:1:");
  }

  @Test
  void 빈_칸_보석_후보는_시세가_비면_비용만_PENDING이다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search("스펙빈칸대기");
    var candidates =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up?characterName=" + encode("스펙빈칸대기")).body())
            .at("/data/candidates");
    var empty = find(candidates, "GEM_LEVEL:0:8");
    assertThat(empty.path("status").asText()).isEqualTo("OK");
    assertThat(empty.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("197.56");
    assertThat(empty.at("/cost/status").asText()).isEqualTo("PENDING");
    assertThat(empty.path("combatPowerPer10kGold").isNull()).isTrue();
  }

  @Test
  void 서포터는_모든_후보가_역할_이유로_UNSUPPORTED다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetched(invocation.getArgument(0));
              var profile = base.details().profile();
              var loadout = base.details().loadout();
              return new FetchedCharacter(
                  CharacterTestFixture.details(
                      new CharacterProfile(
                          profile.characterName(),
                          SERVER,
                          "바드",
                          70,
                          profile.itemLevel(),
                          profile.combatPower(),
                          null,
                          null,
                          null,
                          null,
                          List.of()),
                      new CharacterLoadout(
                          loadout.equipment(),
                          loadout.engravings(),
                          loadout.gems(),
                          null,
                          new CharacterLoadout.ArkPassive(
                              "깨달음",
                              true,
                              List.of(),
                              List.of(
                                  new CharacterLoadout.PassiveEffect(
                                      "깨달음", "깨달음 1티어 절실한 구원 Lv.1", null))),
                          loadout.arkGrid(),
                          List.of()),
                      List.of()),
                  "{}",
                  "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("스펙서폿")).statusCode())
        .isEqualTo(200);
    var data =
        mapper
            .readTree(get("/api/v1/characters/spec-up?characterName=" + encode("스펙서폿")).body())
            .path("data");
    assertThat(data.path("total").asInt()).isZero();
    for (var candidate : data.path("candidates")) {
      assertThat(candidate.path("status").asText()).isEqualTo("UNSUPPORTED");
      assertThat(candidate.path("reasonCode").asText()).isEqualTo("SUPPORTER_MODEL_PENDING");
    }
    assertThat(data.path("candidates").size()).isPositive();
    // 목표 경로(L31)도 오류가 아니라 200, 빈 경로, 같은 역할 이유 코드다.
    var goal =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up/goal?characterName="
                        + encode("스펙서폿")
                        + "&targetCombatPower=3100")
                    .body())
            .path("data");
    assertThat(goal.path("stopReasonCode").asText()).isEqualTo("SUPPORTER_MODEL_PENDING");
    assertThat(goal.path("stopReason").asText()).isNotBlank();
    assertThat(goal.path("steps").size()).isZero();
    assertThat(goal.path("reached").asBoolean()).isFalse();
    assertThat(goal.path("totalCost").isNull()).isTrue();
  }

  @Test
  void 캐시가_없으면_CHARACTER_NOT_CACHED이고_공식_호출이_없다() throws Exception {
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙없음"));
    assertThat(response.statusCode()).isEqualTo(404);
    ApiContractFixture.write("character-spec-up-not-cached.json", response.body());
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED");
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 정렬_값이_틀리면_400이다() throws Exception {
    assertThat(
            get("/api/v1/characters/spec-up?sort=GOLD&characterName=" + encode("스펙캐시"))
                .statusCode())
        .isEqualTo(400);
  }

  @Test
  void 목표_경로는_같은_보석_칸을_이어서_올리고_시세_없는_단계부터_누적_비용이_null이다() throws Exception {
    // L31: 효율 순으로 보석 8→9(620,000), 9→10(증분 2,000,000), 그다음 비용을 모르는 무기 품질 90→100.
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙목표");
    var response =
        get(
            "/api/v1/characters/spec-up/goal?characterName="
                + encode("스펙목표")
                + "&targetCombatPower=3100");
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-spec-up-goal.json", response.body());
    SiteNames.assertAbsent(response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("spec-up-goal-v1");
    assertThat(data.path("combatPower").decimalValue()).isEqualByComparingTo("3000.00");
    assertThat(data.path("targetCombatPower").decimalValue()).isEqualByComparingTo("3100.00");
    assertThat(data.path("stopReasonCode").asText()).isEqualTo("REACHED");
    assertThat(data.path("stopReason").asText()).isEqualTo("목표 전투력에 닿았어요.");
    assertThat(data.path("reached").asBoolean()).isTrue();
    var steps = data.path("steps");
    assertThat(steps.size()).isEqualTo(3);
    var first = steps.get(0);
    assertThat(first.path("order").asInt()).isEqualTo(1);
    assertThat(first.path("candidateId").asText()).isEqualTo("GEM_LEVEL:0:9");
    assertThat(first.path("target").asText()).isEqualTo("테스트 스킬 · 8레벨 겁화");
    assertThat(first.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("24.00");
    assertThat(first.path("combatPowerAfter").decimalValue()).isEqualByComparingTo("3024.00");
    assertThat(first.at("/cost/code").asText()).isEqualTo("GEM_UPGRADE");
    assertThat(first.at("/cost/amount").asLong()).isEqualTo(620_000);
    assertThat(first.path("cumulativeCost").asLong()).isEqualTo(620_000);
    assertThat(first.at("/change/kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(first.at("/change/level").asInt()).isEqualTo(9);
    // 같은 칸 둘째 단계: 10레벨 3,000,000 − 9레벨 1,000,000. 합 2,620,000 = 10레벨 매입가 − 8레벨 판매 기대가(380,000).
    var second = steps.get(1);
    assertThat(second.path("candidateId").asText()).isEqualTo("GEM_LEVEL:0:10");
    assertThat(second.path("from").asText()).isEqualTo("9레벨");
    assertThat(second.at("/cost/code").asText()).isEqualTo("GEM_STEP");
    assertThat(second.at("/cost/amount").asLong()).isEqualTo(2_000_000);
    assertThat(second.path("cumulativeCost").asLong()).isEqualTo(3_000_000 - 380_000);
    // 보석 8→10 한 번에 넣은 시뮬레이터 값(48.07)과 같다.
    assertThat(second.path("combatPowerAfter").decimalValue()).isEqualByComparingTo("3048.07");
    var third = steps.get(2);
    assertThat(third.path("candidateId").asText()).isEqualTo("EQUIPMENT_QUALITY:0:100");
    assertThat(third.at("/cost/status").asText()).isEqualTo("UNKNOWN");
    assertThat(third.path("cumulativeCost").isNull()).isTrue();
    assertThat(third.path("combatPowerPer10kGold").isNull()).isTrue();
    assertThat(data.path("totalCost").isNull()).isTrue();
    assertThat(data.path("unpricedSteps").asInt()).isEqualTo(1);
    assertThat(data.path("finalCombatPower").decimalValue())
        .isEqualByComparingTo(third.path("combatPowerAfter").decimalValue())
        .isGreaterThanOrEqualTo(new BigDecimal("3100"));

    // 같은 캐릭터·목표는 캐시에서 준다(시뮬레이터 0회).
    clearInvocations(simulator);
    var again =
        get(
            "/api/v1/characters/spec-up/goal?characterName="
                + encode("스펙목표")
                + "&targetCombatPower=3100");
    assertThat(mapper.readTree(again.body()).path("data")).isEqualTo(data);
    verify(simulator, never())
        .simulate(
            org.mockito.ArgumentMatchers.any(
                com.loakraken.character.application.CharacterResult.class),
            org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void 목표에_보석만으로_닿으면_비용이_모두_있고_후보가_떨어지면_NO_CANDIDATE다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙목표둘");
    var data =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up/goal?characterName="
                        + encode("스펙목표둘")
                        + "&targetCombatPower=3030")
                    .body())
            .path("data");
    assertThat(data.path("stopReasonCode").asText()).isEqualTo("REACHED");
    assertThat(data.path("steps").size()).isEqualTo(2);
    assertThat(data.path("totalCost").asLong()).isEqualTo(2_620_000);
    assertThat(data.path("unpricedSteps").asInt()).isZero();

    var far =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up/goal?characterName="
                        + encode("스펙목표둘")
                        + "&targetCombatPower=5000")
                    .body())
            .path("data");
    assertThat(far.path("stopReasonCode").asText()).isEqualTo("NO_CANDIDATE");
    assertThat(far.path("reached").asBoolean()).isFalse();
    assertThat(far.path("steps").size()).isEqualTo(3);
    assertThat(far.path("stopReason").asText()).isEqualTo("더 올릴 수 있는 항목이 없어 여기서 멈췄어요.");
  }

  @Test
  void 목표가_지금_이하면_빈_경로이고_2배를_넘거나_형식이_틀리면_400이다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙목표셋");
    var already =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up/goal?characterName="
                        + encode("스펙목표셋")
                        + "&targetCombatPower=2999.5")
                    .body())
            .path("data");
    assertThat(already.path("stopReasonCode").asText()).isEqualTo("ALREADY_REACHED");
    assertThat(already.path("reached").asBoolean()).isTrue();
    assertThat(already.path("steps").size()).isZero();
    assertThat(already.path("totalCost").asLong()).isZero();
    var tooFar =
        get(
            "/api/v1/characters/spec-up/goal?characterName="
                + encode("스펙목표셋")
                + "&targetCombatPower=6000.01");
    assertThat(tooFar.statusCode()).isEqualTo(400);
    assertThat(tooFar.body()).contains("SPEC_UP_GOAL_OUT_OF_RANGE");
    for (var target : List.of("0", "-1", "abc", "3100.123"))
      assertThat(
              get("/api/v1/characters/spec-up/goal?characterName="
                      + encode("스펙목표셋")
                      + "&targetCombatPower="
                      + target)
                  .statusCode())
          .as(target)
          .isEqualTo(400);
    var missing =
        get(
            "/api/v1/characters/spec-up/goal?characterName="
                + encode("스펙없음")
                + "&targetCombatPower=3100");
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(missing.body()).contains("CHARACTER_NOT_CACHED");
  }

  @Test
  void 광휘_보석은_효과와_같은_종류의_시세로_비용을_낸다() throws Exception {
    // 공식 경매장 보석 검색에는 광휘가 없다. 피해 증가 광휘 = 겁화 시세, 귀속이면 판매가를 빼지 않는다(P0 #455 B1).
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙광휘");
    var guide =
        mapper
            .readTree(get("/api/v1/characters/spec-up?characterName=" + encode("스펙광휘")).body())
            .path("data");
    var gem = find(guide.path("candidates"), "GEM_LEVEL:0:9");
    assertThat(gem.path("target").asText()).isEqualTo("테스트 스킬 · 8레벨 광휘");
    assertThat(gem.at("/cost/code").asText()).isEqualTo("GEM_UPGRADE");
    assertThat(gem.at("/cost/amount").asLong()).isEqualTo(620_000);
    var goal =
        mapper
            .readTree(
                get("/api/v1/characters/spec-up/goal?characterName="
                        + encode("스펙광휘")
                        + "&targetCombatPower=3030")
                    .body())
            .path("data");
    assertThat(goal.path("totalCost").asLong()).isEqualTo(3_000_000 - 380_000);
    assertThat(goal.at("/steps/1/cost/code").asText()).isEqualTo("GEM_STEP");

    search("스펙광휘귀속");
    var bound =
        find(
            mapper
                .readTree(
                    get("/api/v1/characters/spec-up?characterName=" + encode("스펙광휘귀속")).body())
                .at("/data/candidates"),
            "GEM_LEVEL:0:9");
    assertThat(bound.at("/cost/code").asText()).isEqualTo("GEM_BUY_BOUND");
    assertThat(bound.at("/cost/amount").asLong()).isEqualTo(1_000_000);
  }

  private void search(String name) throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(name)).statusCode())
        .isEqualTo(200);
  }

  private static List<GemCell> gemCells(boolean ready) {
    var at = Instant.parse("2026-10-11T07:00:00Z");
    var cells = new ArrayList<GemCell>();
    for (var kind : List.of("겁화", "작열"))
      for (int level = 10; level >= 1; level--) {
        long price =
            level == 10
                ? 3_000_000
                : level == 9 ? 1_000_000 : level == 8 ? 400_000 : 10_000L * level;
        var cell = ready ? new AuctionCell(price, 5, at) : null;
        cells.add(
            new GemCell(
                kind,
                level,
                level + "레벨 " + kind + "의 보석",
                cell,
                null,
                ready ? MarketAuctionService.Status.READY : MarketAuctionService.Status.PENDING));
      }
    return cells;
  }

  private static JsonNode find(JsonNode candidates, String id) {
    for (var candidate : candidates) if (id.equals(candidate.path("id").asText())) return candidate;
    throw new AssertionError(id + " 후보가 없습니다");
  }

  /** 전투력 시뮬레이터 테스트와 같은 세팅(보석 8레벨 겁화, 스톤 보너스 1.50%, 완갑 1.00%). */
  private FetchedCharacter fetched(String name) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "버서커",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            List.of());
    var weapon =
        new CharacterLoadout.Equipment(
            "무기",
            name.contains("업화")
                ? "+20 운명의 업화 검"
                : name.contains("전율") ? "+20 운명의 전율 검" : "+20 테스트 무기",
            null,
            "고대",
            // 업화·전율 무기는 공식 툴팁처럼 상급 재련 단계(0)를 갖는다.
            name.contains("업화") || name.contains("전율")
                ? new CharacterLoadout.EquipmentDetail(
                    20, 90, 1700, 4, null, List.of(), List.of(), List.of(), List.of(), null,
                    List.of(), null, List.of(), 0)
                : new CharacterLoadout.EquipmentDetail(
                    20, 90, 1700, 4, null, List.of(), List.of(), List.of(), List.of()));
    var necklace =
        new CharacterLoadout.Equipment(
            "목걸이",
            "테스트 목걸이",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                80,
                null,
                4,
                null,
                List.of(),
                List.of(),
                List.of(new CharacterLoadout.PolishEffect("추가 피해", "+1.60%", EffectGrade.MID)),
                List.of()));
    var stone =
        new CharacterLoadout.Equipment(
            "어빌리티 스톤",
            "테스트 스톤",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                null,
                List.of(
                    new CharacterLoadout.EffectGroup(
                        "무작위 각인 효과", List.of("[원한] Lv.1", "[레벨 보너스] 기본 공격력 +1.50%"))),
                List.of(new CharacterLoadout.StoneEngraving("원한", 1)),
                List.of(),
                List.of()));
    var bracelet =
        new CharacterLoadout.Equipment(
            "팔찌",
            "테스트 팔찌",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new CharacterLoadout.BraceletEffect("특화 +100", EffectGrade.LOW))));
    var armGuard =
        new CharacterLoadout.Equipment(
            "완갑",
            "+13 테스트 완갑",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                null,
                null,
                null,
                4,
                null,
                List.of(new CharacterLoadout.EffectGroup("추가 효과", List.of("기본 공격력 +1.00%"))),
                List.of(),
                List.of(),
                List.of()));
    var engravings =
        new CharacterLoadout.Engravings(
            List.of(),
            List.of(),
            List.of(
                new CharacterLoadout.PassiveEngraving(
                    1,
                    "유물",
                    3,
                    "원한",
                    // 원한 L3 S1 = 18 + 0.75 × 3 + 3 = 23.25(공식 설명 형식). 그 밖 캐릭터는 설명이 달라 UNSUPPORTED.
                    name.contains("각인") ? "적에게 주는 피해가 23.25% 증가한다." : "설명")));
    var gems =
        name.contains("빈칸")
            ? new CharacterLoadout.Gems(List.of(), new CharacterLoadout.GemEffects("", List.of()))
            : new CharacterLoadout.Gems(
            List.of(
                new CharacterLoadout.Gem(
                    0,
                    // 광휘 보석(효과는 피해 증가), 이름에 "귀속"이 있으면 귀속 광휘.
                    name.contains("광휘")
                        ? name.contains("귀속") ? "8레벨 광휘의 보석 (귀속)" : "8레벨 광휘의 보석"
                        : "8레벨 겁화의 보석",
                    null,
                    8,
                    "고대")),
            new CharacterLoadout.GemEffects(
                "기본 공격력 총합 : 0.80%",
                List.of(
                    new CharacterLoadout.GemSkill(
                        0, "테스트 스킬", List.of("피해 32.00% 증가"), "기본 공격력 0.80% 증가", null))));
    var arkPassive =
        new CharacterLoadout.ArkPassive(
            "진화", true, List.of(new CharacterLoadout.PassivePoint("진화", 100, "")), List.of());
    var arkGrid =
        new CharacterLoadout.ArkGrid(
            List.of(new CharacterLoadout.GridSlot(0, null, "질서의 해 코어", 17, "유물", List.of())),
            List.of());
    var loadout =
        new CharacterLoadout(
            List.of(weapon, necklace, stone, bracelet, armGuard),
            engravings,
            gems,
            null,
            arkPassive,
            arkGrid,
            List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
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
