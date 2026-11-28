package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 특수 재련 돌파석 1개 가치(M42, 계약 초안 'special-stone-values' 2026-11-22, 프론트 검토 OK). 가치 = 그 단계 일반 재련 기대
 * 비용(회차별 최적) ÷ 평균 필요량(1회 소모량 ÷ 공식 기본 확률). 계산 검증 기준(캡처 시세): 전율 무기 +20 → +21 = 1,471,202 G ÷ 3,000개 =
 * 490.4 G, 일반 재련 1회 57,476 G(숨결 없음) · 68,301 G(숨결 최대 25).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"refining.special-stone.enabled=true", "refining.optimal.enabled=true"})
class SpecialStoneValuesIntegrationTest {
  @LocalServerPort int port;
  @Autowired ConfigurableEnvironment environment;
  @Autowired com.loakraken.market.application.MarketCache marketCache;
  @Autowired StringRedisTemplate redis;
  @Autowired io.micrometer.core.instrument.MeterRegistry registry;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 시세_정리() {
    redis.delete("market:v1:refining-materials");
  }

  /** 캡처의 재료 시세 표(전율 무기에 쓰는 것과 방어구 몇 개). */
  private void capturePrices(boolean withLavaBreath) {
    var items = new ArrayList<com.loakraken.market.domain.MarketPrice>();
    items.add(price(1, "운명의 파괴석 결정", 100, "1036"));
    items.add(price(2, "위대한 운명의 돌파석", 1, "19"));
    items.add(price(3, "상급 아비도스 융화 재료", 1, "221"));
    items.add(price(4, "운명의 파편 주머니(소)", 1, "80"));
    items.add(price(5, "운명의 수호석 결정", 100, "174"));
    items.add(price(6, "빙하의 숨결", 1, "385"));
    if (withLavaBreath) items.add(price(7, "용암의 숨결", 1, "433"));
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            Instant.parse("2026-11-22T07:00:00Z"), List.copyOf(items)),
        java.time.Duration.ofMinutes(5));
  }

  private static String target(String grade, String type, int from) {
    return "{\"grade\":\""
        + grade
        + "\",\"equipmentType\":\""
        + type
        + "\",\"fromStage\":"
        + from
        + "}";
  }

  @Test
  void 전율_무기_20에서_21은_캡처_시세로_1개_490_4골드이고_요청_순서대로_준다() throws Exception {
    capturePrices(true);
    var response =
        request(
            "{\"targets\":["
                + target("JEONYUL", "WEAPON", 20)
                + ","
                + target("UPHWA", "WEAPON", 20)
                + ","
                + target("JEONYUL", "ARM_GUARD", 20)
                + ","
                + target("JEONYUL", "WEAPON", 10)
                + "]}");
    var data = data(response);
    ApiContractFixture.write("special-stone-values.json", response.body());
    assertThat(data.path("priceBasis").asString()).isEqualTo("CURRENT_MIN_PRICE");
    assertThat(data.path("pricesObservedAt").asString()).isEqualTo("2026-11-22T07:00:00Z");
    assertThat(data.at("/perTrySource/singleSource").asBoolean()).isTrue();
    assertThat(data.at("/perTrySource/capturedOn").asString()).isEqualTo("2026-11-22");
    var rows = data.path("rows");
    assertThat(rows.size()).isEqualTo(4);
    var weapon = rows.get(0);
    assertThat(weapon.path("status").asString()).as(weapon.toString()).isEqualTo("OK");
    assertThat(weapon.path("targetStage").asInt()).isEqualTo(21);
    assertThat(weapon.path("perTry").asInt()).isEqualTo(45);
    assertThat(weapon.path("baseRateBp").asInt()).isEqualTo(150);
    assertThat(weapon.path("averageNeeded").doubleValue()).isCloseTo(3000, within(1e-9));
    assertThat(weapon.path("expectedNormalCostGold").doubleValue())
        .isCloseTo(1_471_202, within(1.0));
    assertThat(weapon.path("valuePerStoneGold").decimalValue()).isEqualByComparingTo("490.4");
    assertThat(weapon.path("expectedNormalAttempts").doubleValue()).isCloseTo(21.57, within(0.01));
    assertThat(weapon.path("stone").isNull()).isTrue();
    var normal = weapon.path("normalTry");
    assertThat(normal.path("costWithoutBreathGold").asLong()).isEqualTo(57_476);
    assertThat(normal.path("costWithMaxBreathGold").asLong()).isEqualTo(68_301);
    assertThat(normal.path("refineGold").asLong()).isEqualTo(8_500);
    assertThat(normal.at("/breath/name").asString()).isEqualTo("용암의 숨결");
    assertThat(normal.at("/breath/max").asInt()).isEqualTo(25);
    assertThat(normal.path("materials").toString()).contains("운명의 파괴석 결정").contains("운명의 파편");
    assertThat(weapon.path("missingPrices").size()).isZero();
    for (int i = 1; i < 4; i++) {
      var row = rows.get(i);
      assertThat(row.path("status").asString()).as(row.toString()).isEqualTo("UNSUPPORTED");
      assertThat(row.path("reasonCode").asString()).isEqualTo("SPECIAL_REFINE_UNSOURCED");
      assertThat(row.path("reason").asString()).isNotBlank();
      assertThat(row.path("valuePerStoneGold").isNull()).isTrue();
    }
    assertThat(rows.get(1).path("grade").asString()).isEqualTo("UPHWA");
    assertThat(rows.get(3).path("fromStage").asInt()).isEqualTo(10);
    OpenApiNulls.assertNullsAllowed(
        OpenApiNulls.document(port), "SpecialStoneValuesResponse", data);
    SiteNames.assertAbsent(response.body());
  }

  /** 전체 순위(무기·방어구 × 11~24 = 28개)를 한 번에 — 계약 초안 답 2: 처음(캐시 없음) 걸린 시간을 남긴다. */
  @Test
  void 전체_28개를_한_번에_계산한다() throws Exception {
    capturePrices(true);
    var body = new StringBuilder("{\"targets\":[");
    int n = 0;
    for (var type : List.of("WEAPON", "ARMOR"))
      for (int from = 11; from <= 24; from++)
        body.append(n++ == 0 ? "" : ",").append(target("JEONYUL", type, from));
    body.append(
        "],\"prices\":[{\"name\":\"재봉술 : 전율 [12-15]\",\"unitPriceGold\":2327},{\"name\":\"재봉술 : 전율 [16-19]\",\"unitPriceGold\":7100},{\"name\":\"야금술 : 전율 [12-15]\",\"unitPriceGold\":2400},{\"name\":\"야금술 : 전율 [16-19]\",\"unitPriceGold\":16995}]}");
    long started = System.nanoTime();
    var response = request(body.toString());
    long ms = (System.nanoTime() - started) / 1_000_000;
    System.out.printf("SPECIALSTONE 28 targets cold %d ms%n", ms);
    var rows = data(response).path("rows");
    assertThat(rows.size()).isEqualTo(28);
    for (var row : rows)
      assertThat(row.path("status").asString()).as(row.toString()).isEqualTo("OK");
  }

  /**
   * 최악(#1099 리뷰 P2): 28개 전부 + 무기·방어구 재료 가격을 매번 바꿔(모든 단계가 캐시에 없음) 10번. 걸린 시간 p50·최대를 남기고, 따로 둔 캐시는
   * 64개를 넘지 않는다.
   */
  @Test
  void 사용자_가격을_바꿔_가며_28개를_보내도_따로_둔_캐시는_64개까지다() throws Exception {
    capturePrices(true);
    var times = new ArrayList<Long>();
    for (int i = 0; i < 10; i++) {
      var body = new StringBuilder("{\"targets\":[");
      int n = 0;
      for (var type : List.of("WEAPON", "ARMOR"))
        for (int from = 11; from <= 24; from++)
          body.append(n++ == 0 ? "" : ",").append(target("JEONYUL", type, from));
      body.append("],\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":")
          .append(900 + i)
          .append("},{\"name\":\"운명의 수호석 결정\",\"unitPriceGold\":")
          .append(150 + i)
          .append(
              "},{\"name\":\"재봉술 : 전율 [12-15]\",\"unitPriceGold\":2327},{\"name\":\"재봉술 : 전율 [16-19]\",\"unitPriceGold\":7100},{\"name\":\"야금술 : 전율 [12-15]\",\"unitPriceGold\":2400},{\"name\":\"야금술 : 전율 [16-19]\",\"unitPriceGold\":16995}]}");
      long started = System.nanoTime();
      var rows = data(request(body.toString())).path("rows");
      times.add((System.nanoTime() - started) / 1_000_000);
      for (var row : rows)
        assertThat(row.path("status").asString()).as(row.toString()).isEqualTo("OK");
    }
    var sorted = times.stream().sorted().toList();
    System.out.printf(
        "SPECIALSTONE user-priced 28 x10 cold p50 %d ms max %d ms%n",
        sorted.get(sorted.size() / 2), sorted.getLast());
    assertThat(
            registry.get("loakraken.refining.special_stone.user_price.cache.size").gauge().value())
        .isLessThanOrEqualTo(64);
  }

  /**
   * 사용자 가격이 있는 요청은 따로 둔 풀이 자리·캐시를 쓴다(#1099 리뷰 P2): 가격을 바꿔 가며 보내도 /plans OPTIMAL이 쓰는 공유 자리를 붙잡거나 공유
   * 캐시(시세 기준 단계)를 밀어내지 않는다. 시세만 쓰는 요청은 그대로 공유 쪽.
   */
  @Test
  void 사용자_가격_풀이는_공유_자리와_공유_캐시를_쓰지_않는다() throws Exception {
    capturePrices(true);
    double sharedSize = registry.get("loakraken.receipt.optimal.cache.size").gauge().value();
    long sharedSolves = registry.get("loakraken.refining.optimal.solve").timer().count();
    var userPriced =
        "{\"targets\":["
            + target("JEONYUL", "WEAPON", 19)
            + "],\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":777}]}";
    var first = data(request(userPriced));
    assertThat(first.at("/rows/0/status").asString()).isEqualTo("OK");
    assertThat(registry.get("loakraken.receipt.optimal.cache.size").gauge().value())
        .as("공유 캐시 크기")
        .isEqualTo(sharedSize);
    assertThat(registry.get("loakraken.refining.optimal.solve").timer().count())
        .as("공유 자리 풀이 수")
        .isEqualTo(sharedSolves);
    var privateSolves = registry.find("loakraken.refining.special_stone.user_price.solve").timer();
    assertThat(privateSolves).as("사용자 가격 풀이 시간").isNotNull();
    // 같은 맥락의 다른 시험도 이 지표를 쌓으니, 이 요청 뒤 값을 기준으로 본다(풀이가 하나 이상 있었다).
    long afterFirst = privateSolves.count();
    assertThat(afterFirst).isPositive();
    // 같은 요청은 따로 둔 캐시에서 답한다(다시 풀지 않음, 값 같음).
    var again = data(request(userPriced));
    assertThat(privateSolves.count()).isEqualTo(afterFirst);
    assertThat(again.at("/rows/0/valuePerStoneGold").decimalValue())
        .isEqualByComparingTo(first.at("/rows/0/valuePerStoneGold").decimalValue());
    // 시세만 쓰는 요청은 공유 쪽(사용자 가격 풀이 수는 그대로).
    data(request("{\"targets\":[" + target("JEONYUL", "WEAPON", 19) + "]}"));
    assertThat(privateSolves.count()).isEqualTo(afterFirst);
    // 값은 같다: 시세와 같은 사용자 가격(파괴석 결정 100개 1,036 → 1개 10.36)이면 따로 둔 쪽도 1,471,202 / 490.4.
    var same =
        data(
            request(
                "{\"targets\":["
                    + target("JEONYUL", "WEAPON", 20)
                    + "],\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":10.36}]}"));
    assertThat(same.at("/rows/0/expectedNormalCostGold").doubleValue())
        .isCloseTo(1_471_202, within(1.0));
    assertThat(same.at("/rows/0/valuePerStoneGold").decimalValue()).isEqualByComparingTo("490.4");
  }

  @Test
  void 사용자_가격이_그_이름의_시세를_바꾸고_쓰지_않는_이름은_400이다() throws Exception {
    capturePrices(true);
    var base = data(request("{\"targets\":[" + target("JEONYUL", "WEAPON", 20) + "]}"));
    var cheaper =
        data(
            request(
                "{\"targets\":["
                    + target("JEONYUL", "WEAPON", 20)
                    + "],\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":5}]}"));
    assertThat(cheaper.at("/rows/0/expectedNormalCostGold").doubleValue())
        .isLessThan(base.at("/rows/0/expectedNormalCostGold").doubleValue());
    assertThat(cheaper.at("/rows/0/normalTry/materials").toString())
        .contains("\"unitPriceGold\":5");
    var unused =
        request(
            "{\"targets\":["
                + target("JEONYUL", "WEAPON", 20)
                + "],\"prices\":[{\"name\":\"빙하의 숨결\",\"unitPriceGold\":1}]}");
    assertThat(unused.statusCode()).as(unused.body()).isEqualTo(400);
  }

  @Test
  void 시세가_없는_재료는_PRICE_MISSING이고_사용자_가격으로_채우면_계산한다() throws Exception {
    capturePrices(false);
    var missing = data(request("{\"targets\":[" + target("JEONYUL", "WEAPON", 20) + "]}"));
    var row = missing.at("/rows/0");
    assertThat(row.path("status").asString()).as(row.toString()).isEqualTo("PRICE_MISSING");
    assertThat(row.path("missingPrices").toString()).contains("용암의 숨결");
    assertThat(row.path("expectedNormalCostGold").isNull()).isTrue();
    var filled =
        data(
            request(
                "{\"targets\":["
                    + target("JEONYUL", "WEAPON", 20)
                    + "],\"prices\":[{\"name\":\"용암의 숨결\",\"unitPriceGold\":433}]}"));
    assertThat(filled.at("/rows/0/status").asString()).isEqualTo("OK");
    assertThat(filled.at("/rows/0/expectedNormalCostGold").doubleValue())
        .isCloseTo(1_471_202, within(1.0));
  }

  @Test
  void 시세_캐시가_없으면_409다() throws Exception {
    var response = request("{\"targets\":[" + target("JEONYUL", "WEAPON", 20) + "]}");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
    assertThat(mapper.readTree(response.body()).path("code").asString())
        .isEqualTo("REFINING_PRICE_PENDING");
  }

  @Test
  void 잘못된_입력은_400이다() throws Exception {
    capturePrices(true);
    var many = new StringBuilder("{\"targets\":[");
    for (int i = 0; i < 29; i++)
      many.append(i == 0 ? "" : ",")
          .append(target(i % 2 == 0 ? "JEONYUL" : "UPHWA", "WEAPON", 10 + i / 2));
    many.append("]}");
    for (var body :
        List.of(
            "{\"targets\":[]}",
            many.toString(),
            "{\"targets\":["
                + target("JEONYUL", "WEAPON", 20)
                + ","
                + target("JEONYUL", "WEAPON", 20)
                + "]}",
            "{\"targets\":[" + target("JEONYUL", "WEAPON", 25) + "]}",
            "{\"targets\":[" + target("JEONYUL", "WEAPON", 9) + "]}",
            "{\"targets\":["
                + target("JEONYUL", "WEAPON", 20)
                + "],\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":-1}]}",
            "{\"targets\":["
                + target("JEONYUL", "WEAPON", 20)
                + "],\"prices\":[{\"name\":\"운명의 파괴석 결정\",\"unitPriceGold\":1.234}]}"))
      assertThat(request(body).statusCode()).as(body).isEqualTo(400);
  }

  @Test
  void 스위치가_꺼져_있으면_404이고_기본값은_꺼짐이다() throws Exception {
    capturePrices(true);
    var body = "{\"targets\":[" + target("JEONYUL", "WEAPON", 20) + "]}";
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "special-off", Map.of("refining.special-stone.enabled", "false")));
    try {
      assertThat(request(body).statusCode()).isEqualTo(404);
    } finally {
      environment.getPropertySources().remove("special-off");
    }
    assertThat(request(body).statusCode()).isEqualTo(200);
    var yaml =
        new String(
            getClass().getResourceAsStream("/application.yaml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yaml).contains("${REFINING_SPECIAL_STONE_ENABLED:false}");
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
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
                    URI.create(
                        "http://localhost:" + port + "/api/v1/refining/special-stone-values"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
