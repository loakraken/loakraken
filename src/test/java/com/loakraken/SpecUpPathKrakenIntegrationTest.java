package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import java.time.Instant;
import java.util.ArrayList;
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
 * 추천 순서 sort=KRAKEN(spec-up.support-order.enabled 켬, PM 총괄 11-15 (d)): 단계마다 크라켄 점수가 오르는 단계만 고르고, 단계
 * 점수는 앞 단계 점수에 이 단계 Δ를 더한 값이다. EFFICIENCY·DELTA 요청은 KRAKEN을 돌려받지 않는다(같은 캐릭터 KRAKEN 요청 뒤 캐시를 써도). 기준
 * 캐릭터 원문(l01), 연마 시세만 합성, 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "combat-power-sim.enabled=true",
      "spec-up.polish-path.enabled=true",
      "spec-up.support-order.enabled=true"
    })
class SpecUpPathKrakenIntegrationTest {
  private static final String SERVER = "점수순서서버";
  private static final String NAME = "점수순서";
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
    var keys = redis.keys("*점수순서*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void KRAKEN은_점수가_오르는_단계를_이어_고르고_다른_기준은_KRAKEN을_돌려받지_않는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    when(auctions.polishPrice(anyString(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              List<MarketAuctionService.PolishLine> lines = invocation.getArgument(1);
              long price = 10_000;
              for (var line : lines)
                price += (long) (Double.parseDouble(line.value().replaceAll("[+,%]", "")) * 1_000);
              return new MarketAuctionService.PolishPrice(
                  MarketAuctionService.PolishStatus.OK,
                  price,
                  Instant.parse("2026-11-15T05:00:00Z"));
            });
    search();
    var kraken = path("KRAKEN");
    assertThat(kraken.path("sort").asText()).isEqualTo("KRAKEN");
    // 스위치가 켜졌고 점수를 낼 수 있으면 목록·추천 순서(어느 기준이든) 모두 true(웹은 이 값으로 KRAKEN 버튼을 보인다).
    assertThat(kraken.path("supportOrderAvailable").asBoolean(false)).isTrue();
    var list = get("/api/v1/characters/spec-up?characterName=" + encode(NAME));
    assertThat(list.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(list.body()).at("/data/supportOrderAvailable").asBoolean(false))
        .isTrue();
    // 점수식: 딜러(웹 표기를 이 값으로 고른다).
    assertThat(kraken.path("krakenScoreRole").asText()).isEqualTo("DEALER");
    assertThat(mapper.readTree(list.body()).at("/data/krakenScoreRole").asText())
        .isEqualTo("DEALER");
    assertThat(absent(kraken.path("krakenSortUnavailableReason"))).isTrue();
    var start = kraken.path("krakenScore").decimalValue();
    assertThat(start).isNotNull();
    var steps = kraken.path("steps");
    assertThat(steps.size()).isPositive();
    var previous = start;
    for (var step : steps) {
      var id = step.path("candidateId").asText();
      var delta = step.path("deltaKrakenScore").decimalValue();
      var after = step.path("krakenScoreAfter").decimalValue();
      assertThat(delta).as(id).isPositive();
      // 단계 점수 = 앞 단계 점수 + 이 단계 Δ(반올림 전 차를 둘째 자리로라 0.01 안).
      assertThat(after.subtract(previous).subtract(delta).abs())
          .as(id)
          .isLessThanOrEqualTo(new BigDecimal("0.01"));
      if (step.path("extraCost").asLong() > 0)
        assertThat(step.path("krakenScorePer100kGold").decimalValue()).as(id).isPositive();
      // 전투력 필드는 그대로(딜러).
      assertThat(step.path("combatPowerAfter").decimalValue()).as(id).isNotNull();
      previous = after;
    }
    assertThat(kraken.path("finalKrakenScore").decimalValue()).isEqualByComparingTo(previous);
    // PM 조건 (1): 같은 캐릭터 KRAKEN 요청 뒤에도 EFFICIENCY·DELTA는 KRAKEN을 돌려받지 않는다(처음 계산과 캐시 둘 다).
    for (int round = 0; round < 2; round++)
      for (var sort : List.of("EFFICIENCY", "DELTA")) {
        var other = path(sort);
        assertThat(other.path("sort").asText()).as(sort + " " + round).isEqualTo(sort);
        assertThat(other.path("supportOrderAvailable").asBoolean(false)).as(sort).isTrue();
        assertThat(absent(other.path("krakenScore"))).as(sort).isTrue();
        assertThat(absent(other.path("finalKrakenScore"))).as(sort).isTrue();
        assertThat(absent(other.path("krakenSortUnavailableReason"))).as(sort).isTrue();
        for (var step : other.path("steps")) {
          assertThat(absent(step.path("deltaKrakenScore"))).as(sort).isTrue();
          assertThat(absent(step.path("krakenScoreAfter"))).as(sort).isTrue();
          assertThat(absent(step.path("krakenScorePer100kGold"))).as(sort).isTrue();
        }
      }
    // KRAKEN 다시(캐시): 같은 응답.
    assertThat(path("KRAKEN")).isEqualTo(kraken);
  }

  @Test
  void 서포터_KRAKEN은_점수로만_고르고_단계_전투력은_null이며_다른_기준은_지금과_같다() throws Exception {
    // 서포터 원문(l04): 공식 원문 표본에 보석 이름이 없어 효과 문구로 겁화·작열 이름을 붙이고 보석 시세만 합성으로 준다(보석 단계만 시세가 있음).
    var at = Instant.parse("2026-11-15T05:00:00Z");
    var cells = new ArrayList<MarketAuctionService.GemCell>();
    for (var kind : List.of("겁화", "작열"))
      for (int level = 10; level >= 1; level--)
        cells.add(
            new MarketAuctionService.GemCell(
                kind,
                level,
                level + "레벨 " + kind + "의 보석",
                new com.loakraken.market.application.AuctionPort.AuctionCell(
                    level == 10 ? 3_000_000L : level == 9 ? 1_000_000L : 400_000L, 5, at),
                null,
                MarketAuctionService.Status.READY));
    when(auctions.cachedGems()).thenReturn(cells);
    var cooldown = searchSupporter();
    var kraken = path(SUPPORTER, "KRAKEN");
    // 웹 계약 고정 표본(프론트 요청 11-15): 서포터 KRAKEN 경로 실제 직렬화 응답.
    ApiContractFixture.write(
        "spec-up-path-kraken-supporter.json",
        get("/api/v1/characters/spec-up/path?characterName="
                + encode(SUPPORTER)
                + "&sort=KRAKEN&limit=10")
            .body());
    assertThat(kraken.path("sort").asText()).isEqualTo("KRAKEN");
    assertThat(kraken.path("krakenScoreRole").asText()).isEqualTo("SUPPORT");
    assertThat(kraken.path("krakenScore").decimalValue()).isNotNull();
    assertThat(absent(kraken.path("finalCombatPower"))).isTrue();
    var steps = kraken.path("steps");
    assertThat(steps.size()).isPositive();
    var previous = kraken.path("krakenScore").decimalValue();
    for (var step : steps) {
      var id = step.path("candidateId").asText();
      // PM 조건 (1): 서포터 KRAKEN 경로만 단계 전투력이 null.
      assertThat(absent(step.path("deltaCombatPower"))).as(id).isTrue();
      assertThat(absent(step.path("combatPowerAfter"))).as(id).isTrue();
      assertThat(absent(step.path("combatPowerPer10kGold"))).as(id).isTrue();
      var delta = step.path("deltaKrakenScore").decimalValue();
      assertThat(delta).as(id).isPositive();
      var after = step.path("krakenScoreAfter").decimalValue();
      assertThat(after.subtract(previous).subtract(delta).abs())
          .as(id)
          .isLessThanOrEqualTo(new BigDecimal("0.01"));
      previous = after;
      // 작열(쿨) 보석 칸은 목록 KRAKEN 기본처럼 뺀다.
      assertThat(cooldown).as(id).doesNotContain(id.substring(0, id.lastIndexOf(':')));
    }
    assertThat(kraken.path("finalKrakenScore").decimalValue()).isEqualByComparingTo(previous);
    // 단계가 다 떨어지면 점수 문장(코드 리뷰 #967 P3).
    if ("NO_CANDIDATE".equals(kraken.path("stopReasonCode").asText()))
      assertThat(kraken.path("stopReason").asText()).contains("크라켄 점수");
    // 서포터 EFFICIENCY·DELTA는 지금과 같다: 단계 없이 서포터 이유로 멈춘다(점수 필드 없음).
    for (var sort : List.of("EFFICIENCY", "DELTA")) {
      var other = path(SUPPORTER, sort);
      assertThat(other.path("sort").asText()).as(sort).isEqualTo(sort);
      assertThat(other.path("steps").size()).as(sort).isZero();
      assertThat(other.path("stopReasonCode").asText())
          .as(sort)
          .isEqualTo("SUPPORTER_MODEL_PENDING");
      assertThat(absent(other.path("krakenScore"))).as(sort).isTrue();
    }
  }

  private static final String SUPPORTER = "점수순서서폿";

  /** 서포터 원문 l04로 검색. 보석에 효과 문구로 이름을 붙인다. 작열 보석 칸 이름(GEM_LEVEL:slot)을 돌려준다. */
  private java.util.Set<String> searchSupporter() throws Exception {
    var raw =
        (tools.jackson.databind.node.ObjectNode)
            mapper
                .readTree(
                    new ClassPathResource("kraken-score/l04.json")
                        .getContentAsString(StandardCharsets.UTF_8))
                .path("raw");
    var cooldownSlots = new java.util.HashSet<Integer>();
    for (var skill : raw.path("ArmoryGem").path("Effects").path("Skills"))
      if (skill.path("Description").toString().contains("재사용 대기시간"))
        cooldownSlots.add(skill.path("GemSlot").asInt());
    var cooldown = new java.util.HashSet<String>();
    for (var gem : raw.path("ArmoryGem").path("Gems")) {
      int slot = gem.path("Slot").asInt();
      boolean cool = cooldownSlots.contains(slot);
      if (cool) cooldown.add("GEM_LEVEL:" + slot);
      ((tools.jackson.databind.node.ObjectNode) gem)
          .put("Name", gem.path("Level").asInt() + "레벨 " + (cool ? "작열" : "겁화") + "의 보석");
    }
    var profile =
        new CharacterProfile(
            SUPPORTER,
            SERVER,
            "도화가",
            70,
            new BigDecimal("1770.00"),
            new BigDecimal("3000.00"),
            null,
            null,
            null,
            null,
            List.of());
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(SUPPORTER)).statusCode())
        .isEqualTo(200);
    return cooldown;
  }

  private JsonNode path(String sort) throws Exception {
    return path(NAME, sort);
  }

  private JsonNode path(String name, String sort) throws Exception {
    var response =
        get(
            "/api/v1/characters/spec-up/path?characterName="
                + encode(name)
                + "&sort="
                + sort
                + "&limit=10");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    SiteNames.assertAbsent(response.body());
    return mapper.readTree(response.body()).path("data");
  }

  private static boolean absent(JsonNode node) {
    return node.isNull() || node.isMissingNode();
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
    // 합성: 이 원문의 장신구 툴팁에는 거래 줄이 잘려 있어 연마 비용을 정할 수 없다. 장신구마다 ItemTitle 뒤에 '거래 제한 아이템 레벨 1680' 줄을 넣는다.
    for (var equipment : raw.path("ArmoryEquipment")) {
      var type = equipment.path("Type").asText();
      if (!List.of("목걸이", "귀걸이", "반지").contains(type)) continue;
      ((tools.jackson.databind.node.ObjectNode) equipment)
          .put("Tooltip", withTradeLine(equipment.path("Tooltip").asText()));
    }
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

  /** 툴팁 JSON의 ItemTitle 바로 뒤에 거래 제한 줄(SingleTextBox)을 넣고 Element 번호를 다시 매긴다. */
  private String withTradeLine(String tooltip) {
    var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(tooltip);
    var elements = new ArrayList<JsonNode>();
    root.forEach(elements::add);
    var out = mapper.createObjectNode();
    int n = 0;
    for (var element : elements) {
      out.set(String.format("Element_%03d", n++), element);
      if ("ItemTitle".equals(element.path("type").asText())) {
        var trade = mapper.createObjectNode();
        trade.put("type", "SingleTextBox");
        trade.put("value", "<FONT SIZE='12'>거래 제한 아이템 레벨 1680</FONT>");
        out.set(String.format("Element_%03d", n++), trade);
      }
    }
    return mapper.writeValueAsString(out);
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
