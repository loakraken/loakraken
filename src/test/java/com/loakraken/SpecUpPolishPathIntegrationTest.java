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
 * 추천 순서의 연마 단계(spec-up.polish-path.enabled 켬, PM 총괄 11-15 (c) A안): 단계 changes(줄마다 POLISH, 빠진 줄은
 * option NONE)를 공개 시뮬레이터에 차례로 넣은 값이 단계 전투력과 같아야 한다(칸마다 마지막 변경만). 빠지는 고정값 줄(기준 캐릭터 원문 l01의 장신구들)과 남는
 * 고정값 줄(귀걸이 1을 합성으로 무공%[상] 공%[상] 공격력 +80으로 바꾼 장신구) 둘 다. 연마 시세만 합성으로 주어 추천 순서가 연마 단계만 고르게 한다. 공식 호출
 * 0.
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
class SpecUpPolishPathIntegrationTest {
  private static final String SERVER = "연마경로서버";
  private static final String NAME = "연마경로";
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
    var keys = redis.keys("character:v*연마경로*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    var paths = redis.keys("*연마경로*");
    if (paths != null && !paths.isEmpty()) redis.delete(paths);
  }

  @Test
  void 연마_단계의_changes를_시뮬레이터에_다시_넣으면_단계_전투력과_같다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    // 합성 연마 시세: 줄 수치 합에 비례(등급이 오를수록 비쌈). 연마만 시세가 있어 추천 순서는 연마 단계만 고른다.
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
    // 원문 그대로: 귀걸이 1 등 고정값 줄이 빠지는 단계(NONE).
    search();
    var original = replay("DELTA");
    replay("EFFICIENCY");
    assertThat(original.drop()).as("NONE 줄이 있는 단계 " + original.ids()).isTrue();
    // 합성 상·상 귀걸이 1: 고정값 줄이 남아 셋째 줄 후보 값이 되는 단계.
    정리();
    search(
        tooltip ->
            tooltip
                .replace(
                    "무기 공격력 <FONT COLOR='CE43FC'>+1.80%</FONT>",
                    "무기 공격력 <FONT COLOR='FE9600'>+3.00%</FONT>")
                .replace(
                    "공격력 <FONT COLOR='00B5FF'>+0.40%</FONT>",
                    "공격력 <FONT COLOR='FE9600'>+1.55%</FONT>"));
    var keep = replay("DELTA");
    var rebuy = replay("EFFICIENCY");
    // 효율 순은 같은 장신구를 여러 번 바꾼다(앞 단계보다 높은 매물로): 이어 다시 재도 같은 값인지가 이 경우를 지난다.
    var slots = new java.util.HashMap<String, Integer>();
    for (var id : rebuy.ids()) slots.merge(id.substring(0, id.lastIndexOf(':')), 1, Integer::sum);
    assertThat(slots.values()).as("같은 장신구를 다시 바꾼 단계 " + rebuy.ids()).anyMatch(n -> n >= 2);
    assertThat(keep.keep()).as("상·상 귀걸이 단계 " + keep.ids()).isTrue();
  }

  private record Replayed(boolean drop, boolean keep, List<String> ids) {}

  /** 추천 순서(DELTA, 30단계)를 받아 단계마다 changes를 시뮬레이터에 다시 넣어 본다. */
  private Replayed replay(String sort) throws Exception {
    var response =
        get(
            "/api/v1/characters/spec-up/path?characterName="
                + encode(NAME)
                + "&sort="
                + sort
                + "&limit=30");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    SiteNames.assertAbsent(response.body());
    // 연마 스위치 켬 + 딜러 + 점수 입력: 답이 연마 단계를 봤다(프론트 11-21). 카르마 스위치는 꺼져 있다.
    var answer = mapper.readTree(response.body()).path("data");
    assertThat(answer.path("polishStepsConsidered").isBoolean()).as(sort).isTrue();
    assertThat(answer.path("polishStepsConsidered").asBoolean()).as(sort).isTrue();
    assertThat(answer.path("karmaStepsConsidered").isBoolean()).as(sort).isTrue();
    assertThat(answer.path("karmaStepsConsidered").asBoolean()).as(sort).isFalse();
    var steps = mapper.readTree(response.body()).at("/data/steps");
    var all = new ArrayList<JsonNode>();
    // 칸(장신구)마다 앞 단계들이 바꾼 줄.
    var touched = new java.util.HashMap<String, java.util.Set<Integer>>();
    boolean keep = false;
    boolean drop = false;
    var ids = new ArrayList<String>();
    for (var step : steps) {
      var id = step.path("candidateId").asText();
      ids.add(id);
      var changes = new ArrayList<JsonNode>();
      step.path("changes").forEach(changes::add);
      assertThat(changes).as(id).isNotEmpty();
      if (!"POLISH".equals(step.path("kind").asText())) {
        // EFFICIENCY는 비용을 모르는 단계도 비용 있는 단계 뒤에 잇는다(PM 총괄 11-19 (B)). 연마가 아닌 단계는 비용 모름이어야 하고, 이어 넣은
        // 값만
        // 확인한다.
        assertThat("EFFICIENCY").as(id).isEqualTo(sort);
        assertThat(step.path("extraCost").isNull()).as(id + " 비용 모름 단계").isTrue();
        all.addAll(changes);
        var simulated = simulate(mapper.writeValueAsString(all));
        assertThat(simulated.statusCode()).as(simulated.body()).isEqualTo(200);
        assertThat(mapper.readTree(simulated.body()).at("/data/after").decimalValue())
            .as(id)
            .isEqualByComparingTo(step.path("combatPowerAfter").decimalValue());
        continue;
      }
      // change는 changes의 첫째(이름 있는 옵션이 먼저).
      assertThat(step.path("change")).as(id).isEqualTo(changes.getFirst());
      for (var change : changes) {
        assertThat(change.path("kind").asText()).isEqualTo("POLISH");
        assertThat(change.path("line").isInt()).as(id).isTrue();
        if ("NONE".equals(change.path("option").asText())) {
          drop = true;
          assertThat(change.path("grade").isNull()).as(id).isTrue();
        } else assertThat(change.path("grade").asText()).as(id).isIn("LOW", "MID", "HIGH");
      }
      if (id.startsWith("POLISH:7:HH")) keep = true;
      // changes의 꼴(PM 총괄 11-15 고정): 처음 장신구와 다른 줄 + 앞 단계가 바꾼 줄의 되돌림. 줄은 한 번씩, 앞 단계가 바꾼 줄은 모두 들어 있고,
      // 처음 값과
      // 같은 변경(되돌림)은 앞 단계가 바꾼 줄에만 있다. 변경 하나만 처음 장신구에 넣어 from·to가 같으면 되돌림이다.
      var slot = id.substring(0, id.lastIndexOf(':'));
      var before = touched.getOrDefault(slot, java.util.Set.of());
      var lines = new java.util.HashSet<Integer>();
      for (var change : changes) {
        assertThat(lines.add(change.path("line").asInt())).as(id + " 줄 중복").isTrue();
        var alone = simulate(mapper.writeValueAsString(List.of(change)));
        var contribution = mapper.readTree(alone.body()).at("/data/contributions/0");
        if (contribution.path("from").asText().equals(contribution.path("to").asText()))
          assertThat(before).as(id + " 되돌림 줄 " + change).contains(change.path("line").asInt());
      }
      assertThat(lines).as(id + " 앞 단계가 바꾼 줄").containsAll(before);
      touched.computeIfAbsent(slot, key -> new java.util.HashSet<>()).addAll(lines);
      // 웹 '시뮬레이터에서 열기'와 같이 지금까지 모든 단계의 changes를 차례로 이어 다시 넣는다(같은 줄은 뒤의 변경이 이김).
      all.addAll(changes);
      var simulated = simulate(mapper.writeValueAsString(all));
      assertThat(simulated.statusCode()).as(simulated.body()).isEqualTo(200);
      var data = mapper.readTree(simulated.body()).path("data");
      assertThat(data.path("partial").asBoolean()).as(id).isFalse();
      assertThat(data.path("after").decimalValue())
          .as(id)
          .isEqualByComparingTo(step.path("combatPowerAfter").decimalValue());
    }
    return new Replayed(drop, keep, ids);
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
