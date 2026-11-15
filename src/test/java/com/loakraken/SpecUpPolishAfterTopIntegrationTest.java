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
 * 추천 순서의 '상·상 뒤' 연마 후보(PM 총괄 11-15, 연마 단계 스위치를 켜기 전 필수): 처음에 상·상이 아닌 장신구가 경로에서 상·상(HH) 매물이 되면, 비교
 * 기준처럼 셋째 줄 고정값 상(HHAH·HHWH) 후보가 생긴다. 그 전에는 고를 수 없고(처음 상태의 비용 모름 목록에도 없음), 고른 뒤에도 단계 changes를 이어 다시
 * 재면 단계 전투력과 같다. 기준 캐릭터 원문(l01) + 합성 거래 줄·연마 시세. 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      "combat-power-sim.enabled=true",
      "spec-up.polish-path.enabled=true"
    })
class SpecUpPolishAfterTopIntegrationTest {
  private static final String SERVER = "연마상상서버";
  private static final String NAME = "연마상상";
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
    var keys = redis.keys("character:v*연마상상*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    var paths = redis.keys("*연마상상*");
    if (paths != null && !paths.isEmpty()) redis.delete(paths);
  }

  @Test
  void 상상이_된_장신구에만_셋째_줄_고정값_상_단계가_뒤따르고_다시_재도_같다() throws Exception {
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
    var response =
        get(
            "/api/v1/characters/spec-up/path?characterName="
                + encode(NAME)
                + "&sort=EFFICIENCY&limit=30");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    var steps = data.path("steps");
    var chosen = new java.util.HashMap<String, String>();
    var all = new ArrayList<JsonNode>();
    var ids = new ArrayList<String>();
    int afterTop = 0;
    for (var step : steps) {
      var id = step.path("candidateId").asText();
      ids.add(id);
      var slot = id.substring(0, id.lastIndexOf(':'));
      var code = id.substring(id.lastIndexOf(':') + 1);
      // 셋째 줄 단계(코드 네 글자)는 그 장신구의 앞 선택이 상·상(HH…)일 때만 온다.
      if ((code.equals("HHAH") || code.equals("HHWH")) && "HH".equals(chosen.get(slot))) {
        afterTop++;
        // 상·상 매물 다음 셋째 줄 상은 더 드는 비용이 있다(목표 시세 키에 고정값을 남김, 코드 리뷰 #957 P1: 처음 장신구 기준이면 HH와 같은 키라
        // 0이었다).
        assertThat(step.path("extraCost").asLong()).as(id + " 더 드는 비용").isPositive();
      }
      if (code.length() == 4 && chosen.containsKey(slot))
        assertThat(chosen.get(slot)).as(id + " " + ids).startsWith("HH");
      chosen.put(slot, code);
      step.path("changes").forEach(all::add);
      var simulated = simulate(mapper.writeValueAsString(all));
      assertThat(simulated.statusCode()).as(simulated.body()).isEqualTo(200);
      assertThat(mapper.readTree(simulated.body()).at("/data/after").decimalValue())
          .as(id)
          .isEqualByComparingTo(step.path("combatPowerAfter").decimalValue());
    }
    assertThat(afterTop).as("상·상 뒤 셋째 줄 단계 " + ids).isPositive();
    // 처음 상태의 비용 모름 목록에도 셋째 줄 후보가 없다(이 원문에는 처음부터 상·상인 장신구가 없음).
    for (var excluded : data.path("unpriced"))
      assertThat(excluded.path("candidateId").asText())
          .doesNotEndWith("HHAH")
          .doesNotEndWith("HHWH");
  }

  @Test
  void 셋째_줄_매물_시세가_없으면_비용을_모르고_추천_순서에_들지_않으며_공짜로_앞서지_않는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    // 실제 시세 키처럼 두 줄 조합만 매물이 있다: 고정값 줄이 든 키는 시세 없음(MISSING).
    when(auctions.polishPrice(anyString(), anyList(), any()))
        .thenAnswer(
            invocation -> {
              List<MarketAuctionService.PolishLine> lines = invocation.getArgument(1);
              if (lines.stream().anyMatch(line -> !line.percent()))
                return new MarketAuctionService.PolishPrice(
                    MarketAuctionService.PolishStatus.MISSING, null, null);
              long price = 10_000;
              for (var line : lines)
                price += (long) (Double.parseDouble(line.value().replaceAll("[+,%]", "")) * 1_000);
              return new MarketAuctionService.PolishPrice(
                  MarketAuctionService.PolishStatus.OK,
                  price,
                  Instant.parse("2026-11-15T05:00:00Z"));
            });
    search();
    // 추천 순서(시세가 있는 단계만): 셋째 줄 상 단계는 없다.
    var path =
        get(
            "/api/v1/characters/spec-up/path?characterName="
                + encode(NAME)
                + "&sort=EFFICIENCY&limit=30");
    assertThat(path.statusCode()).as(path.body()).isEqualTo(200);
    var hh = 0;
    for (var step : mapper.readTree(path.body()).at("/data/steps")) {
      var id = step.path("candidateId").asText();
      if (id.endsWith(":HH")) hh++;
      assertThat(id).doesNotEndWith("HHAH").doesNotEndWith("HHWH");
    }
    // 상·상 단계는 골랐다(그래서 셋째 줄 후보가 열렸지만 시세가 없어 빠졌다).
    assertThat(hh).isPositive();
    // 목표 경로(비용 모르는 단계도 Δ 순으로 뒤에): 셋째 줄 상 단계가 나오면 비용·더 드는 비용은 모름이고 0이 아니다.
    var before = mapper.readTree(path.body()).at("/data/combatPower").decimalValue();
    var goal =
        get(
            "/api/v1/characters/spec-up/goal?characterName="
                + encode(NAME)
                + "&targetCombatPower="
                + before
                    .multiply(new java.math.BigDecimal("1.3"))
                    .setScale(0, java.math.RoundingMode.UP));
    assertThat(goal.statusCode()).as(goal.body()).isEqualTo(200);
    for (var step : mapper.readTree(goal.body()).at("/data/steps")) {
      var id = step.path("candidateId").asText();
      var unknown = step.at("/cost/amount").isNull() || step.at("/cost/amount").isMissingNode();
      if (id.endsWith("HHAH") || id.endsWith("HHWH")) {
        assertThat(unknown).as(id + " 비용").isTrue();
        assertThat(step.at("/cost/status").asText()).as(id).isNotEqualTo("OK");
        assertThat(step.path("extraCost").isNull()).as(id + " 더 드는 비용").isTrue();
      }
    }
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
