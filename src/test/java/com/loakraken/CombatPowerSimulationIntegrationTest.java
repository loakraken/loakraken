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
    assertThat(response.body())
        .contains(
            "\"before\":3000.00",
            "\"after\":3139.85",
            "\"delta\":139.85",
            "\"deltaPercent\":4.662");
    assertThat(data.path("modelBefore").isNull()).isTrue();
    assertThat(data.path("partial").asBoolean()).isTrue();
    // 같은 보석 slot은 뒤의 변경(10)이 이기고 자리는 처음 나온 곳을 지킨다.
    var contributions = data.path("contributions");
    assertThat(contributions.size()).isEqualTo(9);
    assertThat(data.path("unsupportedCount").asInt()).isEqualTo(7);
    assertThat(contributions.at("/0/kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(contributions.at("/0/from").asText()).isEqualTo("8");
    assertThat(contributions.at("/0/to").asText()).isEqualTo("10");
    assertThat(contributions.at("/0/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/0/delta").decimalValue()).isEqualByComparingTo("48.07");
    assertThat(contributions.at("/1/from").asText()).isEqualTo("20");
    assertThat(contributions.at("/2/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/2/delta").decimalValue()).isEqualByComparingTo("91.78");
    for (int i = 1; i < contributions.size(); i++) {
      if (i == 2) continue;
      assertThat(contributions.get(i).path("status").asText()).isEqualTo("UNSUPPORTED");
      assertThat(contributions.get(i).path("delta").isNull()).isTrue();
    }
    for (var item : contributions)
      assertThat(item.path("basis").asText()).doesNotContain("로아", "lo4", "아이스펭");
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

    var missing = simulate("시뮬검증", "[{\"kind\": \"GEM_LEVEL\", \"slot\": 7, \"level\": 9}]");
    assertThat(missing.statusCode()).isEqualTo(409);
    assertThat(missing.body()).contains("CHANGE_TARGET_NOT_FOUND", "보석 slot 7");

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
    var arkPassive =
        new CharacterLoadout.ArkPassive(
            "진화", true, List.of(new CharacterLoadout.PassivePoint("도약", 70, "")), List.of());
    var loadout =
        new CharacterLoadout(List.of(bracelet), null, null, null, arkPassive, null, List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
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
