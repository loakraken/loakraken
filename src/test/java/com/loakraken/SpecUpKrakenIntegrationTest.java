package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketAuctionService.GemCell;
import com.loakraken.market.application.RelicBookScan;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스펙업 KRAKEN 정렬: 기준 캐릭터 원문(l01, 레퍼런스 결과 표와 같은 시각)으로 Δ크라켄 점수·10만 골드당 정렬·작열 제외·각인 다단계 줄을 실제 앱 경로로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spec-up.enabled=true", "kraken-score.enabled=true"})
class SpecUpKrakenIntegrationTest {
  private static final String SERVER = "크라켄서버";
  private static final String NAME = "스펙크라켄";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired com.loakraken.market.application.MarketCache marketCache;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v*크라켄*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    redis.delete("market:v1:relic-books");
  }

  @Test
  void KRAKEN은_10만_골드당_Δ크라켄_점수_순이고_작열을_빼며_레퍼런스_점수_차와_같다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells());
    marketCache.saveRelicBooks(books(), Duration.ofMinutes(5));
    search();
    var data = guide("&sort=KRAKEN&limit=50");
    assertThat(data.path("sort").asText()).isEqualTo("KRAKEN");
    assertThat(data.path("krakenScore").decimalValue()).isEqualByComparingTo("3977.02");
    assertThat(data.path("krakenRuleVersion").asText()).isEqualTo("spec-up-kraken-v1");
    assertThat(data.path("cooldownGemsIncluded").asBoolean()).isFalse();
    assertThat(data.path("krakenSortUnavailableReason").isNull()).isTrue();
    var candidates = data.path("candidates");
    for (var candidate : candidates)
      assertThat(candidate.path("gemType").asText())
          .as(candidate.path("id").asText())
          .isNotEqualTo("COOLDOWN");
    // 정렬 키는 화면에 보이는 10만 골드당 값 그대로 내림차순.
    BigDecimal previous = null;
    for (var candidate : candidates) {
      var per100k = candidate.path("krakenScorePer100kGold");
      if (per100k.isNull()) break;
      if (previous != null) assertThat(per100k.decimalValue()).isLessThanOrEqualTo(previous);
      previous = per100k.decimalValue();
    }
    assertThat(previous).as("10만 골드당 값이 있는 후보").isNotNull();
    // 레퍼런스 ▲(같은 시각): 블레이드 버스트 6→7 98.81, 6→10 402.75. 10만 골드당 = ▲ ÷ 비용 × 10⁵.
    var burst7 = byTarget(candidates, "블레이드 버스트", "7레벨");
    assertThat(burst7.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("98.81");
    long cost = burst7.at("/cost/amount").asLong();
    // 10만 골드당은 반올림 전 Δ ÷ 비용 × 10⁵(B와 같은 순서)라 반올림한 ▲로 나눈 값과 0.01 안.
    assertThat(burst7.path("krakenScorePer100kGold").decimalValue())
        .isCloseTo(
            new BigDecimal("98.81")
                .multiply(BigDecimal.valueOf(100_000))
                .divide(BigDecimal.valueOf(cost), 2, java.math.RoundingMode.HALF_UP),
            org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
    assertThat(byTarget(candidates, "블레이드 버스트", "10레벨").path("deltaKrakenScore").decimalValue())
        .isEqualByComparingTo("402.75");
    // 각인 다단계 줄: 기습의 대가 X0 → X1·X4, 비용은 5권 × 올리는 레벨 수.
    var surprise1 = find(candidates, "ENGRAVING_LEVEL:기습의 대가:1");
    var surprise4 = find(candidates, "ENGRAVING_LEVEL:기습의 대가:4");
    assertThat(surprise1.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("25.71");
    assertThat(surprise4.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("102.85");
    assertThat(surprise1.at("/cost/amount").asLong())
        .as(surprise1.toString())
        .isEqualTo(5 * 48_000L);
    assertThat(surprise4.at("/cost/amount").asLong()).isEqualTo(4 * 5 * 48_000L);
  }

  @Test
  void 연마_스톤_카르마_완갑은_Δ크라켄_점수만_있는_줄로_나온다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells());
    search();
    var candidates = guide("&sort=KRAKEN&limit=200").path("candidates");
    // 레퍼런스 ▲(같은 시각): 귀걸이 1 무공%[중] 공%[하] → 공%[중] 19.38, 스톤 → 아드레날린 Lv.2 원한 Lv.3 0.91, 카르마 29 → 30
    // 1.84,
    // 완갑 14 → 15 37.02.
    var polish = find(candidates, "POLISH:7:MM");
    assertThat(polish.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("19.38");
    assertThat(polish.path("from").asText())
        .isEqualTo("무기 공격력 +1.80%(중) · 공격력 +80(하) · 공격력 +0.40%(하)");
    assertThat(polish.path("to").asText()).isEqualTo("무기 공격력 +1.80%(중) · 공격력 +0.95%(중)");
    assertThat(polish.path("change").isNull()).isTrue();
    assertThat(polish.path("status").asText()).isEqualTo("UNSUPPORTED");
    // 크라켄 점수로만 비교하는 종류: 오류가 아니라 전투력을 계산하지 않는 종류라는 이유.
    assertThat(polish.path("reasonCode").asText()).isEqualTo("SCORE_ONLY");
    assertThat(polish.at("/cost/code").asText()).isEqualTo("POLISH_COST_UNCONFIRMED");
    assertThat(polish.path("krakenScorePer100kGold").isNull()).isTrue();
    var stone = find(candidates, "ABILITY_STONE:2:3");
    assertThat(stone.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("0.91");
    assertThat(stone.path("to").asText()).isEqualTo("97돌 아드레날린 Lv.2 · 원한 Lv.3");
    // B1 비용(비교 기준 B 원본 식): 스톤 = 1 ÷ 목표 조합 확률 키트 × 50골드, 10만 골드당은 B 화면 값과 같다(같은 시각 레퍼런스 표).
    assertThat(stone.at("/cost/code").asText()).isEqualTo("STONE_EXPECTED_KITS");
    assertThat(stone.at("/cost/amount").asLong()).isEqualTo(36_765L); // 735.29개 × 50
    var stones =
        Map.of(
            "ABILITY_STONE:2:3", "2.47",
            "ABILITY_STONE:1:4", "2.47",
            "ABILITY_STONE:2:4", "1.94",
            "ABILITY_STONE:3:3", "0.32",
            "ABILITY_STONE:3:4", "0.02",
            "ABILITY_STONE:4:4", "0.00");
    stones.forEach(
        (id, per100k) ->
            assertThat(find(candidates, id).path("krakenScorePer100kGold").decimalValue())
                .as(id)
                .isEqualByComparingTo(per100k));
    assertThat(find(candidates, "ABILITY_STONE:4:4").at("/cost/amount").asLong())
        .isEqualTo(31_446_540_881L); // 6.29억개 × 50
    var karma = find(candidates, "KARMA_LEVEL:30");
    assertThat(karma.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("1.84");
    // 카르마 29 → 30: 기대 시도 × 900 = 41.32만, 10만 골드당 0.44(B 화면 값).
    assertThat(karma.at("/cost/code").asText()).isEqualTo("KARMA_EXPECTED");
    assertThat(karma.at("/cost/amount").asLong()).isEqualTo(413_228L);
    assertThat(karma.path("krakenScorePer100kGold").decimalValue()).isEqualByComparingTo("0.44");
    var armGuard = find(candidates, "EQUIPMENT_REFINE:16:15");
    assertThat(armGuard.path("kind").asText()).isEqualTo("EQUIPMENT_REFINE");
    assertThat(armGuard.path("deltaKrakenScore").decimalValue()).isEqualByComparingTo("37.02");
    assertThat(armGuard.at("/change/kind").asText()).isEqualTo("ARM_GUARD");
    assertThat(armGuard.at("/change/level").asInt()).isEqualTo(15);
    assertThat(armGuard.at("/change/grade").asText()).isEqualTo("전설");
    assertThat(armGuard.at("/cost/code").asText()).isEqualTo("ARM_GUARD_COST_UNCONFIRMED");
    // 여러 단계 줄(계산 검증 10-25: B는 지금 단계에서 목표 +25까지 줄을 낸다): +14 전설 → +16~20 유물, +21~25 고대.
    for (int level = 15; level <= 25; level++) {
      var row = find(candidates, "EQUIPMENT_REFINE:16:" + level);
      assertThat(row.path("from").asText()).as("+" + level).isEqualTo("+14");
      assertThat(row.at("/change/level").asInt()).isEqualTo(level);
      assertThat(row.at("/change/grade").asText())
          .as("+" + level)
          .isEqualTo(level <= 15 ? "전설" : level <= 20 ? "유물" : "고대");
      assertThat(row.path("deltaKrakenScore").isNull()).as("+" + level).isFalse();
    }
    // 한 줄 알림은 계산 줄로 바뀐 종류만 빠진다.
    for (var candidate : candidates)
      assertThat(candidate.path("id").asText()).isNotIn("POLISH", "KARMA_LEVEL", "ABILITY_STONE");
    // 비용 모름 묶음(② Δ > 0)은 10만 골드당 묶음(①) 뒤, Δ 내림차순.
    int lastPriced = -1;
    int firstUnpriced = Integer.MAX_VALUE;
    for (int i = 0; i < candidates.size(); i++) {
      if (!candidates.get(i).path("krakenScorePer100kGold").isNull()) lastPriced = i;
      else if (firstUnpriced == Integer.MAX_VALUE) firstUnpriced = i;
    }
    assertThat(lastPriced).isLessThan(firstUnpriced);
    // 줄 상세: 완갑은 재련 계획 밖이라 단계만, 연마는 내역 없이 후보만.
    var detail =
        get(
            "/api/v1/characters/spec-up/candidates/EQUIPMENT_REFINE:16:15?characterName="
                + encode(NAME));
    assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
    var data = mapper.readTree(detail.body()).path("data");
    assertThat(data.at("/refine/fromStage").asInt()).isEqualTo(14);
    assertThat(data.at("/refine/toStage").asInt()).isEqualTo(15);
    assertThat(data.path("materials").isNull()).isTrue();
    var polishDetail =
        get("/api/v1/characters/spec-up/candidates/POLISH:7:MM?characterName=" + encode(NAME));
    assertThat(polishDetail.statusCode()).as(polishDetail.body()).isEqualTo(200);
  }

  @Test
  void 작열_포함은_정렬과_따로_고르고_다른_정렬은_기본으로_넣는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(gemCells());
    search();
    var included = guide("&sort=KRAKEN&includeCooldownGems=true&limit=50");
    assertThat(included.path("cooldownGemsIncluded").asBoolean()).isTrue();
    assertThat(hasCooldown(included.path("candidates"))).isTrue();
    var efficiency = guide("&limit=50");
    assertThat(efficiency.path("sort").asText()).isEqualTo("EFFICIENCY");
    assertThat(efficiency.path("cooldownGemsIncluded").asBoolean()).isTrue();
    assertThat(hasCooldown(efficiency.path("candidates"))).isTrue();
    // 상세도 목록과 같은 포함 옵션: 빼면 작열 후보는 404, 넣거나 주지 않으면 200.
    String cooldownId = null;
    for (var candidate : included.path("candidates"))
      if ("COOLDOWN".equals(candidate.path("gemType").asText(""))) {
        cooldownId = candidate.path("id").asText();
        break;
      }
    var detailPath =
        "/api/v1/characters/spec-up/candidates/" + cooldownId + "?characterName=" + encode(NAME);
    assertThat(get(detailPath + "&includeCooldownGems=false").statusCode()).isEqualTo(404);
    assertThat(get(detailPath + "&includeCooldownGems=true").statusCode()).isEqualTo(200);
    assertThat(get(detailPath).statusCode()).isEqualTo(200);
    // 효율 순에도 Δ크라켄 점수는 같이 온다(같은 후보면 같은 값).
    assertThat(
            byTarget(efficiency.path("candidates"), "블레이드 버스트", "7레벨")
                .path("deltaKrakenScore")
                .decimalValue())
        .isEqualByComparingTo("98.81");
  }

  @Test
  void KRAKEN_목록_응답_시간을_잰다() throws Exception {
    // PM-프론트 조건 5: 후보마다 엔진 1회. 결과는 PR에 적는다(로그 줄 '[measure]'). CI 흔들림 때문에 단언은 넉넉한 상한만.
    when(auctions.cachedGems()).thenReturn(gemCells());
    marketCache.saveRelicBooks(books(), Duration.ofMinutes(5));
    search();
    guide("&sort=KRAKEN&limit=200"); // 데우기
    var millis = new ArrayList<Long>();
    for (int i = 0; i < 30; i++) {
      long start = System.nanoTime();
      var response =
          get(
              "/api/v1/characters/spec-up?characterName="
                  + encode(NAME)
                  + "&sort=KRAKEN&limit=200");
      millis.add((System.nanoTime() - start) / 1_000_000);
      assertThat(response.statusCode()).isEqualTo(200);
    }
    millis.sort(Long::compare);
    long p50 = millis.get(14);
    long p95 = millis.get(28);
    System.out.println(
        "[measure] spec-up KRAKEN 30회 p50="
            + p50
            + "ms p95="
            + p95
            + "ms max="
            + millis.getLast()
            + "ms");
    assertThat(p95).isLessThan(2_000);
  }

  private static boolean hasCooldown(JsonNode candidates) {
    for (var candidate : candidates)
      if ("COOLDOWN".equals(candidate.path("gemType").asText(""))) return true;
    return false;
  }

  private static JsonNode byTarget(JsonNode candidates, String skill, String to) {
    for (var candidate : candidates)
      if ("GEM_LEVEL".equals(candidate.path("kind").asText())
          && skill.equals(candidate.path("skillName").asText())
          && "DAMAGE".equals(candidate.path("gemType").asText())
          && to.equals(candidate.path("to").asText())) return candidate;
    throw new AssertionError(skill + " " + to + " 후보가 없습니다");
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
            List.of());
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    assertThat(get("/api/v1/characters/search?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(200);
  }

  /** 보석 시세: 레벨별 즉시 구매 최저가(겁화·작열 같은 값). */
  private static List<GemCell> gemCells() {
    var at = Instant.parse("2026-10-17T09:00:00Z");
    var prices =
        Map.of(
            1,
            2_000L,
            2,
            4_000L,
            3,
            8_000L,
            4,
            15_000L,
            5,
            25_000L,
            6,
            40_000L,
            7,
            150_000L,
            8,
            360_000L,
            9,
            950_000L,
            10,
            2_630_000L);
    var cells = new ArrayList<GemCell>();
    for (var kind : List.of("겁화", "작열"))
      for (int level = 10; level >= 1; level--)
        cells.add(
            new GemCell(
                kind,
                level,
                level + "레벨 " + kind + "의 보석",
                new AuctionCell(prices.get(level), 5, at),
                null,
                MarketAuctionService.Status.READY));
    return cells;
  }

  private static RelicBookScan books() {
    var names = Map.of("기습의 대가", 48_000, "돌격대장", 113_900, "원한", 114_400, "아드레날린", 102_000);
    var prices = new ArrayList<MarketPrice>();
    long id = 1;
    for (var entry : names.entrySet())
      prices.add(
          new MarketPrice(
              id++,
              "유물 " + entry.getKey() + " 각인서",
              "유물",
              null,
              1,
              null,
              null,
              null,
              new BigDecimal(entry.getValue())));
    return new RelicBookScan(Instant.now(), 1, prices);
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
