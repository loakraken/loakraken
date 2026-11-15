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
 * 연마 교체 줄 Δ전투력(spec-up.polish-cp.enabled 켬): 목표 장신구는 Δ크라켄 점수와 같다(점수 옵션 두 줄만, 나머지 줄은 전투력 몫 0). 기준
 * 캐릭터 원문(l01)의 귀걸이 1 '무공%[중] 공%[하] → 무공%[중] 공%[중]'은 비교 기준 B 경로 단계 값 4,052.77 → 4,072.69와 같아야 한다(계산
 * 검증 10-12 기록, 셋째 줄 공격력 +80이 빠진 값). 줄 하나만 바꾸면(셋째 줄 유지) 4,074.97이라 두 정의를 가른다. 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "spec-up.polish-cp.enabled=true"
    })
class SpecUpPolishCpIntegrationTest {
  private static final String SERVER = "연마전투력서버";
  private static final String NAME = "연마전투력";
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
    var keys = redis.keys("character:v*연마전투력*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 연마_교체_줄은_셋째_줄_없는_목표_장신구로_Δ전투력을_내고_비교_기준_경로_값과_같다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search();
    var candidates = guide("&sort=DELTA&limit=200").path("candidates");
    var polish = find(candidates, "POLISH:7:MM");
    assertThat(polish.path("from").asText())
        .isEqualTo("무기 공격력 +1.80%(중) · 공격력 +80(하) · 공격력 +0.40%(하)");
    assertThat(polish.path("to").asText()).isEqualTo("무기 공격력 +1.80%(중) · 공격력 +0.95%(중)");
    assertThat(polish.path("status").asText()).isEqualTo("OK");
    // 4,052.77 × 1.0095 ÷ 1.0040 ÷ (1 + 80 ÷ 142,857) = 4,072.69(B 경로 단계). 줄 하나만 바꾸면 +22.20.
    assertThat(polish.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("19.92");
    assertThat(polish.path("reasonCode").isNull()).isTrue();
    // 공개 시뮬레이터 입력으로 나타내지 않는다(셋째 줄 '모름'은 요청에 없음): change는 그대로 null.
    assertThat(polish.path("change").isNull()).isTrue();
    // Δ크라켄 점수는 그대로(같은 목표).
    assertThat(polish.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("19.38");
    // 1680 고대가 아닌 장신구 줄이 없어도, 연마 줄은 모두 계산됐거나 이유가 있다(빈 이유 없음).
    for (var candidate : candidates)
      if ("POLISH".equals(candidate.path("kind").asText())
          && !"OK".equals(candidate.path("status").asText()))
        assertThat(candidate.path("reasonCode").asText())
            .as(candidate.path("id").asText())
            .isNotBlank();
  }

  @Test
  void 주옵션이_모두_상인_장신구는_고정값_줄을_남겨_셋째_줄_후보_값으로_바꾼다() throws Exception {
    // 합성 장신구(계산 검증 11-15 요청, 캐릭터 원문의 한 칸만 바꿈): 귀걸이 1을 무공%[상] 공%[상] 공격력 +80(하)로. 비교 기준은 이때 고정값 줄을
    // 남기고 그 줄을 올리는 후보를 낸다.
    when(auctions.cachedGems()).thenReturn(List.of());
    search(
        tooltip ->
            tooltip
                .replace(
                    "무기 공격력 <FONT COLOR='CE43FC'>+1.80%</FONT>",
                    "무기 공격력 <FONT COLOR='FE9600'>+3.00%</FONT>")
                .replace(
                    "공격력 <FONT COLOR='00B5FF'>+0.40%</FONT>",
                    "공격력 <FONT COLOR='FE9600'>+1.55%</FONT>"));
    var candidates = guide("&sort=DELTA&limit=200").path("candidates");
    var flat = find(candidates, "POLISH:7:HHAH");
    assertThat(flat.path("to").asText())
        .isEqualTo("무기 공격력 +3.00%(상) · 공격력 +1.55%(상) · 공격력 +390(상)");
    assertThat(flat.path("status").asText()).isEqualTo("OK");
    // 고정값 줄 +80 → +390만: 4,052.77 × (1 + 390/142,857) ÷ (1 + 80/142,857) − 4,052.77 = 8.79. +80을 남긴
    // 채 +390을 더하면
    // 11.06이다.
    assertThat(flat.path("deltaCombatPower").decimalValue()).isEqualByComparingTo("8.79");
    // 두 주옵션이 상이면 주옵션을 바꾸는 후보는 없다(셋째 줄 후보만).
    for (var candidate : candidates) {
      var id = candidate.path("id").asText();
      if (id.startsWith("POLISH:7:")) assertThat(id).startsWith("POLISH:7:HH");
    }
  }

  @Test
  void 점수_입력_줄이_툴팁_줄과_다르면_그_장신구는_전투력_없이_점수만_낸다() throws Exception {
    // 합성(코드 리뷰 #945 P3): 귀걸이 1의 점수 입력 둘째 줄만 다른 값으로. 줄 짝은 순서로 맞추므로 틀린 Δ 대신 계산하지 않아야 한다.
    when(auctions.cachedGems()).thenReturn(List.of());
    search(
        tooltip -> tooltip,
        input ->
            ((tools.jackson.databind.node.ArrayNode) input.at("/armory/accessory/earing1/option"))
                .set(1, "공격력 +195"));
    var candidates = guide("&sort=DELTA&limit=200").path("candidates");
    int earring = 0;
    for (var candidate : candidates) {
      var id = candidate.path("id").asText();
      if (id.startsWith("POLISH:7:")) {
        earring++;
        assertThat(candidate.path("status").asText()).as(id).isEqualTo("UNSUPPORTED");
        assertThat(candidate.path("reasonCode").asText()).as(id).isEqualTo("SCORE_ONLY");
      } else if (id.startsWith("POLISH:") && "OK".equals(candidate.path("status").asText()))
        assertThat(candidate.path("deltaCombatPower").isNull()).as(id).isFalse();
    }
    assertThat(earring).isPositive();
    // 다른 부위 연마 줄은 그대로 계산된다.
    assertThat(
            candidates
                .valueStream()
                .filter(
                    c ->
                        c.path("id").asText().startsWith("POLISH:")
                            && "OK".equals(c.path("status").asText()))
                .count())
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
    search(tooltip -> tooltip);
  }

  private void search(java.util.function.UnaryOperator<String> earring) throws Exception {
    search(earring, input -> {});
  }

  /** earring은 귀걸이 1(장비 순번 7) 툴팁을 바꾸는 함수(합성 장신구), input은 점수 입력만 바꾸는 함수(툴팁과 점수 입력이 갈라진 경우를 만든다). */
  private void search(
      java.util.function.UnaryOperator<String> earring,
      java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> input)
      throws Exception {
    var raw =
        (tools.jackson.databind.node.ObjectNode)
            mapper
                .readTree(
                    new ClassPathResource("kraken-score/l01.json")
                        .getContentAsString(StandardCharsets.UTF_8))
                .path("raw");
    var item = (tools.jackson.databind.node.ObjectNode) raw.path("ArmoryEquipment").get(7);
    item.put("Tooltip", earring.apply(item.path("Tooltip").asText()));
    // 공식 전투력은 비교 기준 B 표본 시각(10-12) 값. 연마 줄은 줄마다 독립 배수라 Δ는 이 값과 바꾼 줄로만 정해진다.
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
        .thenReturn(new FetchedCharacter(details, "{}", "[]", scoreInput(raw, input)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(200);
  }

  private static tools.jackson.databind.node.ObjectNode scoreInput(
      JsonNode raw, java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> change) {
    var input = KrakenScoreInputMapper.from(raw);
    change.accept(input);
    return input;
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
