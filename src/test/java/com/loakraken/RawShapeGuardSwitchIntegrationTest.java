package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.MarketAuctionService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 2부 대비 막기(계약 2026-11-28): 조회 때 남긴 원본 형태 판정은 캐시에 있고, 스위치는 읽을 때 적용된다. 판정이 어긋난(H6) 캐릭터를 막기 꺼짐으로 한 번
 * 조회해 캐시(목표·추천 순서·젬 순위 결과 캐시 포함)를 채운 뒤 막기를 켜면, 다시 조회하지 않고 다음 요청부터 모든 전투력·스펙업 화면이
 * GAME_UPDATE_UNSUPPORTED이고 숫자가 없다. 끄면 돌아온다. 화면 끄기 스위치는 그 화면만 막는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "combat-power-sim.enabled=true",
      "combat-power-sim.gem-ranking.enabled=true",
      "crit-speed.enabled=true"
    })
@DirtiesContext
class RawShapeGuardSwitchIntegrationTest {
  private static final String SERVER = "형태가드서버";
  private static final String NAME = "형태가드캐";
  private static final String CODE = "GAME_UPDATE_UNSUPPORTED";
  private static final String SENTENCE = "게임 업데이트로 바뀐 장비 정보라 아직 계산하지 않아요";
  private static final String SWITCHES = "raw-shape-guard-test";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ConfigurableEnvironment environment;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    environment.getPropertySources().remove(SWITCHES);
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("*형태가드*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  private void switches(Map<String, Object> values) {
    environment.getPropertySources().remove(SWITCHES);
    environment.getPropertySources().addFirst(new MapPropertySource(SWITCHES, values));
  }

  @Test
  void 막기를_켜면_다시_조회하지_않고_모든_전투력_화면이_숫자_없이_같은_이유를_낸다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    var card = search();
    // 막기 꺼짐: 판정이 어긋나도 세기만 한다(지금과 같은 결과, 이유 코드에 새 값 없음).
    assertThat(card.toString()).doesNotContain(CODE);
    assertThat(card.at("/krakenScore/status").asText()).isEqualTo("OK");
    var target = card.at("/combatPower").decimalValue().add(BigDecimal.ONE).toBigInteger();
    var before = surfaces(target);
    assertThat(before.toString()).doesNotContain(CODE);
    assertThat(critSpeed().statusCode()).isEqualTo(200);

    switches(Map.of("combat-power.raw-shape-guard.enforce", "true"));

    var blockedCard = search();
    for (var path : List.of("/braceletEfficiency", "/arkGridEfficiency", "/krakenScore")) {
      var section = blockedCard.at(path);
      if (section.isMissingNode() || section.isNull()) continue;
      assertThat(section.path("status").asText()).as(path).isEqualTo("UNSUPPORTED");
      assertThat(section.path("reasonCode").asText()).as(path).isEqualTo(CODE);
    }
    assertThat(blockedCard.at("/krakenScore/score").isNull()).isTrue();
    assertThat(blockedCard.at("/krakenScore/factors").size()).isZero();
    for (var path : List.of("/braceletEfficiency/real", "/arkGridEfficiency/real")) {
      var real = blockedCard.at(path);
      if (real.isMissingNode() || real.isNull()) continue;
      assertThat(real.path("reasonCode").asText()).as(path).isEqualTo(CODE);
      assertThat(real.path("percent").isNull()).as(path).isTrue();
    }
    // 프로필·장비 목록은 그대로다.
    assertThat(blockedCard.at("/combatPower")).isEqualTo(card.at("/combatPower"));
    assertThat(blockedCard.at("/loadout/equipment")).isEqualTo(card.at("/loadout/equipment"));

    var blocked = surfaces(target);
    var simulate = blocked.get("simulate");
    assertThat(simulate.path("partial").asBoolean()).isTrue();
    assertThat(simulate.path("delta").decimalValue()).isZero();
    assertThat(simulate.path("contributions"))
        .allMatch(row -> CODE.equals(row.path("reasonCode").asText()))
        .allMatch(row -> SENTENCE.equals(row.path("basis").asText()));
    assertThat(blocked.get("contributions").path("items"))
        .allMatch(row -> CODE.equals(row.path("reasonCode").asText()));
    var guide = blocked.get("guide");
    assertThat(guide.path("candidates")).isNotEmpty();
    assertThat(guide.path("candidates"))
        .allMatch(row -> CODE.equals(row.path("reasonCode").asText()))
        .allMatch(row -> row.path("deltaCombatPower").isNull())
        .allMatch(row -> row.path("deltaKrakenScore").isNull());
    assertThat(guide.path("krakenSortUnavailableReason").asText()).isEqualTo(CODE);
    var path = blocked.get("path");
    assertThat(path.path("stopReasonCode").asText()).isEqualTo(CODE);
    assertThat(path.path("stopReason").asText()).isEqualTo(SENTENCE);
    assertThat(path.path("steps").size()).isZero();
    assertThat(path.path("unpriced").size()).isZero();
    assertThat(path.path("finalCombatPower").isNull()).isTrue();
    var goal = blocked.get("goal");
    assertThat(goal.path("stopReasonCode").asText()).isEqualTo(CODE);
    assertThat(goal.path("steps").size()).isZero();
    assertThat(goal.path("finalCombatPower").isNull()).isTrue();
    assertThat(goal.path("totalCost").isNull()).isTrue();
    var ranking = blocked.get("gemRanking");
    assertThat(ranking.path("status").asText()).isEqualTo("UNSUPPORTED");
    assertThat(ranking.path("reasonCode").asText()).isEqualTo(CODE);
    assertThat(ranking.path("rows").size()).isZero();
    assertThat(ranking.path("totalRows").asInt()).isZero();
    var crit = critSpeed();
    assertThat(crit.statusCode()).as(crit.body()).isEqualTo(422);
    assertThat(mapper.readTree(crit.body()).path("code").asText()).isEqualTo(CODE);
    // 공식 조회는 처음 한 번뿐(판정은 캐시에 있고 스위치는 읽을 때 적용).
    verify(detailsPort, times(1)).fetch(anyString());

    // 끄면 돌아온다(같은 캐시).
    environment.getPropertySources().remove(SWITCHES);
    assertThat(search().toString()).doesNotContain(CODE);
    assertThat(surfaces(target).toString()).doesNotContain(CODE);
  }

  @Test
  void 화면_끄기_스위치는_원문과_상관없이_그_화면만_막는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    var card = search();
    var target = card.at("/combatPower").decimalValue().add(BigDecimal.ONE).toBigInteger();

    switches(Map.of("combat-power.kill.gem-ranking", "true"));
    var killed = surfaces(target);
    assertThat(killed.get("gemRanking").path("reasonCode").asText()).isEqualTo(CODE);
    for (var other : List.of("simulate", "contributions", "guide", "path", "goal"))
      assertThat(killed.get(other).toString()).as(other).doesNotContain(CODE);
    assertThat(search().toString()).doesNotContain(CODE);

    switches(Map.of("combat-power.kill.card", "true"));
    assertThat(search().at("/krakenScore/reasonCode").asText()).isEqualTo(CODE);
    assertThat(surfaces(target).toString()).doesNotContain(CODE);

    switches(Map.of("combat-power.kill.crit-speed", "true"));
    assertThat(critSpeed().statusCode()).isEqualTo(422);
    assertThat(surfaces(target).toString()).doesNotContain(CODE);
  }

  /** 전투력·스펙업 화면 응답(data) 모음. */
  private Map<String, JsonNode> surfaces(java.math.BigInteger target) throws Exception {
    var result = new java.util.LinkedHashMap<String, JsonNode>();
    result.put(
        "simulate",
        ok(
            post(
                "/api/v1/characters/combat-power/simulate",
                "{\"characterName\":\"" + NAME + "\",\"changes\":[" + gemChange() + "]}")));
    result.put(
        "contributions",
        ok(get("/api/v1/characters/combat-power/contributions?characterName=" + encode(NAME))));
    result.put(
        "guide", ok(get("/api/v1/characters/spec-up?characterName=" + encode(NAME) + "&limit=5")));
    result.put(
        "path", ok(get("/api/v1/characters/spec-up/path?limit=1&characterName=" + encode(NAME))));
    result.put(
        "goal",
        ok(
            get(
                "/api/v1/characters/spec-up/goal?characterName="
                    + encode(NAME)
                    + "&targetCombatPower="
                    + target)));
    result.put(
        "gemRanking",
        ok(
            post(
                "/api/v1/characters/combat-power/gem-ranking",
                "{\"characterName\":\"" + NAME + "\",\"grade\":\"HERO\"}")));
    return result;
  }

  private JsonNode ok(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> critSpeed() throws Exception {
    return post("/api/v1/characters/crit-speed", "{\"characterName\":\"" + NAME + "\"}");
  }

  private JsonNode raw() throws Exception {
    return mapper
        .readTree(
            new ClassPathResource("kraken-score/l01.json")
                .getContentAsString(StandardCharsets.UTF_8))
        .path("raw");
  }

  /** 첫 보석 칸의 레벨을 한 단계 바꾸는 시뮬레이터 변경(10이면 9로). */
  private String gemChange() throws Exception {
    var gem = raw().path("ArmoryGem").path("Gems").get(0);
    int level = gem.path("Level").asInt();
    return "{\"kind\":\"GEM_LEVEL\",\"slot\":"
        + gem.path("Slot").asInt()
        + ",\"level\":"
        + (level >= 10 ? 9 : level + 1)
        + "}";
  }

  /** 판정이 어긋난(H6) 캐릭터를 조회한다(공식 조회는 대역). */
  private JsonNode search() throws Exception {
    var raw = raw();
    var profile =
        new CharacterProfile(
            NAME,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1770.00"),
            new BigDecimal("4070.75"),
            null,
            null,
            null,
            null,
            new ArrayList<>());
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    var fetched =
        new FetchedCharacter(
            details, "{}", "[]", KrakenScoreInputMapper.from(raw), List.of("H6_GEM"));
    when(detailsPort.fetch(anyString())).thenReturn(fetched);
    // 치명타·공이속은 원정대 없이 전투정보만 읽는다(같은 판정).
    when(detailsPort.fetchArmory(anyString())).thenReturn(fetched);
    return ok(get("/api/v1/characters/search?characterName=" + encode(NAME)));
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
