package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CombatPowerSimulationService;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketBoardScan;
import com.loakraken.market.application.MarketCache;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
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
 * 젬 교체 순위(M49 2단계, 계약 초안 'gem-ranking' 2026-11-22). 기준 원문 l01 딜러(아크 그리드 코어 6 × 젬 4), l04 서포터. 젬 시세는
 * 보드 캐시(아크 그리드 탭 스캔)에 넣고, 공식 호출 없음.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"combat-power-sim.enabled=true", "combat-power-sim.gem-ranking.enabled=true"})
class GemRankingIntegrationTest {
  private static final String SERVER = "젬순위서버";
  private static final String DEALER = "젬순위딜러";
  // 보드 스캔 캐시는 TTL(3분) 안의 관측만 읽는다: 지금 시각으로 넣는다.
  private final Instant OBSERVED = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired MarketCache marketCache;
  @Autowired CombatPowerSimulationService simulator;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("*젬순위*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    redis.delete("market:v1:board-scan:ARK_GRID:all");
  }

  @Test
  void 딜러는_순위_줄을_주고_가격이_없으면_비용은_비고_초기화_줄은_빠진다() throws Exception {
    search("l01", "블레이드", "4052.77");
    long started = System.nanoTime();
    var response = rank("{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"limit\":200}");
    long ms = (System.nanoTime() - started) / 1_000_000;
    System.out.printf("GEMRANK l01 HERO one request %d ms%n", ms);
    var data = data(response);
    ApiContractFixture.write("gem-ranking.json", response.body());
    assertThat(data.path("status").asString()).isEqualTo("OK");
    assertThat(data.path("reasonCode").isNull()).isTrue();
    var rows = data.path("rows");
    assertThat(rows.size()).isPositive().isLessThanOrEqualTo(200);
    assertThat(data.path("totalRows").asInt()).isGreaterThanOrEqualTo(rows.size());
    int rank = 0;
    for (var row : rows) {
      assertThat(row.path("rank").asInt()).isEqualTo(++rank);
      assertThat(row.path("reset").asBoolean()).as("초기화권 가격 없음").isFalse();
      double p = row.path("successProbability").asDouble();
      assertThat(p).isGreaterThan(0).isLessThanOrEqualTo(1);
      assertThat(row.path("expectedGems").asDouble()).isCloseTo(1 / p, within(0.01));
      assertThat(row.path("expectedGainPct").asDouble()).isGreaterThan(0);
      assertThat(row.path("targetTotal").asInt()).isBetween(5, 20);
      assertThat(row.path("expectedCost").isNull()).isTrue();
      assertThat(row.path("goldPerPercent").isNull()).isTrue();
      assertThat(row.path("missingPrices").toString()).contains("GEM").contains("PHEON");
      assertThat(row.path("grade").asString()).isEqualTo("HERO");
    }
    // 견본의 null(비용·1% 골드·이유)이 생성 문서에서 null 허용인가(프론트2 11-22).
    OpenApiNulls.assertNullsAllowed(OpenApiNulls.document(port), "GemRankingResponse", data);
    // 비용이 없으면 Δ% × 확률 내림차순.
    for (int i = 1; i < rows.size(); i++)
      assertThat(value(rows.get(i - 1))).isGreaterThanOrEqualTo(value(rows.get(i)) - 1e-9);
    SiteNames.assertAbsent(response.body());
  }

  @Test
  void 가격이_있으면_비용_구성의_합이_total이고_1퍼센트_골드_오름차순이다() throws Exception {
    search("l01", "블레이드", "4052.77");
    seedGemPrices();
    var response =
        rank(
            "{\"characterName\":\""
                + DEALER
                + "\",\"grade\":\"HERO\",\"prices\":{\"resetTicketGold\":59110,\"pheonGold\":72.5},\"limit\":200}");
    var data = data(response);
    assertThat(Instant.parse(data.path("pricesObservedAt").asString())).isEqualTo(OBSERVED);
    var rows = data.path("rows");
    assertThat(rows.size()).isPositive();
    boolean sawReset = false;
    for (var row : rows) {
      var cost = row.path("expectedCost");
      assertThat(cost.isObject()).as(row.toString()).isTrue();
      long total =
          cost.path("gem").asLong()
              + cost.path("pheon").asLong()
              + cost.path("processingGold").asLong()
              + cost.path("resetTicket").asLong();
      assertThat(cost.path("total").asLong()).isEqualTo(total);
      double gems = row.path("expectedGems").asDouble();
      double unit = "침식".equals(row.path("gemType").asString()) ? 21883 : 20000;
      assertThat(cost.path("gem").asDouble()).isCloseTo(unit * gems, within(unit * 0.01 + 1));
      assertThat(cost.path("pheon").asDouble())
          .isCloseTo(12 * 72.5 * gems, within(12 * 72.5 * 0.01 + 1));
      if (row.path("reset").asBoolean()) sawReset = true;
      else assertThat(cost.path("resetTicket").asLong()).isZero();
      // goldPerPercent = total / expectedGainPct. 응답의 Δ%는 소수 셋째 자리로 반올림돼 있어(±0.0005) 그만큼의 상대 오차 안.
      double gainPct = row.path("expectedGainPct").asDouble();
      double perPercent = cost.path("total").asDouble() / gainPct;
      assertThat(row.path("goldPerPercent").asDouble())
          .isCloseTo(perPercent, within(perPercent * 0.0005 / gainPct * 1.01 + 1));
      assertThat(row.path("missingPrices").size()).isZero();
    }
    assertThat(sawReset).isTrue();
    for (int i = 1; i < rows.size(); i++)
      assertThat(rows.get(i - 1).path("goldPerPercent").asDouble())
          .isLessThanOrEqualTo(rows.get(i).path("goldPerPercent").asDouble());
  }

  @Test
  void 교체_값_표는_시뮬레이터의_젬_교체와_같은_Δ퍼센트다() throws Exception {
    search("l01", "블레이드", "4052.77");
    var result = simulator.snapshot(DEALER);
    // G1 기준 사례(#1079): 혼돈의 해 코어(Index 5) 젬 1을 침식 5P · 추가 피해 3 · 공격력 2로 → +0.4091%.
    var evaluator = simulator.gemSwapEvaluator(result, 5, 1, "침식");
    assertThat(evaluator).isNotNull();
    assertThat(evaluator.reason()).isNull();
    var simulated =
        data(
            simulate(
                "[{\"kind\":\"ARK_GRID_GEM\",\"slotIndex\":5,\"index\":1,\"point\":5,\"gemType\":\"침식\",\"willEfficiency\":4,\"effects\":[{\"name\":\"추가 피해\",\"level\":3},{\"name\":\"공격력\",\"level\":2}]}]"));
    var row = simulated.path("contributions").get(0);
    assertThat(row.path("status").asString()).as(simulated.toString()).isEqualTo("OK");
    double expected = (evaluator.ratio(5, "추가 피해", 3, "공격력", 2) - 1) * 100;
    assertThat(expected).isCloseTo(0.4091, within(0.0005));
    assertThat(simulated.path("deltaPercent").asDouble()).isCloseTo(expected, within(0.001));
    // 남는 의지력 4(공급 17 − 다른 활성 젬 13): 침식(기본 8)은 효율 4부터 맞는다.
    assertThat(evaluator.willFits(4)).isTrue();
    assertThat(evaluator.willFits(3)).isFalse();
  }

  @Autowired io.micrometer.core.instrument.MeterRegistry meters;

  private double cache(String outcome) {
    var counter = meters.find("loakraken.gem_ranking.cache").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * 켜기 전 조건(#1095 코드 리뷰 P2: 캐릭터 하나 216~327ms): 같은 캐릭터 캐시·등급·정책·초기화·가격·시세 관측 시각이면 다시 풀지 않는다. limit만 다르면 같은
   * 결과에서 자른다. 응답은 같다.
   */
  @Test
  void 같은_요청은_결과_캐시를_쓰고_limit만_달라도_같은_결과에서_자른다() throws Exception {
    search("l01", "블레이드", "4052.77");
    var body =
        "{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"prices\":{\"pheonGold\":72.5},";
    double hits = cache("hit");
    double misses = cache("miss");
    var first = rank(body + "\"limit\":200}");
    long started = System.nanoTime();
    var second = rank(body + "\"limit\":200}");
    long ms = (System.nanoTime() - started) / 1_000_000;
    System.out.printf("GEMRANK cached request %d ms%n", ms);
    assertThat(second.body()).isEqualTo(first.body());
    var top = data(rank(body + "\"limit\":3}"));
    assertThat(cache("miss") - misses).isEqualTo(1);
    assertThat(cache("hit") - hits).isEqualTo(2);
    assertThat(top.path("rows").size()).isEqualTo(Math.min(3, data(first).path("rows").size()));
    assertThat(top.path("totalRows").asInt()).isEqualTo(data(first).path("totalRows").asInt());
    // 가격이 바뀌면(페온) 다시 푼다.
    rank("{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"prices\":{\"pheonGold\":80}}");
    assertThat(cache("miss") - misses).isEqualTo(2);
  }

  @Test
  void 서포터는_UNSUPPORTED와_빈_줄이다() throws Exception {
    search("l04", "도화가", "3000.00");
    var data = data(rank("{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\"}"));
    assertThat(data.path("status").asString()).isEqualTo("UNSUPPORTED");
    assertThat(data.path("reasonCode").asString()).isEqualTo("SUPPORTER_MODEL_PENDING");
    assertThat(data.path("rows").size()).isZero();
    OpenApiNulls.assertNullsAllowed(OpenApiNulls.document(port), "GemRankingResponse", data);
  }

  @Test
  void 잘못된_입력은_400이다() throws Exception {
    search("l01", "블레이드", "4052.77");
    for (var body :
        List.of(
            "{\"characterName\":\"" + DEALER + "\",\"grade\":\"UNCOMMON\"}",
            "{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"policies\":[\"A\",\"A\"]}",
            "{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"limit\":0}",
            "{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"limit\":201}",
            "{\"characterName\":\""
                + DEALER
                + "\",\"grade\":\"HERO\",\"prices\":{\"pheonGold\":-1}}",
            "{\"characterName\":\"" + DEALER + "\",\"grade\":\"HERO\",\"reset\":[true,true]}",
            "{\"characterName\":\"" + DEALER + "\"}"))
      assertThat(rank(body).statusCode()).as(body).isEqualTo(400);
  }

  @Test
  void 스위치_기본값은_꺼짐이다() throws Exception {
    var yaml =
        new String(
            getClass().getResourceAsStream("/application.yaml").readAllBytes(),
            StandardCharsets.UTF_8);
    assertThat(yaml).contains("${GEM_RANKING_ENABLED:false}");
  }

  private static double value(JsonNode row) {
    return row.path("expectedGainPct").asDouble() * row.path("successProbability").asDouble();
  }

  /** 아크 그리드 탭 스캔 캐시(영웅 6종: 침식 21,883, 나머지 20,000). */
  private void seedGemPrices() {
    var items = new ArrayList<MarketPrice>();
    long[][] ids = {{67400003L}, {67400103L}, {67400203L}, {67410303L}, {67410403L}, {67410503L}};
    String[] names = {
      "질서의 젬 : 안정", "질서의 젬 : 견고", "질서의 젬 : 불변", "혼돈의 젬 : 침식", "혼돈의 젬 : 왜곡", "혼돈의 젬 : 붕괴"
    };
    for (int i = 0; i < names.length; i++)
      items.add(
          new MarketPrice(
              ids[i][0],
              names[i],
              "영웅",
              null,
              1,
              0,
              null,
              null,
              new BigDecimal(names[i].endsWith("침식") ? "21883" : "20000")));
    marketCache.saveBoardScan(new MarketBoardScan(MarketTab.ARK_GRID, null, OBSERVED, 18, items));
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private void search(String fixture, String className, String combatPower) throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/" + fixture + ".json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    var profile =
        new CharacterProfile(
            DEALER,
            SERVER,
            className,
            70,
            new BigDecimal("1770.00"),
            new BigDecimal(combatPower),
            null,
            null,
            null,
            null,
            stats(raw));
    var old = ArmoryRawFixtures.loadout(raw);
    // 저장한 원문은 아크 그리드 칸 번호가 비어 있어 G1 기준 사례의 아크 그리드(이름 없음)로 바꾼다.
    var loadout =
        new com.loakraken.character.domain.CharacterLoadout(
            old.equipment(),
            old.engravings(),
            old.gems(),
            old.cards(),
            old.arkPassive(),
            gemSwapGrid(),
            old.skills());
    var details = CharacterTestFixture.details(profile, loadout, List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    var response = get("/api/v1/characters/search?characterName=" + encode(DEALER));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  }

  private static List<CharacterProfile.Stat> stats(JsonNode raw) {
    var stats = new ArrayList<CharacterProfile.Stat>();
    for (var stat : raw.path("ArmoryProfile").path("Stats")) {
      var tooltip = new ArrayList<String>();
      for (var line : stat.path("Tooltip")) tooltip.add(line.asString());
      if ("공격력".equals(stat.path("Type").asString()) && tooltip.isEmpty())
        tooltip.add("힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 180000 입니다.");
      stats.add(
          new CharacterProfile.Stat(
              stat.path("Type").asString(), stat.path("Value").asString(), tooltip));
    }
    return stats;
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

  private HttpResponse<String> rank(String body) throws Exception {
    return post("/api/v1/characters/combat-power/gem-ranking", body);
  }

  private HttpResponse<String> simulate(String changes) throws Exception {
    return post(
        "/api/v1/characters/combat-power/simulate",
        "{\"characterName\": \"" + DEALER + "\", \"changes\": " + changes + "}");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * G1 기준 사례와 같은 아크 그리드(CombatPowerSimulationIntegrationTest, 이름 없음): 혼돈의 해 코어 : 현란한 공격(고대, Slots
   * Index 5, 17P, 공급 의지력 17, 젬 필요 의지력 3+4+3+7 = 17)과 공식 옵션 합.
   */
  private static com.loakraken.character.domain.CharacterLoadout.ArkGrid gemSwapGrid() {
    var gems =
        List.of(
            gridGem(0, 4, 3, 8, 5, "혼돈의 젬 : 침식", "추가 피해", 3, "낙인력", 1),
            gridGem(1, 4, 4, 8, 4, "혼돈의 젬 : 침식", "아군 피해 강화", 2, "낙인력", 5),
            gridGem(2, 4, 3, 8, 5, "혼돈의 젬 : 침식", "아군 피해 강화", 3, "낙인력", 2),
            gridGem(3, 5, 7, 9, 2, "혼돈의 젬 : 왜곡", "보스 피해", 4, "공격력", 3));
    var core =
        new com.loakraken.character.domain.CharacterLoadout.GridSlot(
            5,
            null,
            "혼돈의 해 코어 : 현란한 공격",
            17,
            "고대",
            gems,
            new com.loakraken.character.domain.CharacterLoadout.CoreDetail(
                "혼돈", 17, List.of(), List.of()),
            null);
    return new com.loakraken.character.domain.CharacterLoadout.ArkGrid(
        List.of(core),
        List.of(
            new com.loakraken.character.domain.CharacterLoadout.GridEffect("추가 피해", 49, null, null),
            new com.loakraken.character.domain.CharacterLoadout.GridEffect("낙인력", 39, null, null),
            new com.loakraken.character.domain.CharacterLoadout.GridEffect(
                "아군 피해 강화", 24, null, null),
            new com.loakraken.character.domain.CharacterLoadout.GridEffect("공격력", 34, null, null),
            new com.loakraken.character.domain.CharacterLoadout.GridEffect(
                "보스 피해", 4, null, null)));
  }

  private static com.loakraken.character.domain.CharacterLoadout.GridGem gridGem(
      int index,
      int point,
      int will,
      int baseWill,
      int efficiency,
      String name,
      String first,
      int firstLevel,
      String second,
      int secondLevel) {
    return new com.loakraken.character.domain.CharacterLoadout.GridGem(
        index,
        null,
        true,
        "전설",
        new com.loakraken.character.domain.CharacterLoadout.GemDetail(
            name,
            "혼돈",
            point + 11,
            will,
            baseWill,
            efficiency,
            point,
            List.of(
                new com.loakraken.character.domain.CharacterLoadout.GemOption(
                    first, firstLevel, null, null),
                new com.loakraken.character.domain.CharacterLoadout.GemOption(
                    second, secondLevel, null, null))));
  }
}
