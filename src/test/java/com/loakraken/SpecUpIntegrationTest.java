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
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*스펙*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 후보마다_Δ전투력과_비용과_효율을_주고_계산할_수_없는_후보는_이유와_함께_남긴다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells(true));
    search("스펙캐시");
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙캐시"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-spec-up.json", response.body());
    assertThat(response.body()).doesNotContain(SiteNames.FORBIDDEN);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("spec-up-v1");
    assertThat(data.path("simulatorRuleVersion").asText()).isEqualTo("combat-power-sim-v8");
    assertThat(data.path("combatPower").decimalValue()).isEqualByComparingTo("3000.00");
    assertThat(data.path("sort").asText()).isEqualTo("EFFICIENCY");
    // 보석은 단계별 후보(8→9, 8→10)가 각각 나오고, 무기 품질(90 → 100)도 계산한다.
    assertThat(data.path("total").asInt()).isEqualTo(3);
    var candidates = data.path("candidates");
    // 보석 8 → 9: 3000 × (1.0640/1.0576) × (1 + 3.50%)/(1 + 3.30%) = 3024.00.
    var gem = candidates.get(0);
    assertThat(gem.path("id").asText()).isEqualTo("GEM_LEVEL:0:9");
    assertThat(gem.path("kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(gem.path("target").asText()).isEqualTo("테스트 스킬 · 8레벨 겁화");
    assertThat(gem.path("skillName").asText()).isEqualTo("테스트 스킬");
    assertThat(gem.path("from").asText()).isEqualTo("8레벨");
    assertThat(gem.path("to").asText()).isEqualTo("9레벨");
    assertThat(gem.path("status").asText()).isEqualTo("OK");
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
    var gem10 = candidates.get(1);
    assertThat(gem10.path("id").asText()).isEqualTo("GEM_LEVEL:0:10");
    assertThat(gem10.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("48.07");
    assertThat(gem10.at("/cost/amount").asLong()).isEqualTo(2_620_000);
    assertThat(gem10.at("/cost/unit").asText()).isEqualTo("GOLD");
    assertThat(gem10.path("combatPowerPer10kGold").decimalValue()).isEqualByComparingTo("0.18");
    assertThat(gem10.path("suggestedTo").isNull()).isTrue();
    // 무기 품질 90 → 100: 3000 × (1 + 30%)/(1 + 26.2%) = 3090.33. 비용 근거가 없어 UNKNOWN이고 효율이 없어 보석 뒤.
    var quality = candidates.get(2);
    assertThat(quality.path("id").asText()).isEqualTo("EQUIPMENT_QUALITY:0:100");
    assertThat(quality.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("90.33");
    assertThat(quality.at("/cost/status").asText()).isEqualTo("UNKNOWN");
    assertThat(quality.at("/cost/code").asText()).isEqualTo("QUALITY_COST_UNCONFIRMED");
    assertThat(quality.path("combatPowerPer10kGold").isNull()).isTrue();
    // 나머지는 UNSUPPORTED이고, 같은 코드는 같은 문장이다.
    var codes = new ArrayList<String>();
    for (int i = 3; i < candidates.size(); i++) {
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

    marketCache.saveRelicBooks(
        new RelicBookScan(
            Instant.now(),
            1,
            List.of(
                new MarketPrice(
                    1, "유물 원한 각인서", "유물", null, 1, null, null, null, new BigDecimal("12000")))),
        Duration.ofMinutes(5));
    var response = get("/api/v1/characters/spec-up?characterName=" + encode("스펙각인"));
    var engraving =
        find(mapper.readTree(response.body()).at("/data/candidates"), "ENGRAVING_LEVEL:원한:4");
    assertThat(engraving.at("/cost/status").asText()).isEqualTo("OK");
    assertThat(engraving.at("/cost/code").asText()).isEqualTo("ENGRAVING_BOOK_BUY");
    assertThat(engraving.at("/cost/amount").asLong()).isEqualTo(60_000);
    // 18.26 × 10000 / 60000 = 3.04
    assertThat(engraving.path("combatPowerPer10kGold").decimalValue()).isEqualByComparingTo("3.04");
    assertThat(mapper.readTree(response.body()).at("/data/pricesAt").isNull()).isFalse();
    redis.delete("market:v1:relic-books");
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
    assertThat(data.path("total").asInt()).isEqualTo(3);
    assertThat(data.path("candidates").size()).isEqualTo(all - 2);
    // Δ 순이라 무기 품질(90.33)이 먼저 남는다.
    assertThat(data.at("/candidates/0/id").asText()).isEqualTo("EQUIPMENT_QUALITY:0:100");
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
            "+20 테스트 무기",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
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
        new CharacterLoadout.Gems(
            List.of(new CharacterLoadout.Gem(0, "8레벨 겁화의 보석", null, 8, "고대")),
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
