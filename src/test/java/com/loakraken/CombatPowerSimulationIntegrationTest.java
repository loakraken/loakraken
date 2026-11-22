package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterLoadout.EffectGrade;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** 전투력 시뮬레이터 A단계(#239): 캐시만 읽고 공식 호출이 없으며, 계산식 확정 전에는 모든 항목이 UNSUPPORTED다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "combat-power-sim.enabled=true")
class CombatPowerSimulationIntegrationTest {
  private static final String SERVER = "시뮬서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '시뮬%'").update();
    var keys = redis.keys("character:v*시뮬*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 캐시가_없으면_404이고_공식_호출이_없다() throws Exception {
    var response = simulate("시뮬없음", "[]");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED");
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 검색_뒤에는_캐시로_보석만_계산하고_나머지는_UNSUPPORTED다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    var search = get("/api/v1/characters/search?characterName=" + encode("시뮬캐시"));
    assertThat(search.statusCode()).isEqualTo(200);

    var response =
        simulate(
            "시뮬캐시",
            """
            [
              {"kind": "GEM_LEVEL", "slot": 0, "level": 9},
              {"kind": "EQUIPMENT_REFINE", "index": 0, "refine": 22},
              {"kind": "EQUIPMENT_QUALITY", "index": 0, "quality": 100},
              {"kind": "POLISH", "index": 1, "line": 0, "option": "추가 피해", "grade": "HIGH"},
              {"kind": "BRACELET_EFFECT", "line": 0, "name": "치명", "grade": "MID"},
              {"kind": "ENGRAVING_LEVEL", "name": "원한", "level": 4},
              {"kind": "STONE_ENGRAVING", "name": "원한", "level": 2},
              {"kind": "ARK_PASSIVE_POINT", "tree": "진화", "points": 120},
              {"kind": "ARK_GRID_CORE", "slotIndex": 0, "grade": "고대", "point": 20},
              {"kind": "GEM_LEVEL", "slot": 0, "level": 10}
            ]
            """);
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-combat-power-simulate.json", response.body());
    // 다른 사이트 이름·간접 표현 금지(#261).
    SiteNames.assertAbsent(response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("combat-power-sim-v8");
    // 시뮬레이션이 읽은 캐시가 검색 응답과 같은지 웹이 비교한다(PM-프론트 18:31).
    assertThat(data.path("fetchedAt").asText())
        .isEqualTo(mapper.readTree(search.body()).at("/data/fetchedAt").asText());
    // 보석 8 → 10(뒤의 변경이 이김): 3000 × (1.0704/1.0576) × (1 + 3.70%)/(1 + 3.30%) = 3048.07.
    // 이어서 무기 품질 90 → 100(추가 피해 10 + 20 × (q/100)²): × 1.30/1.262 = 3139.85.
    // 기본 공격력 % = 보석 0.80 → 1.20 + 스톤 레벨 보너스 1.50 + 완갑 추가 효과 1.00.
    // 이어서 목걸이 연마 추가 피해 +1.60% → +2.60%(#325, c = x/1.3): × 1.02/(1 + 0.016/1.3) = 3163.70.
    // 이어서 진화 100 → 120(1 + 0.0075 × (P − 40), 모델값): × 1.60/1.45.
    // 이어서 아크 그리드 질서의 해 유물 17P(7.5%) → 고대 20P(8 + 1.0 = 9%): × 1.09/1.075. 합 3207.85 × 1.60/1.45 =
    // 3539.70.
    assertThat(response.body()).contains("\"before\":3000.00", "\"after\":3539.70");
    assertThat(data.path("modelBefore").isNull()).isTrue();
    assertThat(data.path("partial").asBoolean()).isTrue();
    // 같은 보석 slot은 뒤의 변경(10)이 이기고 자리는 처음 나온 곳을 지킨다.
    var contributions = data.path("contributions");
    assertThat(contributions.size()).isEqualTo(9);
    assertThat(data.path("unsupportedCount").asInt()).isEqualTo(4);
    assertThat(contributions.at("/0/kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(contributions.at("/0/from").asText()).isEqualTo("8");
    assertThat(contributions.at("/0/to").asText()).isEqualTo("10");
    assertThat(contributions.at("/0/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/0/delta").decimalValue()).isEqualByComparingTo("48.07");
    assertThat(contributions.at("/1/from").asText()).isEqualTo("20");
    assertThat(contributions.at("/2/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/2/delta").decimalValue()).isEqualByComparingTo("91.78");
    // 연마 줄 교체(L31-b): 공식 수치표의 상 등급 값으로 바꾼다.
    assertThat(contributions.at("/3/kind").asText()).isEqualTo("POLISH");
    assertThat(contributions.at("/3/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/3/from").asText()).isEqualTo("추가 피해 +1.60%");
    assertThat(contributions.at("/3/to").asText()).isEqualTo("추가 피해 +2.60%");
    assertThat(contributions.at("/3/delta").decimalValue()).isEqualByComparingTo("23.85");
    // 진화 포인트(PM 총괄 11-15 19:12): 모델값이라 근거에 그렇게 적는다.
    assertThat(contributions.at("/7/kind").asText()).isEqualTo("ARK_PASSIVE_POINT");
    assertThat(contributions.at("/7/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/7/basis").asText()).contains("모델값");
    assertThat(contributions.at("/8/kind").asText()).isEqualTo("ARK_GRID_CORE");
    assertThat(contributions.at("/8/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/8/delta").decimalValue()).isEqualByComparingTo("48.72");
    for (int i = 1; i < contributions.size(); i++) {
      if (i == 2 || i == 3 || i == 7 || i == 8) continue;
      assertThat(contributions.get(i).path("status").asText()).isEqualTo("UNSUPPORTED");
      assertThat(contributions.get(i).path("delta").isNull()).isTrue();
    }
    for (var item : contributions) {
      assertThat(item.path("basis").asText()).doesNotContain("로아");
      SiteNames.assertAbsent(item.path("basis").asText());
    }
    verify(detailsPort, times(1)).fetch(anyString());
  }

  @Test
  void 무기_품질과_각인을_순서대로_계산한다() throws Exception {
    // #275: 계산 검증 표본 c02(공식 전투력 11,157.79)와 같은 세팅. 무기 100→90 = 10,831.64, 이어서 원한 4→3.
    // #344: 예리한 둔기 4→3(표 17.36 → 16.62%)도 계산한다: 10,764.50 × 1.1662/1.1736 = 10,696.63.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetchedEquipment(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬장비")).statusCode())
        .isEqualTo(200);
    var response =
        simulate(
            "시뮬장비",
            """
            [
              {"kind": "EQUIPMENT_QUALITY", "index": 0, "quality": 90},
              {"kind": "ENGRAVING_LEVEL", "name": "원한", "level": 3},
              {"kind": "EQUIPMENT_QUALITY", "index": 1, "quality": 90},
              {"kind": "ENGRAVING_LEVEL", "name": "예리한 둔기", "level": 3}
            ]
            """);
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(response.body()).contains("\"before\":11157.79", "\"after\":10696.63");
    var contributions = data.path("contributions");
    assertThat(contributions.at("/0/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/0/delta").decimalValue()).isEqualByComparingTo("-326.15");
    assertThat(contributions.at("/1/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/1/delta").decimalValue()).isEqualByComparingTo("-67.14");
    // 방어구 품질은 배수 1이라 OK·0.
    assertThat(contributions.at("/2/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/2/delta").decimalValue()).isEqualByComparingTo("0");
    assertThat(contributions.at("/3/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/3/delta").decimalValue()).isEqualByComparingTo("-67.87");
    assertThat(data.path("unsupportedCount").asInt()).isZero();
    SiteNames.assertAbsent(response.body());
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.loakraken.character.application.CombatPowerSimulationService simulator;

  @Test
  void 방어구_재련_상급_재련은_시뮬레이터와_스펙업_경로_모두_계산하지_않는다() throws Exception {
    // 옵션 B(11-08) 뒤 P0 핫픽스(11-09, #870): 방어구 재련·상급 재련은 같은 시점 실측에서 약 0.66배라 시뮬레이터 화면과 스펙업 경로 모두
    // UNSUPPORTED(기존 코드
    // MODEL_UNCONFIRMED).
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetchedEquipment(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬방어구")).statusCode())
        .isEqualTo(200);
    var response =
        simulate(
            "시뮬방어구",
            """
            [
              {"kind": "EQUIPMENT_REFINE", "index": 1, "refine": 24},
              {"kind": "EQUIPMENT_ADVANCED_REFINE", "index": 1, "level": 10},
              {"kind": "EQUIPMENT_QUALITY", "index": 0, "quality": 90}
            ]
            """);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("character-combat-power-simulate-armor-refine.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    var contributions = data.path("contributions");
    var armorReason = "방어구 재련·상급 재련의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";
    for (int i = 0; i < 2; i++) {
      assertThat(contributions.get(i).path("status").asText()).isEqualTo("UNSUPPORTED");
      assertThat(contributions.get(i).path("delta").isNull()).isTrue();
      assertThat(contributions.get(i).path("basis").asText()).isEqualTo(armorReason);
      assertThat(contributions.get(i).path("reasonCode").asText()).isEqualTo("MODEL_UNCONFIRMED");
    }
    // 다른 변경은 그대로 계산한다(무기 품질 100 → 90 = -326.15, 위 테스트와 같은 값).
    assertThat(contributions.at("/2/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/2/delta").decimalValue()).isEqualByComparingTo("-326.15");
    assertThat(data.path("unsupportedCount").asInt()).isEqualTo(2);
    // 스펙업 목록·추천 순서가 부르는 경로(캐릭터 스냅숏 + 변경)도 같은 이유로 계산하지 않는다.
    var specUp =
        simulator.simulate(
            simulator.snapshot("시뮬방어구"),
            List.of(
                new com.loakraken.character.application.CombatPowerSimulationService.Change(
                    com.loakraken.character.application.CombatPowerSimulationService.Kind
                        .EQUIPMENT_REFINE,
                    null,
                    1,
                    null,
                    null,
                    24,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null)));
    assertThat(specUp.contributions().getFirst().basis()).isEqualTo(armorReason);
    assertThat(specUp.contributions().getFirst().delta()).isNull();
    // 무기 재련은 시뮬레이터 화면에서도 방어구 이유가 붙지 않는다.
    var weapon =
        simulate("시뮬방어구", "[{\"kind\": \"EQUIPMENT_REFINE\", \"index\": 0, \"refine\": 11}]");
    assertThat(weapon.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(weapon.body()).at("/data/contributions/0/basis").asText())
        .isNotEqualTo(armorReason);
  }

  @Test
  void 범위_밖은_400_대상이_없으면_409다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬검증")).statusCode())
        .isEqualTo(200);

    var outOfRange = simulate("시뮬검증", "[{\"kind\": \"GEM_LEVEL\", \"slot\": 0, \"level\": 11}]");
    assertThat(outOfRange.statusCode()).isEqualTo(400);
    assertThat(outOfRange.body()).contains("INVALID_REQUEST", "level", "0~10");

    // 보석 빼기(0레벨): 3000 × (1/1.0576) × (1 + 2.50%)/(1 + 3.30%) = 2814.64.
    var removed = simulate("시뮬검증", "[{\"kind\": \"GEM_LEVEL\", \"slot\": 0, \"level\": 0}]");
    assertThat(removed.statusCode()).isEqualTo(200);
    assertThat(removed.body())
        .contains("\"after\":2814.64", "\"delta\":-185.36", "\"status\":\"OK\"");

    var unknownKind = simulate("시뮬검증", "[{\"kind\": \"CARD_SET\"}]");
    assertThat(unknownKind.statusCode()).isEqualTo(400);

    // 빈 보석 칸(#541)은 409가 아니라 그 칸에 끼우는 계산이다(값은 보석 모델 단위 테스트가 '빼기의 역'으로 고정).
    var emptySlot = simulate("시뮬검증", "[{\"kind\": \"GEM_LEVEL\", \"slot\": 7, \"level\": 9}]");
    assertThat(emptySlot.statusCode()).isEqualTo(200);
    assertThat(emptySlot.body())
        .contains("\"status\":\"OK\"")
        .doesNotContain("CHANGE_TARGET_NOT_FOUND");

    var notRefinable =
        simulate("시뮬검증", "[{\"kind\": \"EQUIPMENT_REFINE\", \"index\": 1, \"refine\": 20}]");
    assertThat(notRefinable.statusCode()).isEqualTo(409);
  }

  @Test
  void 처리정지_목록이어도_캐시가_있으면_일반_캐릭터와_같게_계산한다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬제외")).statusCode())
        .isEqualTo(200);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('시뮬제외', NULL, 'USER_REQUEST')")
        .update();
    // #363: 404로 처리정지 여부를 드러내지 않는다. 저장하지 않는 메모리 계산이다.
    var response = simulate("시뮬제외", "[]");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("CHARACTER_NOT_CACHED");
  }

  @Test
  void 입력_범위는_검증_전_예시로_표시한다() throws Exception {
    var response = get("/api/v1/characters/combat-power/options");
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("combat-power-options.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("verified").asBoolean()).isFalse();
    assertThat(data.path("gemLevels").size()).isEqualTo(11);
    assertThat(data.path("refineMax").asInt()).isEqualTo(25);
    // 연마 수치(#251): 공식 툴팁 표기 그대로, 부위는 공식 연마 확률 정보.
    var polish = data.at("/polishValues/options");
    assertThat(data.at("/polishValues/accessoryGrade").asText()).isEqualTo("고대");
    assertThat(polish.at("/0/option").asText()).isEqualTo("추가 피해");
    assertThat(polish.at("/0/high").asText()).isEqualTo("+2.60%");
    var allyDamage =
        polish
            .valueStream()
            .filter(item -> item.path("option").asText().equals("아군 피해량 강화 효과"))
            .findFirst()
            .orElseThrow();
    // 빈 칸 3개는 추가 표본에서 등급 색으로 확인했다(polish-values-v2).
    assertThat(allyDamage.path("mid").asText()).isEqualTo("+4.50%");
    assertThat(data.at("/polishValues/itemLevel").asInt()).isEqualTo(1680);
    assertThat(polish.at("/0/parts/0").asText()).isEqualTo("목걸이");
    assertThat(
            polish
                .valueStream()
                .filter(item -> item.path("option").asText().equals("공격력"))
                .map(item -> item.path("percent").asBoolean())
                .toList())
        .containsExactly(true, false);
    verifyNoInteractions(detailsPort);
  }

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
                null, // 공식 장신구 툴팁처럼 아이템 레벨 없음 — 연마 값(추가 피해 +1.60%)이 1680 표의 목걸이 값이라 같은 장신구
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
            List.of(new CharacterLoadout.PassiveEngraving(1, "유물", 3, "원한", "설명")));
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

  private FetchedCharacter fetchedEquipment(String name) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "슬레이어",
            70,
            new BigDecimal("1760"),
            new BigDecimal("11157.79"),
            null,
            null,
            null,
            null,
            List.of());
    var weapon =
        new CharacterLoadout.Equipment(
            "무기",
            "+10 테스트 에스더 무기",
            null,
            "에스더",
            new CharacterLoadout.EquipmentDetail(
                10,
                100,
                null,
                4,
                null,
                List.of(new CharacterLoadout.EffectGroup("추가 효과", List.of("추가 피해 +30.00%"))),
                List.of(),
                List.of(),
                List.of()));
    var helmet =
        new CharacterLoadout.Equipment(
            "투구",
            "+25 테스트 투구",
            null,
            "고대",
            new CharacterLoadout.EquipmentDetail(
                25, 100, null, 4, null, List.of(), List.of(), List.of(), List.of()));
    var engravings =
        new CharacterLoadout.Engravings(
            List.of(),
            List.of(),
            List.of(
                new CharacterLoadout.PassiveEngraving(
                    null, "유물", 4, "원한", "보스 및 레이드 몬스터에게 주는 피해가 21.00% 증가하지만, 받는 피해가 20.00% 증가한다."),
                new CharacterLoadout.PassiveEngraving(
                    null, "유물", 4, "예리한 둔기", "치명타 피해가 52.00% 증가한다.")));
    var loadout =
        new CharacterLoadout(
            List.of(weapon, helmet), engravings, null, null, null, null, List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
  }

  @Test
  void 팔찌_줄을_빼거나_바꾸고_팔찌_전투력_기여를_준다() throws Exception {
    // #327: 기준 캐릭터와 같은 팔찌(전투 특성 합 2,469, 도약 70) → 기여 12.60%, 추가 피해 +3.50% 빼기 3,345.24 → 3,257.54.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetchedBracelet(invocation.getArgument(0)));
    var search = get("/api/v1/characters/search?characterName=" + encode("시뮬팔찌"));
    assertThat(search.statusCode()).isEqualTo(200);
    // 검색 응답 팔찌 효율(#345): 지금 ÷ 팔찌 뺀 전투력 − 1(기여 12.60%와 같은 모델 → 1/0.874 − 1 = 14.42%).
    // 줄별은 그 줄만 뺐을 때, 팔찌 도약은 마지막 한 줄. 시뮬레이터 플래그와 무관.
    ApiContractFixture.write("character-search-bracelet-efficiency.json", search.body());
    var efficiency = mapper.readTree(search.body()).at("/data/braceletEfficiency");
    assertThat(efficiency.path("status").asText()).isEqualTo("OK");
    assertThat(efficiency.path("percent").decimalValue()).isEqualByComparingTo("14.42");
    assertThat(efficiency.path("reasonCode").isNull()).isTrue();
    assertThat(efficiency.at("/lines/0/text").asText()).isEqualTo("특화 +81");
    assertThat(efficiency.at("/lines/0/percent").decimalValue()).isEqualByComparingTo("1.42");
    assertThat(efficiency.at("/lines/1/percent").decimalValue()).isEqualByComparingTo("0.00");
    // 추가 피해 +3.50% = 1.026922 − 1 = 2.69%.
    assertThat(efficiency.at("/lines/3/percent").decimalValue()).isEqualByComparingTo("2.69");
    // 팔찌 도약 18(도약 합 70) = 1.14 / 1.104 − 1 = 3.26%.
    var leapLine = efficiency.path("lines").get(efficiency.path("lines").size() - 1);
    assertThat(leapLine.path("text").asText()).isEqualTo("팔찌 도약 +18");
    assertThat(leapLine.path("percent").decimalValue()).isEqualByComparingTo("3.26");
    assertThat(leapLine.path("status").asText()).isEqualTo("OK");
    assertThat(efficiency.path("ruleVersion").asText()).startsWith("combat-power-sim-");
    var response =
        simulate("시뮬팔찌", "[{\"kind\": \"BRACELET_EFFECT\", \"line\": 3, \"name\": \"없음\"}]");
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(data.path("after").decimalValue()).isEqualByComparingTo("3257.54");
    var upgrade =
        mapper
            .readTree(
                simulate(
                        "시뮬팔찌",
                        "[{\"kind\": \"BRACELET_EFFECT\", \"line\": 3, \"name\": \"추가 피해\", \"grade\": \"HIGH\"}]")
                    .body())
            .path("data");
    assertThat(upgrade.at("/contributions/0/to").asText()).isEqualTo("추가 피해 HIGH");
    assertThat(upgrade.path("after").decimalValue()).isEqualByComparingTo("3357.77");
    var contributions =
        get("/api/v1/characters/combat-power/contributions?characterName=" + encode("시뮬팔찌"));
    assertThat(contributions.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-combat-power-contributions.json", contributions.body());
    var items = mapper.readTree(contributions.body()).at("/data/items");
    assertThat(items.at("/0/kind").asText()).isEqualTo("BRACELET");
    assertThat(items.at("/0/status").asText()).isEqualTo("OK");
    assertThat(items.at("/0/contributionPct").decimalValue()).isEqualByComparingTo("12.60");
    SiteNames.assertAbsent(contributions.body());
    var options =
        mapper.readTree(get("/api/v1/characters/combat-power/options").body()).path("data");
    assertThat(options.at("/braceletOptions").size()).isEqualTo(13);
    assertThat(options.toString()).contains("추가 피해 +3.50%");
  }

  @Test
  void 진화_깨달음_포인트는_트리마다_독립_배수로_계산하고_깨달음_101번째_포인트는_전투력이_없다() throws Exception {
    // PM 총괄 11-15 19:12: 진화 (1 + 0.0075·max(P − 40, 0), 모델값), 도약 (1 + 0.002·P). 깨달음은 M13(PM 총괄
    // 11-22)부터 1 + 0.007·min(P, 100).
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              String name = invocation.getArgument(0);
              int enlightenment = name.endsWith("초과") ? 101 : 100;
              return fetchedBracelet(
                  name,
                  List.of(
                      new CharacterLoadout.PassivePoint("진화", 140, ""),
                      new CharacterLoadout.PassivePoint("깨달음", enlightenment, ""),
                      new CharacterLoadout.PassivePoint("도약", 70, "")));
            });
    for (var name : List.of("시뮬진화", "시뮬진화초과"))
      assertThat(get("/api/v1/characters/search?characterName=" + encode(name)).statusCode())
          .isEqualTo(200);
    // 진화 140 → 120: 3,345.24 × 1.60 / 1.75 = 3,058.51.
    var evolution = one("시뮬진화", "진화", 120);
    assertThat(evolution.at("/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(evolution.at("/contributions/0/target").asText()).isEqualTo("아크 패시브 진화");
    assertThat(evolution.at("/contributions/0/basis").asText()).contains("모델값");
    assertThat(evolution.path("after").decimalValue()).isEqualByComparingTo("3058.51");
    // 1티어 40포인트까지는 전투력이 없다: 140 → 40 = × 1 / 1.75.
    assertThat(one("시뮬진화", "진화", 40).path("after").decimalValue()).isEqualByComparingTo("1911.57");
    assertThat(one("시뮬진화", "진화", 30).path("after").decimalValue()).isEqualByComparingTo("1911.57");
    // 진화 포인트 최대 140(공식 공지 13307): 140은 계산(배수 1), 141은 웹을 거치지 않아도 400(코드 리뷰 #965 P2).
    assertThat(one("시뮬진화", "진화", 140).path("after").decimalValue()).isEqualByComparingTo("3345.24");
    var over =
        simulate("시뮬진화", "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"진화\", \"points\": 141}]");
    assertThat(over.statusCode()).isEqualTo(400);
    assertThat(over.body()).contains("INVALID_REQUEST", "points", "0~140");
    // 깨달음 최대 101: 102는 400, 100·101은 계산(101번째 포인트는 전투력 없음, 아래).
    var overEnlightenment =
        simulate("시뮬진화", "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"깨달음\", \"points\": 102}]");
    assertThat(overEnlightenment.statusCode()).isEqualTo(400);
    assertThat(overEnlightenment.body()).contains("INVALID_REQUEST", "points", "0~101");
    assertThat(one("시뮬진화", "깨달음", 100).at("/contributions/0/status").asText()).isEqualTo("OK");
    // 깨달음 100 → 90: 3,345.24 × 1.63 / 1.70 = 3,207.49.
    var enlightenment = one("시뮬진화", "깨달음", 90);
    assertThat(enlightenment.at("/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(enlightenment.path("after").decimalValue()).isEqualByComparingTo("3207.49");
    // 세 트리를 한 번에: 각 배수를 곱한다. 3,345.24 × 1.60/1.75 × 1.63/1.70 × 1.12/1.14 = 2,881.12.
    var all =
        mapper
            .readTree(
                simulate(
                        "시뮬진화",
                        """
                        [{"kind": "ARK_PASSIVE_POINT", "tree": "진화", "points": 120},
                         {"kind": "ARK_PASSIVE_POINT", "tree": "깨달음", "points": 90},
                         {"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 60}]
                        """)
                    .body())
            .path("data");
    assertThat(all.path("contributions").size()).isEqualTo(3);
    assertThat(all.path("partial").asBoolean()).isFalse();
    assertThat(all.path("after").decimalValue()).isEqualByComparingTo("2881.12");
    // M13(PM 총괄 11-22, 캐릭터 25명 공식 전투력 대조): 깨달음 101번째 포인트는 전투력이 없다 — 배수는 1 + 0.007 × min(P, 100).
    // 100 → 101은 × 1(변화 없음), 101 → 90은 × 1.63 / 1.70(= −4.118%).
    var toCap = one("시뮬진화", "깨달음", 101);
    assertThat(toCap.at("/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(toCap.path("delta").decimalValue()).isEqualByComparingTo("0");
    var fromCap = one("시뮬진화초과", "깨달음", 90);
    assertThat(fromCap.at("/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(fromCap.path("partial").asBoolean()).isFalse();
    assertThat(fromCap.path("deltaPercent").decimalValue()).isEqualByComparingTo("-4.118");
    assertThat(fromCap.at("/contributions/0/basis").asText()).contains("101번째 포인트");
  }

  private JsonNode one(String name, String tree, int points) throws Exception {
    var response =
        simulate(
            name,
            "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \""
                + tree
                + "\", \"points\": "
                + points
                + "}]");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  @Test
  void 도약_포인트는_독립_배수로_계산한다() throws Exception {
    // 도약 합 P의 배수 (1 + 0.002·P)(팔찌 도약과 같은 계수). 비교 기준 A·B가 같은 식이다.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetchedBracelet(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬도약")).statusCode())
        .isEqualTo(200);
    // 70 → 50: 3,345.24 × 1.10 / 1.14 = 3,227.86.
    var down =
        mapper
            .readTree(
                simulate(
                        "시뮬도약",
                        "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"도약\", \"points\": 50}]")
                    .body())
            .path("data");
    assertThat(down.at("/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(down.at("/contributions/0/target").asText()).isEqualTo("아크 패시브 도약");
    assertThat(down.at("/contributions/0/from").asText()).isEqualTo("70");
    assertThat(down.at("/contributions/0/to").asText()).isEqualTo("50");
    assertThat(down.path("after").decimalValue()).isEqualByComparingTo("3227.86");
    assertThat(down.at("/contributions/0/delta").decimalValue()).isEqualByComparingTo("-117.38");
    assertThat(down.path("partial").asBoolean()).isFalse();
    // 같은 대상은 뒤의 변경이 이긴다(10 → 50 = 70 → 50).
    var later =
        mapper
            .readTree(
                simulate(
                        "시뮬도약",
                        """
                        [{"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 10},
                         {"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 50}]
                        """)
                    .body())
            .path("data");
    assertThat(later.path("contributions").size()).isEqualTo(1);
    assertThat(later.path("after").decimalValue()).isEqualByComparingTo("3227.86");
    // 도약 최대 70(고대 장비 18 + 전투 레벨 40 + 랭크 12, 공식 가이드 2026-06-24): 넘으면 웹을 거치지 않아도 400(코드 리뷰 web #1706
    // P2).
    var over =
        simulate("시뮬도약", "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"도약\", \"points\": 71}]");
    assertThat(over.statusCode()).isEqualTo(400);
    assertThat(over.body()).contains("INVALID_REQUEST", "points", "0~70");
    var overLater =
        simulate(
            "시뮬도약",
            """
            [{"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 50},
             {"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 80}]
            """);
    assertThat(overLater.statusCode()).isEqualTo(400);
    assertThat(
            simulate(
                    "시뮬도약", "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"도약\", \"points\": 70}]")
                .statusCode())
        .isEqualTo(200);
    // 같은 값이면 배수 1.
    var same =
        mapper
            .readTree(
                simulate(
                        "시뮬도약",
                        "[{\"kind\": \"ARK_PASSIVE_POINT\", \"tree\": \"도약\", \"points\": 70}]")
                    .body())
            .path("data");
    assertThat(same.path("after").decimalValue()).isEqualByComparingTo("3345.24");
    // 팔찌 줄 교체와 도약을 한 번에(계산 검증 11-03 판정): 팔찌 줄은 도약을 움직이지 않고, 입력한 도약(팔찌 +18 포함한 최종 합)만 한 번 곱한다.
    // 3,345.24 ÷ (1 + 2.6923%)(추가 피해 +3.50% 빼기) × 1.104/1.14 = 3,154.67(끝에서 한 번 반올림).
    // 가르는 값: 팔찌 도약 18까지 또 빼면(이중 계산, × 0.93783934) 3,055.05, 입력 도약을 팔찌 빼기에 묻으면(× 1) 3,257.54.
    for (var body :
        List.of(
            """
            [{"kind": "BRACELET_EFFECT", "line": 3, "name": "없음"},
             {"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 52}]
            """,
            """
            [{"kind": "ARK_PASSIVE_POINT", "tree": "도약", "points": 52},
             {"kind": "BRACELET_EFFECT", "line": 3, "name": "없음"}]
            """)) {
      var both = mapper.readTree(simulate("시뮬도약", body).body()).path("data");
      assertThat(both.path("partial").asBoolean()).isFalse();
      assertThat(both.path("after").decimalValue())
          .isEqualByComparingTo("3154.67")
          .isNotEqualByComparingTo("3055.05")
          .isNotEqualByComparingTo("3257.54");
    }
  }

  @Test
  void 서포터와_판정_불가_캐릭터는_딜러_식을_쓰지_않는다() throws Exception {
    // PM-백엔드 P0(10-11 20:23): 서포터 전투력은 케어 + 버프라 식이 다르다. 깨달음 노드로 딜러일 때만 계산한다.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              String name = invocation.getArgument(0);
              return fetchedRole(name, name.endsWith("서폿") ? "깨달음 1티어 절실한 구원 Lv.1" : null);
            });
    for (var name : List.of("시뮬바드서폿", "시뮬바드모름"))
      assertThat(get("/api/v1/characters/search?characterName=" + encode(name)).statusCode())
          .isEqualTo(200);
    // 검색 응답 팔찌 효율도 같은 상수로 UNSUPPORTED(#345).
    var supportSearch =
        mapper
            .readTree(get("/api/v1/characters/search?characterName=" + encode("시뮬바드서폿")).body())
            .at("/data/braceletEfficiency");
    assertThat(supportSearch.path("status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(supportSearch.path("reasonCode").asText()).isEqualTo("SUPPORTER_MODEL_PENDING");
    assertThat(supportSearch.at("/lines/0/reasonCode").asText())
        .isEqualTo("SUPPORTER_MODEL_PENDING");
    assertThat(supportSearch.path("percent").isNull()).isTrue();
    var support =
        mapper
            .readTree(
                simulate(
                        "시뮬바드서폿",
                        "[{\"kind\": \"BRACELET_EFFECT\", \"line\": 3, \"name\": \"없음\"}]")
                    .body())
            .path("data");
    assertThat(support.at("/contributions/0/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(support.at("/contributions/0/basis").asText())
        .isEqualTo("서포터 전투력 계산식은 아직 적용하지 않았습니다.");
    assertThat(support.path("after").decimalValue()).isEqualByComparingTo("3345.24");
    var unknown =
        mapper
            .readTree(
                simulate(
                        "시뮬바드모름",
                        "[{\"kind\": \"BRACELET_EFFECT\", \"line\": 3, \"name\": \"없음\"}]")
                    .body())
            .path("data");
    assertThat(unknown.at("/contributions/0/basis").asText())
        .isEqualTo("딜러·서포터를 판정하지 못해 계산하지 않았습니다.");
    var contributions =
        mapper
            .readTree(
                get("/api/v1/characters/combat-power/contributions?characterName="
                        + encode("시뮬바드서폿"))
                    .body())
            .at("/data/items/0");
    assertThat(contributions.path("status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(contributions.path("contributionPct").isNull()).isTrue();
  }

  private FetchedCharacter fetchedRole(String name, String node) {
    var base = fetchedBracelet(name);
    var profile = base.details().profile();
    var supportProfile =
        new CharacterProfile(
            name,
            SERVER,
            "바드",
            70,
            profile.itemLevel(),
            profile.combatPower(),
            null,
            null,
            null,
            null,
            profile.stats());
    var loadout = base.details().loadout();
    var effects =
        node == null
            ? List.<CharacterLoadout.PassiveEffect>of()
            : List.of(new CharacterLoadout.PassiveEffect("깨달음", node, null));
    var arkPassive =
        new CharacterLoadout.ArkPassive("깨달음", true, loadout.arkPassive().points(), effects);
    var next =
        new CharacterLoadout(loadout.equipment(), null, null, null, arkPassive, null, List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(supportProfile, next, List.of()), "{}", "[]");
  }

  private FetchedCharacter fetchedBracelet(String name) {
    return fetchedBracelet(name, List.of(new CharacterLoadout.PassivePoint("도약", 70, "")));
  }

  private FetchedCharacter fetchedBracelet(
      String name, List<CharacterLoadout.PassivePoint> passivePoints) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "버서커",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3345.24"),
            null,
            null,
            null,
            null,
            List.of(
                new CharacterProfile.Stat("치명", "669", List.of()),
                new CharacterProfile.Stat("특화", "1700", List.of()),
                new CharacterProfile.Stat("신속", "100", List.of())));
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
                new CharacterLoadout.PointBonus("도약", 18),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CharacterLoadout.BraceletEffect("특화 +81", EffectGrade.MID),
                    new CharacterLoadout.BraceletEffect("전투자원 자연 회복량 +8.00%", EffectGrade.MID),
                    new CharacterLoadout.BraceletEffect("치명타 피해 +8.40%", EffectGrade.MID),
                    new CharacterLoadout.BraceletEffect("추가 피해 +3.50%", EffectGrade.MID),
                    new CharacterLoadout.BraceletEffect(
                        "추가 피해가 2.5% 증가한다.\n악마 및 대악마 계열 피해량이 2.5% 증가한다.", EffectGrade.LOW))));
    var arkPassive = new CharacterLoadout.ArkPassive("진화", true, passivePoints, List.of());
    var loadout =
        new CharacterLoadout(List.of(bracelet), null, null, null, arkPassive, null, List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
  }

  @Test
  void 완갑과_팔찌_주스탯_줄은_계산하지_않고_다른_팔찌_줄은_그대로다() throws Exception {
    // PM 총괄 11-09: 완갑과 팔찌 힘·민첩·지능 줄은 주 스탯 → 전투력 경로라(같은 시점 실측에서 약 1.524배 작음) 계산하지 않는다.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var bracelet = old.equipment().getFirst();
              var effects = new java.util.ArrayList<>(bracelet.detail().braceletEffects());
              effects.add(new CharacterLoadout.BraceletEffect("힘 +12000", EffectGrade.MID));
              var detail = bracelet.detail();
              var withMainStat =
                  new CharacterLoadout.Equipment(
                      bracelet.type(),
                      bracelet.name(),
                      bracelet.icon(),
                      bracelet.grade(),
                      new CharacterLoadout.EquipmentDetail(
                          detail.refineLevel(),
                          detail.quality(),
                          detail.itemLevel(),
                          detail.tier(),
                          detail.arkPassivePoint(),
                          detail.effects(),
                          detail.stoneEngravings(),
                          detail.polishEffects(),
                          List.copyOf(effects)));
              var armGuard =
                  new CharacterLoadout.Equipment(
                      "완갑",
                      "+16 테스트 팔 보호구",
                      null,
                      "유물",
                      new CharacterLoadout.EquipmentDetail(
                          16, null, null, 4, null, List.of(), List.of(), List.of(), List.of()));
              var loadout =
                  new CharacterLoadout(
                      List.of(withMainStat, armGuard),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      old.arkGrid(),
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬주스탯")).statusCode())
        .isEqualTo(200);
    var response =
        simulate(
            "시뮬주스탯",
            """
            [
              {"kind": "BRACELET_EFFECT", "line": 3, "name": "없음"},
              {"kind": "BRACELET_EFFECT", "line": 5, "name": "추가 피해", "grade": "MID"},
              {"kind": "ARM_GUARD", "level": 17, "grade": "유물"}
            ]
            """);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write(
        "character-combat-power-simulate-main-stat-held.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    var contributions = data.path("contributions");
    // 추가 피해 +3.50% 빼기는 그대로 계산한다(위 팔찌 테스트와 같은 3,345.24 → 3,257.54).
    assertThat(contributions.at("/0/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/0/delta").decimalValue()).isEqualByComparingTo("-87.70");
    // 힘 +12000 줄을 바꾸면 주 스탯 경로라 계산하지 않는다.
    assertThat(contributions.at("/1/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(contributions.at("/1/delta").isNull()).isTrue();
    assertThat(contributions.at("/1/reasonCode").asText()).isEqualTo("MODEL_UNCONFIRMED");
    assertThat(contributions.at("/1/basis").asText())
        .isEqualTo("팔찌 힘·민첩·지능 줄의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.");
    // 완갑도 같은 경로라 계산하지 않는다.
    assertThat(contributions.at("/2/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(contributions.at("/2/reasonCode").asText()).isEqualTo("MODEL_UNCONFIRMED");
    assertThat(contributions.at("/2/basis").asText())
        .isEqualTo("팔 보호구 재련의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.");
    assertThat(data.path("after").decimalValue()).isEqualByComparingTo("3257.54");
    assertThat(data.path("unsupportedCount").asInt()).isEqualTo(2);
  }

  @Test
  void 검색_응답에_아크_그리드_효율을_준다() throws Exception {
    // 계산 검증 #368 표본 기준 캐릭터와 같은 코어 6개·젬 옵션: 아크 그리드 38.64%, 코어 합 32.96%, 젬 4.27%.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var grid =
                  new CharacterLoadout.ArkGrid(
                      List.of(
                          gridSlot(0, "질서의 해 코어 : 직업 스킬", 17, "고대"),
                          gridSlot(1, "질서의 별 코어 : 직업 스킬", 18, "유물"),
                          gridSlot(2, "질서의 달 코어 : 직업 스킬", 18, "유물"),
                          gridSlot(3, "혼돈의 달 코어 : 불타는 일격", 17, "고대"),
                          gridSlot(4, "혼돈의 해 코어 : 현란한 공격", 17, "유물"),
                          gridSlot(5, "혼돈의 별 코어 : 공격", 17, "유물")),
                      List.of(
                          new CharacterLoadout.GridEffect("공격력", 28),
                          new CharacterLoadout.GridEffect("추가 피해", 51),
                          new CharacterLoadout.GridEffect("보스 피해", 4)));
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      grid,
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    var search = get("/api/v1/characters/search?characterName=" + encode("시뮬아크"));
    assertThat(search.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-search-ark-grid-efficiency.json", search.body());
    // 크라켄 점수(#522): 공개 전이라 늘 DISABLED 모양(값 null, 요소 빈 배열, 이름은 비교 기준 이름 없이).
    var krakenScore = mapper.readTree(search.body()).at("/data/krakenScore");
    assertThat(krakenScore.path("status").asText()).isEqualTo("DISABLED");
    assertThat(krakenScore.path("score").isNull()).isTrue();
    assertThat(krakenScore.path("tier").isNull()).isTrue();
    assertThat(krakenScore.path("factors").size()).isZero();
    assertThat(krakenScore.path("ruleVersion").asText()).isEqualTo("kraken-score-v1");
    SiteNames.assertAbsent(search.body());
    var efficiency = mapper.readTree(search.body()).at("/data/arkGridEfficiency");
    assertThat(efficiency.path("status").asText()).isEqualTo("OK");
    assertThat(efficiency.path("percent").decimalValue()).isEqualByComparingTo("38.64");
    assertThat(efficiency.path("coresPercent").decimalValue()).isEqualByComparingTo("32.96");
    assertThat(efficiency.path("reasonCode").isNull()).isTrue();
    assertThat(efficiency.path("lines").size()).isEqualTo(7);
    assertThat(efficiency.at("/lines/0/slotIndex").asInt()).isZero();
    assertThat(efficiency.at("/lines/0/text").asText()).isEqualTo("질서의 해 코어 : 직업 스킬 고대 17P");
    assertThat(efficiency.at("/lines/0/percent").decimalValue()).isEqualByComparingTo("8.50");
    assertThat(efficiency.at("/lines/6/slotIndex").isNull()).isTrue();
    assertThat(efficiency.at("/lines/6/percent").decimalValue()).isEqualByComparingTo("4.27");
    assertThat(efficiency.path("ruleVersion").asText()).isEqualTo("ark-grid-efficiency-v1");

    // L38·L39 랭킹용 저장: armory 관측 기록(비동기)이 팔찌 효율과 젬 옵션 합계 줄을 규칙 버전과 함께 남긴다.
    java.util.Map<String, Object> row = java.util.Map.of();
    for (int i = 0; i < 100; i++) {
      row =
          jdbc
              .sql(
                  """
              SELECT bracelet_efficiency_pct, bracelet_rule_version, ark_grid_gem_pct,
                ark_grid_rule_version, efficiency_observed_at
              FROM characters WHERE server_name = :s AND character_name = :n
              """)
              .param("s", SERVER)
              .param("n", "시뮬아크")
              .query()
              .listOfRows()
              .stream()
              .findFirst()
              .orElse(java.util.Map.of());
      if (row.get("ark_grid_gem_pct") != null) break;
      Thread.sleep(50);
    }
    assertThat((BigDecimal) row.get("bracelet_efficiency_pct")).isEqualByComparingTo("14.42");
    assertThat(row.get("bracelet_rule_version")).isEqualTo("combat-power-sim-v8");
    assertThat((BigDecimal) row.get("ark_grid_gem_pct")).isEqualByComparingTo("4.27");
    assertThat(row.get("ark_grid_rule_version")).isEqualTo("ark-grid-efficiency-v1");
    assertThat(row.get("efficiency_observed_at")).isNotNull();
  }

  @Test
  void 아크_그리드_코어는_코어_배수_비율로_계산하고_무기_코어는_계산하지_않는다() throws Exception {
    // 계산 검증 11-02(비교 기준 둘 일치): 바꾼 뒤 = 공식 × (1 + C(new)/100) / (1 + C(now)/100), 코어마다 독립 배수.
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var grid =
                  new CharacterLoadout.ArkGrid(
                      List.of(
                          gridSlot(0, "질서의 해 코어 : 직업 스킬", 18, "유물"),
                          gridSlot(2, "질서의 달 코어 : 직업 스킬", 18, "유물"),
                          gridSlot(4, "혼돈의 해 코어 : 현란한 공격", 17, "유물"),
                          gridSlot(6, "혼돈의 별 코어 : 무기", 17, "유물")),
                      List.of());
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      grid,
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬코어")).statusCode())
        .isEqualTo(200);
    var response =
        simulate(
            "시뮬코어",
            """
            [
              {"kind": "ARK_GRID_CORE", "slotIndex": 0, "grade": "고대", "point": 18},
              {"kind": "ARK_GRID_CORE", "slotIndex": 4, "grade": "유물", "point": 16},
              {"kind": "ARK_GRID_CORE", "slotIndex": 2, "grade": "전설", "point": 20},
              {"kind": "ARK_GRID_CORE", "slotIndex": 6, "grade": "고대", "point": 20}
            ]
            """);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("character-combat-power-simulate-ark-grid.json", response.body());
    SiteNames.assertAbsent(response.body());
    var data = mapper.readTree(response.body()).path("data");
    var contributions = data.path("contributions");
    // 질서의 해 유물 18P(7.67%) → 고대 18P(8.67%, 17P 이상 +1.0%p): × 1.0867/1.0767 = 1.009288(비교 기준 둘과 같음).
    // 코어 합에 더하는 식이면 × 1.007521이라 이 값으로 갈린다.
    assertThat(contributions.at("/0/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/0/from").asText()).isEqualTo("유물 18P");
    assertThat(contributions.at("/0/to").asText()).isEqualTo("고대 18P");
    assertThat(contributions.at("/0/delta").decimalValue()).isEqualByComparingTo("31.07");
    // 혼돈 1단 17P(2.5%) → 16P: 14~16은 같은 구간(1%)이라 × 1.01/1.025(보간이 아님).
    assertThat(contributions.at("/1/delta").decimalValue()).isEqualByComparingTo("-49.41");
    // 전설은 14P가 상한이라 20P를 골라도 14P 값(4%): × 1.04/1.0767.
    assertThat(contributions.at("/2/delta").decimalValue()).isEqualByComparingTo("-113.40");
    // 혼돈의 별 : 무기는 무기 공격력 항이라 코어 표에 없다.
    assertThat(contributions.at("/3/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(contributions.at("/3/delta").isNull()).isTrue();
    assertThat(contributions.at("/3/basis").asText()).contains("혼돈의 별 : 무기");
    assertThat(data.path("after").decimalValue()).isEqualByComparingTo("3213.50");
    assertThat(data.path("delta").decimalValue()).isEqualByComparingTo("-131.74");
    assertThat(data.path("deltaPercent").decimalValue()).isEqualByComparingTo("-3.938");
    assertThat(data.path("unsupportedCount").asInt()).isEqualTo(1);
    verify(detailsPort, times(1)).fetch(anyString());
  }

  /**
   * M49 G1(계산 검증 11-22): 아크 그리드 젬 교체. 공식 원본(11-15 표본)과 같은 아크 그리드 상태를 이름 없이 만든다 — 젬 옵션 합 추가 피해 49 ·
   * 공격력 34 · 보스 피해 4 · 낙인력 39 · 아군 피해 강화 24, 혼돈의 해 코어 : 현란한 공격(고대, Slots Index 5, 17P, 공급 의지력 17, 젬
   * 필요 의지력 3+4+3+7 = 17). 비교 기준 식 기대값: 젬 1(아군 피해 강화 2 · 낙인력 5, 4P)을 침식 5P 추가 피해 3 · 공격력 2로 바꾸면 옵션
   * +0.2443% × 코어 17 → 18P +0.1643% = +0.4091%.
   */
  @Test
  void 아크_그리드_젬_교체는_옵션_합과_코어_포인트를_다시_더하고_의지력이_모자라면_계산하지_않는다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      gemSwapGrid(),
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬젬")).statusCode())
        .isEqualTo(200);
    var swap =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 1, "gemType": "침식", "willEfficiency": %d,
         "point": %d, "effects": [{"name": "추가 피해", "level": 3}, {"name": "공격력", "level": 2}]}
        """;
    // (A) 효율 4(필요 4, 합 17 = 공급 17): 옵션 + 포인트.
    var response = simulate("시뮬젬", "[" + swap.formatted(4, 5) + "]");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("character-combat-power-simulate-ark-grid-gem.json", response.body());
    SiteNames.assertAbsent(response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/contributions/0/status").asText()).isEqualTo("OK");
    // 표시는 1부터: 공식 Gems Index 1 = 두 번째 젬 = "젬 2"(프론트2 11-22).
    assertThat(data.at("/contributions/0/target").asText()).isEqualTo("혼돈의 해 코어 : 현란한 공격 젬 2");
    assertThat(data.at("/contributions/0/from").asText()).isEqualTo("침식 4P 아군 피해 강화 2 · 낙인력 5");
    assertThat(data.at("/contributions/0/to").asText()).isEqualTo("침식 5P 추가 피해 3 · 공격력 2");
    assertThat(data.path("deltaPercent").decimalValue())
        .isBetween(new BigDecimal("0.408"), new BigDecimal("0.410"));
    // 옵션만(포인트 4 그대로, 코어 17P): +0.2443%.
    var options = mapper.readTree(simulate("시뮬젬", "[" + swap.formatted(4, 4) + "]").body());
    assertThat(options.at("/data/deltaPercent").decimalValue())
        .isBetween(new BigDecimal("0.243"), new BigDecimal("0.245"));
    // (B) 효율 3(필요 5, 합 18 > 공급 17): 어느 젬이 꺼질지 몰라 계산하지 않는다.
    var shortWill = mapper.readTree(simulate("시뮬젬", "[" + swap.formatted(3, 5) + "]").body());
    assertThat(shortWill.at("/data/contributions/0/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(shortWill.at("/data/contributions/0/reasonCode").asText())
        .isEqualTo("ARK_GRID_WILL_SHORT");
    assertThat(shortWill.at("/data/contributions/0/delta").isNull()).isTrue();
    // 잘못된 젬: 혼돈 코어에 질서 젬, 그 종류에 없는 옵션, 같은 옵션 두 줄 → 400. 없는 젬 칸 → 409.
    assertThat(
            simulate("시뮬젬", "[" + swap.formatted(4, 5).replace("\"침식\"", "\"견고\"") + "]")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            simulate("시뮬젬", "[" + swap.formatted(4, 5).replace("\"공격력\"", "\"보스 피해\"") + "]")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            simulate("시뮬젬", "[" + swap.formatted(4, 5).replace("\"공격력\"", "\"추가 피해\"") + "]")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            simulate(
                    "시뮬젬", "[" + swap.formatted(4, 5).replace("\"index\": 1", "\"index\": 9") + "]")
                .statusCode())
        .isEqualTo(409);
    // 칸 번호가 빠지면 요청 오류(400, #1079 코드 리뷰 P3).
    assertThat(
            simulate("시뮬젬", "[" + swap.formatted(4, 5).replace("\"slotIndex\": 5, ", "") + "]")
                .statusCode())
        .isEqualTo(400);
    // 같은 코어의 교체는 모두 반영한 의지력으로 본다(#1079 코드 리뷰). 젬 3(왜곡 필요 7) → 효율 5(필요 4)로 3을 비우고 젬 0(필요 3) → 효율
    // 2(필요 6).
    // 젬 0 교체만이면 17 − 3 + 6 = 20 > 17이라 계산하지 않지만, 둘 함께면 17로 맞는다.
    var gem0 =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 0, "gemType": "침식", "willEfficiency": 2,
         "point": 4, "effects": [{"name": "추가 피해", "level": 3}, {"name": "낙인력", "level": 1}]}
        """;
    var gem3 =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 3, "gemType": "왜곡", "willEfficiency": %d,
         "point": 5, "effects": [{"name": "보스 피해", "level": 4}, {"name": "공격력", "level": 3}]}
        """;
    var alone = mapper.readTree(simulate("시뮬젬", "[" + gem0 + "]").body());
    assertThat(alone.at("/data/contributions/0/reasonCode").asText())
        .isEqualTo("ARK_GRID_WILL_SHORT");
    var rebalanced =
        mapper.readTree(simulate("시뮬젬", "[" + gem0 + "," + gem3.formatted(5) + "]").body());
    assertThat(rebalanced.at("/data/contributions/0/status").asText()).isEqualTo("OK");
    assertThat(rebalanced.at("/data/contributions/1/status").asText()).isEqualTo("OK");
    // 합이 넘으면 그 코어의 교체는 모두 계산하지 않는다(젬 3 효율 2 = 필요 7 그대로 + 젬 0 필요 6 → 20). 젬 3 교체 줄만 보면 맞지만 같은 코어라 함께
    // 막는다.
    var together =
        mapper.readTree(simulate("시뮬젬", "[" + gem0 + "," + gem3.formatted(2) + "]").body());
    assertThat(together.at("/data/contributions/0/reasonCode").asText())
        .isEqualTo("ARK_GRID_WILL_SHORT");
    assertThat(together.at("/data/contributions/1/reasonCode").asText())
        .isEqualTo("ARK_GRID_WILL_SHORT");
    // 같은 코어의 등급·포인트 변경과 젬 교체는 함께 계산하지 않는다(그 이유 문장).
    var mixed =
        mapper.readTree(
            simulate(
                    "시뮬젬",
                    "[{\"kind\": \"ARK_GRID_CORE\", \"slotIndex\": 5, \"grade\": \"고대\", \"point\": 20},"
                        + swap.formatted(4, 5)
                        + "]")
                .body());
    assertThat(mixed.at("/data/contributions/1/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(mixed.at("/data/contributions/1/basis").asText()).contains("젬 교체를 함께 계산하지");
  }

  /**
   * #1079 대체 리뷰 P3: 같은 코어의 교체 하나를 계산하지 못하면 그 코어의 교체는 모두 계산하지 않는다(의지력 판정은 그 코어의 교체 전부를 전제로 했다). 공식
   * 옵션 합(추가 피해 1)이 젬 0의 추가 피해 3보다 작은 어긋난 상태: 젬 0을 빼면 레벨이 음수라 다시 더할 수 없다. 젬 1 교체만이면 계산된다.
   */
  @Test
  void 같은_코어의_교체_하나를_계산하지_못하면_그_코어의_교체는_모두_계산하지_않는다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var grid = gemSwapGrid();
              var effects =
                  grid.effects().stream()
                      .map(
                          effect ->
                              "추가 피해".equals(effect.name())
                                  ? new CharacterLoadout.GridEffect("추가 피해", 1, null, null)
                                  : effect)
                      .toList();
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      new CharacterLoadout.ArkGrid(grid.slots(), effects),
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬젬형제")).statusCode())
        .isEqualTo(200);
    var gem1 =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 1, "gemType": "침식", "willEfficiency": 4,
         "point": 4, "effects": [{"name": "아군 피해 강화", "level": 2}, {"name": "낙인력", "level": 5}]}
        """;
    var gem0 =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 0, "gemType": "침식", "willEfficiency": 5,
         "point": 4, "effects": [{"name": "낙인력", "level": 1}, {"name": "공격력", "level": 2}]}
        """;
    var alone = mapper.readTree(simulate("시뮬젬형제", "[" + gem1 + "]").body());
    assertThat(alone.at("/data/contributions/0/status").asText()).isEqualTo("OK");
    var both = mapper.readTree(simulate("시뮬젬형제", "[" + gem1 + "," + gem0 + "]").body());
    assertThat(both.at("/data/contributions/0/status").asText())
        .as(both.toString())
        .isEqualTo("UNSUPPORTED");
    assertThat(both.at("/data/contributions/1/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(both.at("/data/contributions/0/basis").asText())
        .isEqualTo(both.at("/data/contributions/1/basis").asText())
        .contains("함께 계산하지 못해");
    assertThat(both.at("/data/delta").decimalValue()).isEqualByComparingTo("0");
  }

  /**
   * #1083 대체 리뷰 P3: 코어마다 미리 계산해 보는 것으로는 부족하다 — 옵션 합은 그리드 전체에서 더한다. 공식 추가 피해 합 4인 어긋난 상태에서 두
   * 코어(5·6)가 추가 피해 3인 젬을 하나씩 바꾸면 하나씩은 계산되지만(4 − 3 = 1) 함께면 −2라 다시 더할 수 없다. 그러면 젬 교체를 모두 같은 이유로 계산하지
   * 않는다(앞 코어만 계산되고 뒤 코어만 빠지지 않게).
   */
  @Test
  void 여러_코어의_젬_교체를_함께_계산하지_못하면_젬_교체는_모두_계산하지_않는다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var grid = gemSwapGrid();
              var second =
                  new CharacterLoadout.GridSlot(
                      6,
                      null,
                      "혼돈의 해 코어 : 현란한 공격",
                      4,
                      "고대",
                      List.of(gridGem(0, 4, 3, 8, 5, "추가 피해", 3, "공격력", 1)),
                      new CharacterLoadout.CoreDetail("혼돈", 17, List.of(), List.of()),
                      null);
              var slots = new java.util.ArrayList<>(grid.slots());
              slots.add(second);
              var effects =
                  grid.effects().stream()
                      .map(
                          effect ->
                              "추가 피해".equals(effect.name())
                                  ? new CharacterLoadout.GridEffect("추가 피해", 4, null, null)
                                  : effect)
                      .toList();
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      new CharacterLoadout.ArkGrid(List.copyOf(slots), effects),
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬젬두코어")).statusCode())
        .isEqualTo(200);
    var core5 =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 0, "gemType": "침식", "willEfficiency": 5,
         "point": 4, "effects": [{"name": "낙인력", "level": 1}, {"name": "공격력", "level": 2}]}
        """;
    var core6 =
        """
        {"kind": "ARK_GRID_GEM", "slotIndex": 6, "index": 0, "gemType": "침식", "willEfficiency": 5,
         "point": 4, "effects": [{"name": "낙인력", "level": 1}, {"name": "공격력", "level": 2}]}
        """;
    // 하나씩은 계산된다.
    for (var alone : List.of(core5, core6)) {
      var body = mapper.readTree(simulate("시뮬젬두코어", "[" + alone + "]").body());
      assertThat(body.at("/data/contributions/0/status").asText())
          .as(body.toString())
          .isEqualTo("OK");
    }
    var both = mapper.readTree(simulate("시뮬젬두코어", "[" + core5 + "," + core6 + "]").body());
    assertThat(both.at("/data/contributions/0/status").asText())
        .as(both.toString())
        .isEqualTo("UNSUPPORTED");
    assertThat(both.at("/data/contributions/1/status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(both.at("/data/contributions/0/basis").asText())
        .isEqualTo(both.at("/data/contributions/1/basis").asText())
        .contains("여러 코어의 젬 교체를 함께 계산하지 못해");
    assertThat(both.at("/data/delta").decimalValue()).isEqualByComparingTo("0");
  }

  /**
   * #1079 대체 리뷰 P3-2: 교체로 의지력이 남으면 뒤 슬롯의 꺼진 젬이 켜진다(공식 가이드 '아크 그리드' 최근수정일 2026-06-24 — 코어에서 가까운 슬롯부터
   * 차례로 공급, 모자란 슬롯부터 그 뒤는 꺼짐). 공급 17, 젬 0·1·2가 3·4·3을 쓰고 젬 3(필요 8)은 7이 남아 꺼져 있다. 젬 1을 효율 5(필요 3)로
   * 바꾸면 8이 남아 젬 3이 켜지고(보스 피해 4·공격력 3·코어 포인트 5가 더해짐), 효율 4(필요 4)면 7이라 그대로 꺼져 있다.
   */
  @Test
  void 교체로_의지력이_남으면_뒤_슬롯의_꺼진_젬이_켜진다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      sleepingGemGrid(),
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬젬깨움")).statusCode())
        .isEqualTo(200);
    var freesWill =
        """
        [{"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 1, "gemType": "침식", "willEfficiency": 5,
          "point": 5, "effects": [{"name": "추가 피해", "level": 3}, {"name": "공격력", "level": 2}]}]
        """;
    var keepsWill =
        """
        [{"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 1, "gemType": "침식", "willEfficiency": 4,
          "point": 5, "effects": [{"name": "추가 피해", "level": 3}, {"name": "공격력", "level": 2}]}]
        """;
    var woke = mapper.readTree(simulate("시뮬젬깨움", freesWill).body());
    var slept = mapper.readTree(simulate("시뮬젬깨움", keepsWill).body());
    assertThat(woke.at("/data/contributions/0/status").asText())
        .as(woke.toString())
        .isEqualTo("OK");
    assertThat(slept.at("/data/contributions/0/status").asText())
        .as(slept.toString())
        .isEqualTo("OK");
    // 효율만 다르고 옵션·포인트는 같다: 젬 3이 켜지는 쪽이 더 오른다.
    assertThat(woke.at("/data/delta").decimalValue())
        .as(woke.toString() + " vs " + slept)
        .isGreaterThan(slept.at("/data/delta").decimalValue());
  }

  /**
   * 규칙이 지금 IsActive를 재현하지 못하면(젬 3이 필요 7로 맞는데도 공식은 꺼짐 — 슬롯 순서가 다를 수 있다) 젬을 새로 켜지 않는다: 효율 5·4가 같은
   * 값이다(전과 같음).
   */
  @Test
  void 활성화_규칙이_지금_상태를_재현하지_못하면_젬을_새로_켜지_않는다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(
            invocation -> {
              var base = fetchedBracelet(invocation.getArgument(0));
              var details = base.details();
              var old = details.loadout();
              var grid = sleepingGemGrid(7);
              var loadout =
                  new CharacterLoadout(
                      old.equipment(),
                      old.engravings(),
                      old.gems(),
                      old.cards(),
                      old.arkPassive(),
                      grid,
                      old.skills());
              return new FetchedCharacter(
                  CharacterTestFixture.details(details.profile(), loadout, List.of()), "{}", "[]");
            });
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬젬어긋남")).statusCode())
        .isEqualTo(200);
    var five =
        """
        [{"kind": "ARK_GRID_GEM", "slotIndex": 5, "index": 1, "gemType": "침식", "willEfficiency": 5,
          "point": 5, "effects": [{"name": "추가 피해", "level": 3}, {"name": "공격력", "level": 2}]}]
        """;
    var four = five.replace("\"willEfficiency\": 5", "\"willEfficiency\": 4");
    var a = mapper.readTree(simulate("시뮬젬어긋남", five).body());
    var b = mapper.readTree(simulate("시뮬젬어긋남", four).body());
    assertThat(a.at("/data/contributions/0/status").asText()).as(a.toString()).isEqualTo("OK");
    assertThat(a.at("/data/delta").decimalValue())
        .isEqualByComparingTo(b.at("/data/delta").decimalValue());
  }

  /** 젬 3이 꺼진 아크 그리드: 공급 17, 젬 0·1·2가 3·4·3, 젬 3은 필요 8이라 꺼짐 — 공식 옵션 합·코어 포인트는 켜진 젬만. */
  private static CharacterLoadout.ArkGrid sleepingGemGrid() {
    return sleepingGemGrid(8);
  }

  /** gem3Need: 젬 3의 필요 의지력(8이면 규칙대로 꺼짐, 7이면 규칙으로는 켜져야 하는데 공식은 꺼짐 — 어긋난 상태). */
  private static CharacterLoadout.ArkGrid sleepingGemGrid(int gem3Need) {
    var gems =
        List.of(
            gridGem(0, 4, 3, 8, 5, "추가 피해", 3, "낙인력", 1),
            gridGem(1, 4, 4, 8, 4, "아군 피해 강화", 2, "낙인력", 5),
            gridGem(2, 4, 3, 8, 5, "아군 피해 강화", 3, "낙인력", 2),
            new CharacterLoadout.GridGem(
                3,
                null,
                false,
                "전설",
                new CharacterLoadout.GemDetail(
                    "혼돈의 젬 : 왜곡",
                    "혼돈",
                    16,
                    gem3Need,
                    9,
                    9 - gem3Need,
                    5,
                    List.of(
                        new CharacterLoadout.GemOption("보스 피해", 4, null, null),
                        new CharacterLoadout.GemOption("공격력", 3, null, null)))));
    var core =
        new CharacterLoadout.GridSlot(
            5,
            null,
            "혼돈의 해 코어 : 현란한 공격",
            12,
            "고대",
            gems,
            new CharacterLoadout.CoreDetail("혼돈", 17, List.of(), List.of()),
            null);
    return new CharacterLoadout.ArkGrid(
        List.of(core),
        List.of(
            new CharacterLoadout.GridEffect("추가 피해", 49, null, null),
            new CharacterLoadout.GridEffect("낙인력", 39, null, null),
            new CharacterLoadout.GridEffect("아군 피해 강화", 24, null, null),
            new CharacterLoadout.GridEffect("공격력", 31, null, null)));
  }

  /** 11-15 표본과 같은 아크 그리드(이름 없음): 혼돈의 해 코어 하나와 공식 옵션 합. */
  private static CharacterLoadout.ArkGrid gemSwapGrid() {
    var gems =
        List.of(
            gridGem(0, 4, 3, 8, 5, "추가 피해", 3, "낙인력", 1),
            gridGem(1, 4, 4, 8, 4, "아군 피해 강화", 2, "낙인력", 5),
            gridGem(2, 4, 3, 8, 5, "아군 피해 강화", 3, "낙인력", 2),
            new CharacterLoadout.GridGem(
                3,
                null,
                true,
                "전설",
                new CharacterLoadout.GemDetail(
                    "혼돈의 젬 : 왜곡",
                    "혼돈",
                    16,
                    7,
                    9,
                    2,
                    5,
                    List.of(
                        new CharacterLoadout.GemOption("보스 피해", 4, null, null),
                        new CharacterLoadout.GemOption("공격력", 3, null, null)))));
    var core =
        new CharacterLoadout.GridSlot(
            5,
            null,
            "혼돈의 해 코어 : 현란한 공격",
            17,
            "고대",
            gems,
            new CharacterLoadout.CoreDetail("혼돈", 17, List.of(), List.of()),
            null);
    return new CharacterLoadout.ArkGrid(
        List.of(core),
        List.of(
            new CharacterLoadout.GridEffect("추가 피해", 49, null, null),
            new CharacterLoadout.GridEffect("낙인력", 39, null, null),
            new CharacterLoadout.GridEffect("아군 피해 강화", 24, null, null),
            new CharacterLoadout.GridEffect("공격력", 34, null, null),
            new CharacterLoadout.GridEffect("보스 피해", 4, null, null)));
  }

  private static CharacterLoadout.GridGem gridGem(
      int index,
      int point,
      int will,
      int baseWill,
      int efficiency,
      String first,
      int firstLevel,
      String second,
      int secondLevel) {
    return new CharacterLoadout.GridGem(
        index,
        null,
        true,
        "전설",
        new CharacterLoadout.GemDetail(
            "혼돈의 젬 : 침식",
            "혼돈",
            // 젬 포인트(총합)는 코어 포인트(혼돈 포인트)와 다르다(공식 원본: 젬 포인트 15 · 혼돈 포인트 4, #1079 P0).
            point + 11,
            will,
            baseWill,
            efficiency,
            point,
            List.of(
                new CharacterLoadout.GemOption(first, firstLevel, null, null),
                new CharacterLoadout.GemOption(second, secondLevel, null, null))));
  }

  private static CharacterLoadout.GridSlot gridSlot(
      int index, String name, int point, String grade) {
    return new CharacterLoadout.GridSlot(index, null, name, point, grade, List.of());
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
