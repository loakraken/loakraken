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
   * P0 핫픽스(11-09): 방어구 재련·상급 재련과 완갑의 전투력 Δ는 같은 시점 공식 전투력 대비 약 0.66배라(주 스탯 경로) 계산하지 않는다. 기본 공격력 툴팁이
   * 있어 재련 전투력을 계산할 수 있는 프로필에서도 목록의 방어구·완갑 줄은 UNSUPPORTED(MODEL_UNCONFIRMED, Δ 없음)이고 추천 순서는 그 줄을 단계로
   * 쓰지 않는다. 무기 재련 줄은 그대로 계산한다.
   */
  @Test
  void 방어구_재련과_완갑은_목록과_추천_순서에서_전투력을_계산하지_않고_무기는_그대로다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search(
        List.of(
            new CharacterProfile.Stat(
                "공격력", "0", List.of("힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 267396 입니다."))));
    var guide = guide();
    var row = rows(guide).get(ARM_GUARD_ROW);
    assertThat(row).isNotNull();
    assertThat(row.path("status").asText()).as(row.toString()).isEqualTo("UNSUPPORTED");
    assertThat(row.path("reasonCode").asText()).isEqualTo("MODEL_UNCONFIRMED");
    assertThat(row.path("deltaCombatPower").isNull()).isTrue();
    // 방어구 재련·상급 재련 줄도 모두 Δ 없이 UNSUPPORTED, 무기 재련 줄은 OK(같은 프로필이라 재련 모델은 계산할 수 있다).
    var weaponOk = 0;
    var armorRows = 0;
    for (var candidate : guide.path("candidates")) {
      var id = candidate.path("id").asText();
      if (!id.startsWith("EQUIPMENT_REFINE:") && !id.startsWith("EQUIPMENT_ADVANCED_REFINE:"))
        continue;
      if (id.startsWith("EQUIPMENT_REFINE:0:") || id.startsWith("EQUIPMENT_ADVANCED_REFINE:0:")) {
        if ("OK".equals(candidate.path("status").asText())) weaponOk++;
        continue;
      }
      armorRows++;
      assertThat(candidate.path("status").asText()).as(id).isEqualTo("UNSUPPORTED");
      assertThat(candidate.path("deltaCombatPower").isNull()).as(id).isTrue();
      assertThat(candidate.path("reasonCode").asText()).as(id).isEqualTo("MODEL_UNCONFIRMED");
    }
    assertThat(armorRows).isPositive();
    assertThat(weaponOk).isPositive();
    // 추천 순서는 방어구·완갑 재련을 단계로도, 비용 모르는 후보로도 쓰지 않는다.
    var on = path();
    for (var step : on.path("steps")) {
      var id = step.path("candidateId").asText();
      assertThat(id).doesNotStartWith("EQUIPMENT_REFINE:16:");
      assertThat(id.matches("EQUIPMENT_(ADVANCED_)?REFINE:[1-9][0-9]*:.*")).as(id).isFalse();
    }
    for (var excluded : on.path("unpriced")) {
      var id = excluded.path("candidateId").asText();
      assertThat(id.matches("EQUIPMENT_(ADVANCED_)?REFINE:[1-9][0-9]*:.*")).as(id).isFalse();
    }
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
