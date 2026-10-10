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
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("combat-power-sim-v1");
    // 시뮬레이션이 읽은 캐시가 검색 응답과 같은지 웹이 비교한다(PM-프론트 18:31).
    assertThat(data.path("fetchedAt").asText())
        .isEqualTo(mapper.readTree(search.body()).at("/data/fetchedAt").asText());
    // 보석 8 → 10(뒤의 변경이 이김): 3000 × (1.0704/1.0576) × (1 + 3.70%)/(1 + 3.30%) = 3048.07.
    // 기본 공격력 % = 보석 0.80 → 1.20 + 스톤 레벨 보너스 1.50 + 완갑 추가 효과 1.00.
    assertThat(response.body())
        .contains(
            "\"before\":3000.00", "\"after\":3048.07", "\"delta\":48.07", "\"deltaPercent\":1.602");
    assertThat(data.path("modelBefore").isNull()).isTrue();
    assertThat(data.path("partial").asBoolean()).isTrue();
    // 같은 보석 slot은 뒤의 변경(10)이 이기고 자리는 처음 나온 곳을 지킨다.
    var contributions = data.path("contributions");
    assertThat(contributions.size()).isEqualTo(9);
    assertThat(data.path("unsupportedCount").asInt()).isEqualTo(8);
    assertThat(contributions.at("/0/kind").asText()).isEqualTo("GEM_LEVEL");
    assertThat(contributions.at("/0/from").asText()).isEqualTo("8");
    assertThat(contributions.at("/0/to").asText()).isEqualTo("10");
    assertThat(contributions.at("/0/status").asText()).isEqualTo("OK");
    assertThat(contributions.at("/0/delta").decimalValue()).isEqualByComparingTo("48.07");
    assertThat(contributions.at("/1/from").asText()).isEqualTo("20");
    for (int i = 1; i < contributions.size(); i++) {
      assertThat(contributions.get(i).path("status").asText()).isEqualTo("UNSUPPORTED");
      assertThat(contributions.get(i).path("delta").isNull()).isTrue();
    }
    for (var item : contributions)
      assertThat(item.path("basis").asText()).doesNotContain("로아", "lo4", "아이스펭");
    verify(detailsPort, times(1)).fetch(anyString());
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
  void 공개_제외_이름은_캐시가_있어도_404다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode("시뮬제외")).statusCode())
        .isEqualTo(200);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('시뮬제외', NULL, 'USER_REQUEST')")
        .update();
    var response = simulate("시뮬제외", "[]");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED").doesNotContain("시뮬서버");
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
