package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.refining.domain.AdvancedRefineCost;
import com.loakraken.refining.domain.AdvancedRefineRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상급 재련 기대 비용 API(계약 초안 2026-11-22, 프론트2 검토 OK): 구간마다 기대 턴·숨결·책 선택·재료·골드, 합계는 올리기 전 구간 값의 합을 한 번 올린
 * 값(스펙업 ADVANCED_REFINE_EXPECTED와 같은 모델·같은 값). 시세가 없으면 409, 전율은 400 REFINING_ADVANCED_UNCONFIRMED,
 * 스위치 기본 꺼짐.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "refining.advanced.enabled=true")
class RefiningAdvancedPlanIntegrationTest {
  private static final Instant OBSERVED = Instant.parse("2026-10-12T02:00:00Z");
  private static final Set<String> CHOICES = Set.of("NONE", "BREATH", "BOOK", "BOTH");

  @LocalServerPort int port;
  @Autowired ConfigurableEnvironment environment;
  @Autowired com.loakraken.market.application.MarketCache marketCache;
  @Autowired StringRedisTemplate redis;
  @Autowired AdvancedRefineRules advancedRules;
  private final JsonMapper mapper = new JsonMapper();

  /** 스펙업 상급 재련 시험과 같은 시세표(책 값 없음 → 책을 쓰는 조합은 빼고 고름). */
  private static final Map<String, BigDecimal> UNITS =
      Map.of(
          "운명의 수호석", new BigDecimal("0.31"),
          "운명의 돌파석", new BigDecimal("3"),
          "아비도스 융화 재료", new BigDecimal("160"),
          "운명의 파편", new BigDecimal("0.08"),
          "빙하의 숨결", new BigDecimal("180"),
          "운명의 파괴석", new BigDecimal("0.45"),
          "용암의 숨결", new BigDecimal("210"));

  @BeforeEach
  @AfterEach
  void 시세_정리() {
    redis.delete("market:v1:refining-materials");
  }

  private void prices() {
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            OBSERVED,
            List.of(
                price(1, "운명의 수호석", 100, "31"),
                price(2, "운명의 돌파석", 1, "3"),
                price(3, "아비도스 융화 재료", 1, "160"),
                price(4, "운명의 파편 주머니(대)", 1, "240"),
                price(5, "빙하의 숨결", 1, "180"),
                price(6, "운명의 파괴석", 100, "45"),
                price(7, "용암의 숨결", 1, "210"))),
        java.time.Duration.ofMinutes(5));
  }

  @Test
  void 시세가_없으면_409이고_있으면_구간마다_기대_턴과_비용을_스펙업과_같은_값으로_준다() throws Exception {
    var body = "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":0,\"toStage\":20}";
    var pending = request(body);
    assertThat(pending.statusCode()).as(pending.body()).isEqualTo(409);
    assertThat(mapper.readTree(pending.body()).path("code").asString())
        .isEqualTo("REFINING_PRICE_PENDING");
    assertThat(pending.headers().firstValue("Retry-After")).contains("60");

    prices();
    var response = request(body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("refining-advanced-plan.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    var model = AdvancedRefineCost.expected(advancedRules, "ARMOR", 0, 20, UNITS::get);
    // 합계 = 올리기 전 구간 값의 합을 한 번 올린 값(스펙업 상급 재련 단계 비용과 같은 계산).
    assertThat(data.path("expectedCostGold").asLong())
        .isEqualTo(model.expectedGold().setScale(0, RoundingMode.CEILING).longValueExact());
    assertThat(data.path("ruleVersion").asString()).isEqualTo(advancedRules.ruleVersion());
    assertThat(data.path("grade").asString()).isEqualTo("UPHWA");
    assertThat(data.path("equipmentType").asString()).isEqualTo("ARMOR");
    assertThat(data.path("pricesObservedAt").asString()).isEqualTo("2026-10-12T02:00:00Z");
    assertThat(data.path("priceBasis").asString()).isEqualTo("CURRENT_MIN_PRICE");
    assertThat(data.path("policyLimited").asBoolean()).isTrue();
    assertThat(data.path("temperingStages").toString()).isEqualTo("[10]");
    assertThat(data.path("expectedMaterials").size()).isPositive();
    assertThat(data.path("source").path("officialGuide").asString()).isNotBlank();

    var bands = data.path("bands");
    assertThat(bands.size()).isEqualTo(2);
    for (int i = 0; i < 2; i++) {
      var band = bands.get(i);
      var expected = model.bands().get(i);
      assertThat(band.path("bandStart").asInt()).isEqualTo(expected.bandStart());
      assertThat(band.path("fromStage").asInt()).isEqualTo(expected.fromStage());
      assertThat(band.path("toStage").asInt()).isEqualTo(expected.toStage());
      assertThat(band.path("growthSupport").asBoolean()).isTrue();
      assertThat(band.at("/expectedTurns/total").decimalValue())
          .isEqualByComparingTo(
              BigDecimal.valueOf(expected.turns().total()).setScale(2, RoundingMode.HALF_UP));
      // 20단계 아래 구간에는 강화 선조 턴이 없다.
      assertThat(band.at("/expectedTurns/enhanced").isNull()).isTrue();
      assertThat(band.at("/policy/enhanced").isNull()).isTrue();
      assertThat(CHOICES).contains(band.at("/policy/normal").asString());
      assertThat(CHOICES).contains(band.at("/policy/ancestor").asString());
      assertThat(band.path("expectedCostGold").asLong())
          .isEqualTo(expected.expectedGold().setScale(0, RoundingMode.CEILING).longValueExact());
      assertThat(band.path("expectedRefineGold").decimalValue())
          .isEqualByComparingTo(expected.gold().setScale(2, RoundingMode.HALF_UP));
      assertThat(band.path("expectedMaterials").size()).isPositive();
    }
    assertThat(data.path("assumptions").toString()).contains("즉시 구매 최저가");
    SiteNames.assertAbsent(response.body());
  }

  @Test
  void 구간_안의_단계에서_시작하면_그_단계부터_풀고_20단계부터는_강화_선조를_준다() throws Exception {
    prices();
    var midResponse =
        request(
            "{\"grade\":\"UPHWA\",\"equipmentType\":\"WEAPON\",\"fromStage\":13,\"toStage\":20}");
    assertThat(midResponse.statusCode()).as(midResponse.body()).isEqualTo(200);
    var mid = mapper.readTree(midResponse.body()).path("data");
    assertThat(mid.path("bands").size()).isEqualTo(1);
    assertThat(mid.at("/bands/0/bandStart").asInt()).isEqualTo(10);
    assertThat(mid.at("/bands/0/fromStage").asInt()).isEqualTo(13);
    assertThat(mid.path("temperingStages").size()).isZero();
    var model = AdvancedRefineCost.expected(advancedRules, "WEAPON", 13, 20, UNITS::get);
    assertThat(mid.path("expectedCostGold").asLong())
        .isEqualTo(model.expectedGold().setScale(0, RoundingMode.CEILING).longValueExact());

    var high =
        request(
            "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21}");
    assertThat(high.statusCode()).as(high.body()).isEqualTo(200);
    var band = mapper.readTree(high.body()).at("/data/bands/0");
    assertThat(band.at("/expectedTurns/enhanced").isNumber()).isTrue();
    assertThat(CHOICES).contains(band.at("/policy/enhanced").asString());
    assertThat(band.path("growthSupport").asBoolean()).isFalse();
    assertThat(mapper.readTree(high.body()).at("/data/temperingStages").toString())
        .isEqualTo("[20]");
  }

  @Test
  void 전율은_400_REFINING_ADVANCED_UNCONFIRMED다() throws Exception {
    prices();
    var response =
        request(
            "{\"grade\":\"JEONYUL\",\"equipmentType\":\"ARMOR\",\"fromStage\":0,\"toStage\":10}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asString())
        .isEqualTo("REFINING_ADVANCED_UNCONFIRMED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARM_GUARD\",\"fromStage\":0,\"toStage\":10}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":10}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":0,\"toStage\":41}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":-1,\"toStage\":10}",
        "{\"equipmentType\":\"ARMOR\",\"fromStage\":0,\"toStage\":10}",
        "{\"grade\":\"UPHWA\",\"fromStage\":0,\"toStage\":10}"
      })
  void 잘못된_입력은_400_INVALID_REQUEST다(String body) throws Exception {
    prices();
    var response = request(body);
    assertThat(response.statusCode()).as(body + " " + response.body()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asString())
        .isEqualTo("INVALID_REQUEST");
  }

  @Test
  void 스위치가_꺼져_있으면_400이다() throws Exception {
    prices();
    var body = "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":0,\"toStage\":10}";
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource("advanced-off", Map.of("refining.advanced.enabled", "false")));
    try {
      var off = request(body);
      assertThat(off.statusCode()).as(off.body()).isEqualTo(400);
      assertThat(mapper.readTree(off.body()).path("code").asString()).isEqualTo("INVALID_REQUEST");
    } finally {
      environment.getPropertySources().remove("advanced-off");
    }
    assertThat(request(body).statusCode()).isEqualTo(200);
  }

  /** 설정을 주지 않으면 상급 재련 스위치는 꺼짐이다(운영 기본값). */
  @Test
  void 스위치_기본값은_꺼짐이다() throws Exception {
    var yaml =
        new String(
            getClass().getResourceAsStream("/application.yaml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yaml).contains("${REFINING_ADVANCED_ENABLED:false}");
  }

  private static com.loakraken.market.domain.MarketPrice price(
      long id, String name, int bundle, String currentMin) {
    return new com.loakraken.market.domain.MarketPrice(
        id, name, "영웅", null, bundle, null, null, null, new BigDecimal(currentMin));
  }

  private HttpResponse<String> request(String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/refining/advanced-plans"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
