package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CombatPowerSimulationService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 점수 입력에서 만든 줄(연마 교체·스톤)의 전투력은 줄마다 따로 실패한다(PM 총괄 11-15 14:14, 스위치를 켜기 전 필수): 시뮬레이터가 한 줄에서 예외를 던져도
 * 목록은 200이고 그 줄만 점수만(SCORE_ONLY) 남는다. 기준 캐릭터 원문(l01), 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "spec-up.polish-cp.enabled=true",
      "spec-up.stone-cp.enabled=true"
    })
class SpecUpScoreRowIsolationIntegrationTest {
  private static final String SERVER = "줄격리서버";
  private static final String NAME = "줄격리";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  @MockitoSpyBean CombatPowerSimulationService simulator;
  @Autowired io.micrometer.core.instrument.MeterRegistry meters;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*줄격리*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 한_줄에서_시뮬레이터가_실패해도_목록은_200이고_그_줄만_점수만_남는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    // 귀걸이 1(장비 순번 7) 연마 교체와 스톤 줄(스톤 각인 변경)에서만 예외.
    doThrow(new IllegalStateException("합성 실패"))
        .when(simulator)
        .simulatePolishSwap(any(), any(), eq(7), any(), any());
    doThrow(new IllegalStateException("합성 실패"))
        .when(simulator)
        .simulate(
            any(),
            any(CombatPowerSimulationService.Models.class),
            argThat(
                changes ->
                    changes != null
                        && changes.stream()
                            .anyMatch(
                                change ->
                                    change.kind()
                                        == CombatPowerSimulationService.Kind.STONE_ENGRAVING)));
    search();
    var candidates = guide("&sort=DELTA&limit=200").path("candidates");
    int failed = 0;
    int computed = 0;
    for (var candidate : candidates) {
      var id = candidate.path("id").asText();
      if (id.startsWith("POLISH:7:") || id.startsWith("ABILITY_STONE:")) {
        failed++;
        assertThat(candidate.path("status").asText()).as(id).isEqualTo("UNSUPPORTED");
        assertThat(candidate.path("reasonCode").asText()).as(id).isEqualTo("SCORE_ONLY");
        // Δ크라켄 점수는 그대로.
        assertThat(candidate.path("deltaKrakenScore").isNull()).as(id).isFalse();
      } else if (id.startsWith("POLISH:") && "OK".equals(candidate.path("status").asText()))
        computed++;
    }
    assertThat(failed).isGreaterThanOrEqualTo(4);
    // 다른 장신구 연마 줄은 계산된다(한 줄 실패가 다른 줄로 번지지 않음).
    assertThat(computed).isPositive();
    // 실패 수: 줄마다 하나(종류·예외 클래스 태그만).
    assertThat(
            meters
                .get("loakraken.spec_up.row_cp_failed")
                .tag("kind", "ABILITY_STONE")
                .tag("error", "IllegalStateException")
                .counter()
                .count())
        .isEqualTo(3.0);
    assertThat(count("POLISH", "IllegalStateException")).isEqualTo(failed - 3);
  }

  @Test
  void 점수_입력_줄이_툴팁_줄과_다르면_그_장신구_줄만_점수만이고_line_mismatch로_센다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    double mismatchBefore = count("POLISH", "line_mismatch");
    double unmappableBefore = count("POLISH", "unmappable");
    double stoneBefore = count("ABILITY_STONE", "unmappable");
    // 점수 입력 원문만 귀걸이 1(장비 순번 7)의 공격력 +80 줄을 +195로: 두 원문 파서가 갈라진 경우(예외 없이 계산하지 않는 줄).
    var raw = raw();
    var scoreRaw = raw.deepCopy();
    var item = (tools.jackson.databind.node.ObjectNode) scoreRaw.path("ArmoryEquipment").get(7);
    var tooltip = item.path("Tooltip").asText();
    assertThat(tooltip).contains("+80</FONT>");
    item.put("Tooltip", tooltip.replace("+80</FONT>", "+195</FONT>"));
    search(raw, scoreRaw);
    var candidates = guide("&sort=DELTA&limit=200").path("candidates");
    int rejected = 0;
    int computed = 0;
    for (var candidate : candidates) {
      var id = candidate.path("id").asText();
      if (id.startsWith("POLISH:7:")) {
        rejected++;
        assertThat(candidate.path("status").asText()).as(id).isEqualTo("UNSUPPORTED");
        assertThat(candidate.path("reasonCode").asText()).as(id).isEqualTo("SCORE_ONLY");
        assertThat(candidate.path("deltaKrakenScore").isNull()).as(id).isFalse();
      } else if (id.startsWith("POLISH:") && "OK".equals(candidate.path("status").asText()))
        computed++;
    }
    assertThat(rejected).isPositive();
    assertThat(computed).isPositive();
    // 거른 줄마다 하나, 이 원문의 다른 줄은 거르지 않는다.
    assertThat(count("POLISH", "line_mismatch") - mismatchBefore).isEqualTo(rejected);
    assertThat(count("POLISH", "unmappable") - unmappableBefore).isZero();
    assertThat(count("ABILITY_STONE", "unmappable") - stoneBefore).isZero();
  }

  private double count(String kind, String error) {
    var counter =
        meters
            .find("loakraken.spec_up.row_cp_failed")
            .tag("kind", kind)
            .tag("error", error)
            .counter();
    return counter == null ? 0 : counter.count();
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

  private JsonNode raw() throws Exception {
    return mapper
        .readTree(
            new ClassPathResource("kraken-score/l01.json").getContentAsString(StandardCharsets.UTF_8))
        .path("raw");
  }

  private void search() throws Exception {
    var raw = raw();
    search(raw, raw);
  }

  private void search(JsonNode raw, JsonNode scoreRaw) throws Exception {
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
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(scoreRaw)));
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
