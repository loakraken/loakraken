package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.DealerCombatPower;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 방어구 재련 전투력 문(PM 총괄 11-21 (b) 3단계, spec-up.armor-cp-gate.enabled 켬): 공식 전투력이 장비로 지은 딜러 전투력과 0.10%
 * 안이면 시뮬레이터가 방어구 재련을 계산하고, 벗어나면 ARMOR_CP_RESIDUAL_HIGH로 닫는다. 기준 딜러 원문(l01, 전투 레벨만 넣음), 공식 전투력은 장비로
 * 지은 값을 기준으로 정한다. 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "combat-power-sim.enabled=true",
      "kraken-score.enabled=true",
      "spec-up.enabled=true",
      "spec-up.support-order.enabled=true",
      "spec-up.armor-cp-gate.enabled=true"
    })
class ArmorCpGateIntegrationTest {
  private static final String SERVER = "방어구문서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired KrakenScoreEngine.Tables engine;
  @Autowired DealerCombatPower.Tables tables;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("*방어구문*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 차이가_작으면_방어구_재련을_계산하고_크면_그_이유로_닫는다() throws Exception {
    var raw = raw();
    var built = DealerCombatPower.of(KrakenScoreInputMapper.from(raw), engine, tables);
    assertThat(built).as("장비로 지은 딜러 전투력").isNotNull();
    int helmet = index(raw, "투구");
    int target = refine(raw, helmet) + 1;
    var change =
        "[{\"kind\": \"EQUIPMENT_REFINE\", \"index\": " + helmet + ", \"refine\": " + target + "}]";

    // 공식 전투력 = 장비로 지은 값(r = 0): 문이 열려 방어구 재련을 계산한다.
    search("방어구문열림", raw, built);
    var open = simulate("방어구문열림", change);
    assertThat(open.statusCode()).as(open.body()).isEqualTo(200);
    var openRow = mapper.readTree(open.body()).at("/data/contributions/0");
    assertThat(openRow.path("status").asText()).as(open.body()).isEqualTo("OK");
    assertThat(openRow.path("delta").decimalValue()).isPositive();
    // 웹 표본(프론트2 11-21): 스위치 켬, 문 안 딜러의 시뮬레이터·스펙업 목록·추천 순서.
    ApiContractFixture.write("armor-cp-gate-open-simulate.json", open.body());
    for (var sample :
        java.util.Map.of(
                "armor-cp-gate-open-spec-up.json",
                "/api/v1/characters/spec-up?characterName=",
                "armor-cp-gate-open-path-efficiency.json",
                "/api/v1/characters/spec-up/path?sort=EFFICIENCY&limit=30&characterName=",
                "armor-cp-gate-open-path-kraken.json",
                "/api/v1/characters/spec-up/path?sort=KRAKEN&limit=30&characterName=")
            .entrySet()) {
      var response = get(sample.getValue() + URLEncoder.encode("방어구문열림", StandardCharsets.UTF_8));
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      ApiContractFixture.write(sample.getKey(), response.body());
    }

    // 공식 전투력이 1% 높으면(r = 1%) 닫고 새 이유 코드·문장.
    search("방어구문닫힘", raw, built.multiply(new BigDecimal("1.01")));
    var closed = simulate("방어구문닫힘", change);
    assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
    var closedRow = mapper.readTree(closed.body()).at("/data/contributions/0");
    assertThat(closedRow.path("status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(closedRow.path("reasonCode").asText()).isEqualTo("ARMOR_CP_RESIDUAL_HIGH");
    assertThat(closedRow.path("basis").asText())
        .isEqualTo("장비로 계산한 전투력이 공식 전투력과 맞지 않아 방어구 재련 · 상급 재련 · 완갑 전투력은 계산하지 않았어요.")
        .doesNotContain("%");
    ApiContractFixture.write("armor-cp-gate-closed-simulate.json", closed.body());
    var closedList =
        get(
            "/api/v1/characters/spec-up?characterName="
                + URLEncoder.encode("방어구문닫힘", StandardCharsets.UTF_8));
    assertThat(closedList.statusCode()).as(closedList.body()).isEqualTo(200);
    ApiContractFixture.write("armor-cp-gate-closed-spec-up.json", closedList.body());
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private ObjectNode raw() throws Exception {
    var raw =
        (ObjectNode)
            mapper
                .readTree(
                    new ClassPathResource("kraken-score/l01.json")
                        .getContentAsString(StandardCharsets.UTF_8))
                .path("raw");
    // 이 표본 원문에는 전투 레벨이 없다(장비로 지은 전투력에 필요).
    ((ObjectNode) raw.path("ArmoryProfile")).put("CharacterLevel", 70);
    return raw;
  }

  private static int index(JsonNode raw, String type) {
    var equipment = raw.path("ArmoryEquipment");
    for (int i = 0; i < equipment.size(); i++)
      if (type.equals(equipment.get(i).path("Type").asText())) return i;
    throw new IllegalStateException(type + " 없음");
  }

  private static int refine(JsonNode raw, int index) {
    var name = raw.path("ArmoryEquipment").get(index).path("Name").asText();
    return Integer.parseInt(name.substring(1, name.indexOf(' ')));
  }

  private void search(String name, ObjectNode raw, BigDecimal combatPower) throws Exception {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1770.00"),
            combatPower,
            null,
            null,
            null,
            null,
            // 재련 모델은 공식 기본 공격력 줄이 있는지 본다(값은 비에 쓰지 않음). 이 표본 원문의 공격력 툴팁은 비어 있어 실제 프로필처럼 넣는다.
            List.of(
                new CharacterProfile.Stat(
                    "공격력", "200266", List.of("공격력은 200,266입니다.", "기본 공격력은 140,000입니다."))));
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/api/v1/characters/search?characterName="
                                + URLEncoder.encode(name, StandardCharsets.UTF_8)))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
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
}
