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

/**
 * 어빌리티 스톤 줄 Δ전투력(spec-up.stone-cp.enabled 켬): 추천 순서(목표 경로)와 같은 변경(STONE_ENGRAVING 둘, 목표 레벨)이라 같은
 * 캐릭터에 그 두 변경을 시뮬레이터에 넣은 Δ와 같아야 한다. 기준 캐릭터 원문(l01). 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "combat-power-sim.enabled=true",
      "spec-up.stone-cp.enabled=true"
    })
class SpecUpStoneCpIntegrationTest {
  private static final String SERVER = "스톤전투력서버";
  private static final String NAME = "스톤전투력";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*스톤전투력*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 스톤_줄은_스톤_각인_두_변경을_시뮬레이터에_넣은_Δ전투력과_같다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search();
    var candidates = guide("&sort=DELTA&limit=200").path("candidates");
    int stones = 0;
    for (var candidate : candidates) {
      var id = candidate.path("id").asText();
      if (!id.startsWith("ABILITY_STONE:")) continue;
      stones++;
      assertThat(candidate.path("status").asText()).as(candidate.toString()).isEqualTo("OK");
      assertThat(candidate.path("change").isNull()).as(id).isTrue();
      // to = "97돌 아드레날린 Lv.2 · 원한 Lv.3" → 각인마다 STONE_ENGRAVING
      var parts = candidate.path("to").asText().replaceFirst("^\\S+돌 ", "").split(" · ");
      var changes = new StringBuilder("[");
      for (var part : parts) {
        var at = part.lastIndexOf(" Lv.");
        if (changes.length() > 1) changes.append(',');
        changes
            .append("{\"kind\": \"STONE_ENGRAVING\", \"name\": \"")
            .append(part, 0, at)
            .append("\", \"level\": ")
            .append(part.substring(at + 4))
            .append('}');
      }
      var simulated = simulate(changes.append(']').toString());
      assertThat(simulated.statusCode()).as(simulated.body()).isEqualTo(200);
      var data = mapper.readTree(simulated.body()).path("data");
      assertThat(data.path("partial").asBoolean()).as(id).isFalse();
      assertThat(candidate.path("deltaCombatPower").decimalValue())
          .as(id)
          .isEqualByComparingTo(data.path("delta").decimalValue());
      // 비용(기대 키트 × 50골드)이 있으니 1만 골드당 전투력도 있다.
      assertThat(candidate.path("combatPowerPer10kGold").isNull()).as(id).isFalse();
    }
    // 97·106·107·99·109·1010돌 가운데 점수가 오르는 조합(97·106·107은 레퍼런스: 2:3, 1:4, 2:4, PM 총괄 11-19로
    // 99·109·1010돌도).
    assertThat(stones).isEqualTo(6);
    assertThat(find(candidates, "ABILITY_STONE:2:3").path("deltaCombatPower").decimalValue())
        .isPositive();
  }

  private static JsonNode find(JsonNode candidates, String id) {
    for (var candidate : candidates) if (id.equals(candidate.path("id").asText())) return candidate;
    throw new AssertionError(id + " 후보가 없습니다");
  }

  private JsonNode guide(String query) throws Exception {
    var response = get("/api/v1/characters/spec-up?characterName=" + encode(NAME) + query);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    SiteNames.assertAbsent(response.body());
    return mapper.readTree(response.body()).path("data");
  }

  private void search() throws Exception {
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    // 합성: 이 원문은 각인 설명(Description)이 잘려 있어 시뮬레이터가 각인·스톤을 계산하지 않는다(ENGRAVING_MISMATCH). 스톤 각인 둘에 계수표
    // 값으로 만든 설명을 넣는다(아드레날린 각인서 3·스톤 3 = 중첩당 공격력 1.73%·치명 18.50%, 원한 각인서 0·스톤 2 = 21.75%).
    for (var effect : raw.at("/ArmoryEngraving/ArkPassiveEffects")) {
      var name = effect.path("Name").asText();
      if ("아드레날린".equals(name))
        ((tools.jackson.databind.node.ObjectNode) effect)
            .put("Description", "공격력이 1.73% 증가하며 최대 6중첩 시 치명타 적중률이 18.50% 증가한다.");
      if ("원한".equals(name))
        ((tools.jackson.databind.node.ObjectNode) effect)
            .put("Description", "보스 등급 이상 몬스터에게 주는 피해가 21.75% 증가한다.");
    }
    var profile =
        new CharacterProfile(
            NAME,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1770.00"),
            new BigDecimal("4052.77"),
            null,
            null,
            null,
            null,
            List.of());
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(200);
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> simulate(String changes) throws Exception {
    var body = "{\"characterName\": \"" + NAME + "\", \"changes\": " + changes + "}";
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
