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
import java.util.LinkedHashMap;
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
 * 완갑 줄 스위치(spec-up.arm-guard.enabled, 기본 켬): 켠 상태로 캐릭터·시세·목표 경로·회차별 최적 캐시를 채운 뒤 끄면, 다음 요청부터 목록에 완갑
 * 재련 줄이 없고 줄 상세는 SPEC_UP_CANDIDATE_NOT_FOUND다. 다른 줄과 목표 경로는 그대로다(코드 리뷰·PM 총괄 10-25).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spec-up.enabled=true", "kraken-score.enabled=true"})
@DirtiesContext
class SpecUpArmGuardSwitchIntegrationTest {
  private static final String SERVER = "완갑스위치서버";
  private static final String NAME = "완갑스위치";
  private static final String ARM_GUARD_ROW = "EQUIPMENT_REFINE:16:15";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ConfigurableEnvironment environment;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;

  private final JsonMapper mapper = new JsonMapper();
  private String target;

  @BeforeEach
  @AfterEach
  void 정리() {
    environment.getPropertySources().remove("arm-guard-off");
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*완갑스위치*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 켠_채로_캐시를_채운_뒤_끄면_완갑_줄만_빠지고_줄_상세는_404다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search();
    // 켠 상태: 완갑 줄이 있고 줄 상세 200. 목표 경로도 계산해 캐시에 둔다.
    var onData = guide();
    var on = rows(onData);
    // 한두 단계로 닿는 목표(시간 제한에 걸리지 않게)
    target =
        onData.path("combatPower").decimalValue().add(BigDecimal.ONE).toBigInteger().toString();
    assertThat(on).containsKey(ARM_GUARD_ROW);
    assertThat(on.values()).anyMatch(row -> "ARM_GUARD".equals(row.at("/change/kind").asText()));
    assertThat(detail().statusCode()).isEqualTo(200);
    var goalOn = goal();

    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource("arm-guard-off", Map.of("spec-up.arm-guard.enabled", "false")));

    // 끈 상태: 같은 캐시에서 완갑 줄만 빠지고, 나머지 줄(비용·Δ 포함)은 그대로.
    var off = rows(guide());
    assertThat(off.values()).noneMatch(row -> "ARM_GUARD".equals(row.at("/change/kind").asText()));
    var expected = new LinkedHashMap<>(on);
    expected.values().removeIf(row -> "ARM_GUARD".equals(row.at("/change/kind").asText()));
    assertThat(off).isEqualTo(expected);
    var response = detail();
    assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("SPEC_UP_CANDIDATE_NOT_FOUND");
    // 목표 경로에는 완갑 단계가 없어(무기·방어구 재련만) 끄기 전과 같다.
    var goalOff = goal();
    assertThat(goalOff).isEqualTo(goalOn);
    assertThat(goalOff.path("steps").size()).isPositive();
    var armGuardTarget = on.get(ARM_GUARD_ROW).path("target").asText();
    for (var step : goalOff.path("steps"))
      assertThat(step.path("target").asText()).isNotEqualTo(armGuardTarget);

    // 다시 켜면 돌아온다.
    environment.getPropertySources().remove("arm-guard-off");
    assertThat(rows(guide())).isEqualTo(on);
  }

  /**
   * 추천 순서도 완갑 재련 단계를 다음 단계 후보로 둔다(실제 시뮬레이터). 기본 공격력 툴팁이 있어 완갑 전투력이 계산되는 프로필에서, 목록 완갑 줄(+15)이 OK면 추천
   * 순서에도 같은 id·같은 Δ로 나온다(재료 시세가 없어 비용을 몰라 unpriced). 스위치를 끄면 어디에도 없다.
   */
  @Test
  void 추천_순서는_완갑_단계를_목록_완갑_줄과_같은_전투력으로_후보에_둔다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(
        List.of(
            new CharacterProfile.Stat(
                "공격력", "0", List.of("힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 267396 입니다."))));
    var row = rows(guide()).get(ARM_GUARD_ROW);
    assertThat(row).isNotNull();
    assertThat(row.path("status").asText()).as(row.toString()).isEqualTo("OK");
    var on = path();
    assertThat(on.path("ruleVersion").asText()).isEqualTo("spec-up-path-v2");
    JsonNode item = null;
    for (var excluded : on.path("unpriced"))
      if (ARM_GUARD_ROW.equals(excluded.path("candidateId").asText())) item = excluded;
    assertThat(item).as(on.toString()).isNotNull();
    assertThat(item.path("kind").asText()).isEqualTo("ARM_GUARD");
    assertThat(item.path("deltaCombatPower").decimalValue())
        .isEqualByComparingTo(row.path("deltaCombatPower").decimalValue());

    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource("arm-guard-off", Map.of("spec-up.arm-guard.enabled", "false")));
    var off = path();
    for (var excluded : off.path("unpriced"))
      assertThat(excluded.path("candidateId").asText()).doesNotStartWith("EQUIPMENT_REFINE:16:");
    for (var step : off.path("steps"))
      assertThat(step.path("candidateId").asText()).doesNotStartWith("EQUIPMENT_REFINE:16:");
  }

  private JsonNode path() throws Exception {
    var response = get("/api/v1/characters/spec-up/path?limit=1&characterName=" + encode(NAME));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private JsonNode guide() throws Exception {
    var response =
        get("/api/v1/characters/spec-up?characterName=" + encode(NAME) + "&sort=DELTA&limit=200");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> detail() throws Exception {
    return get(
        "/api/v1/characters/spec-up/candidates/"
            + ARM_GUARD_ROW
            + "?characterName="
            + encode(NAME));
  }

  private JsonNode goal() throws Exception {
    var response =
        get(
            "/api/v1/characters/spec-up/goal?characterName="
                + encode(NAME)
                + "&targetCombatPower="
                + target);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    return data;
  }

  /** 후보 id → 후보(목록 순서). */
  private static Map<String, JsonNode> rows(JsonNode data) {
    var rows = new LinkedHashMap<String, JsonNode>();
    for (var candidate : data.path("candidates"))
      rows.put(candidate.path("id").asText(), candidate);
    return rows;
  }

  private void search() throws Exception {
    search(List.of());
  }

  /** stats: 프로필 능력치(재련 전투력 계산에 기본 공격력 툴팁이 필요하다). 기본은 비어 있어 재련·완갑 전투력은 계산하지 않는다. */
  private void search(List<CharacterProfile.Stat> stats) throws Exception {
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
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
            stats);
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

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
