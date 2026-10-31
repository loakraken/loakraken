package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
// OPTIMAL 스위치를 켜고 풀이 자리를 1개로 둔다(몰림 테스트가 제한기 없이는 실패하게).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"refining.optimal.enabled=true", "refining.optimal.max-concurrent-solves=1"})
class RefiningPlanIntegrationTest {
  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.core.env.ConfigurableEnvironment environment;

  private final JsonMapper mapper = new JsonMapper();

  @Test
  void 고정된_규칙으로_단계별_시도_분포와_재료를_계산하고_출처를_밝힌다() throws Exception {
    var response =
        request(
            """
            {"grade":"UPHWA","equipmentType":"ARMOR","fromStage":18,"toStage":20,
             "useBreath":true,"useBook":true,"growthSupport":false}
            """);
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("refining-plan.json", response.body());
    // 응답 source에도 다른 사이트 이름이 없다(#261, MIT 고지는 저장소 NOTICE).
    SiteNames.assertAbsent(response.body());
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("REFINING_PLAN_SUCCESS");
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("t4-refining-v4");
    assertThat(json.at("/data/source/license").asText()).startsWith("MIT");
    // 책은 생략하면 켬(#279, PM 총괄 10-11 결정), false를 명시하면 끔.
    var omitted =
        mapper.readTree(
            request(
                    "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":18,\"toStage\":20}")
                .body());
    assertThat(omitted.at("/data/useBook").asBoolean()).isTrue();
    var off =
        mapper.readTree(
            request(
                    "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":18,\"toStage\":20,\"useBook\":false}")
                .body());
    assertThat(off.at("/data/useBook").asBoolean()).isFalse();
    // 성장 비용은 생략하면 끔(PM-백엔드 10-10 09:10), 영지 연구는 생략하면 켬.
    assertThat(json.at("/data/growthCost").asBoolean()).isFalse();
    assertThat(json.at("/data/expResearch").asBoolean()).isTrue();
    assertThat(json.at("/data/stages/0/growth").isNull()).isTrue();
    assertThat(json.at("/data/source/commit").asText()).startsWith("5b870d2");
    var stages = json.at("/data/stages");
    assertThat(stages.size()).isEqualTo(2);
    assertThat(stages.get(0).path("targetStage").asInt()).isEqualTo(19);
    // 업화 방어구 19단계: 기본 3% + 빙하의 숨결 0.15%×20 + 재봉술 3% = 9%
    assertThat(stages.get(0).path("firstAttemptSuccessRateBp").asInt()).isEqualTo(900);
    assertThat(stages.get(0).path("maxAttempts").asInt())
        .isGreaterThanOrEqualTo(stages.get(0).path("p90Attempts").asInt());
    assertThat(json.at("/data/expectedMaterials").toString())
        .contains("운명의 수호석", "운명의 파편", "빙하의 숨결", "재봉술 : 업화 [19-20]");
    assertThat(json.at("/data/assumptions").size()).isGreaterThan(0);
  }

  @Test
  void 운명의_전율_무기도_계산하고_책_요청은_해당_단계에_책이_없으면_무시한다() throws Exception {
    var response =
        request(
            """
            {"grade":"JEONYUL","equipmentType":"WEAPON","fromStage":24,"toStage":25,"useBook":true,
             "currentArtisanEnergy":46.50,"currentFailedAttempts":3}
            """);
    assertThat(response.statusCode()).isEqualTo(200);
    var stage = mapper.readTree(response.body()).at("/data/stages/0");
    // 전율 25단계 기본 0.5% + 실패 3회 × 0.05% = 0.65%
    assertThat(stage.path("firstAttemptSuccessRateBp").asInt()).isEqualTo(65);
    assertThat(stage.path("expectedMaterials").toString())
        .contains("운명의 파괴석 결정", "위대한 운명의 돌파석", "상급 아비도스 융화 재료")
        .doesNotContain("야금술");
  }

  @Test
  void 전율_방어구_21단계_한계_해방은_파편을_재료에_더하고_실링과_가시는_nonGold로_보인다() throws Exception {
    var off =
        mapper.readTree(
            request(
                    "{\"grade\":\"JEONYUL\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21}")
                .body());
    assertThat(off.at("/data/nonGold").isArray()).isTrue();
    assertThat(off.at("/data/nonGold").size()).isZero();
    var response =
        request(
            "{\"grade\":\"JEONYUL\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21,\"growthCost\":true}");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    // 공식 공지 13339·게임 가이드 이미지(2026-10-25): 파편 72,000 · 실링 720,000 · 고통의 가시 15
    var growth = json.at("/data/stages/0/growth");
    assertThat(growth.path("shard").decimalValue()).isEqualByComparingTo("72000");
    assertThat(growth.path("silver").asLong()).isEqualTo(720_000);
    assertThat(growth.at("/extraMaterials/0/name").asText()).isEqualTo("고통의 가시");
    assertThat(growth.at("/extraMaterials/0/amount").decimalValue()).isEqualByComparingTo("15");
    var nonGold = json.at("/data/nonGold");
    assertThat(nonGold.size()).isEqualTo(2);
    assertThat(nonGold.at("/0/name").asText()).isEqualTo("실링");
    assertThat(nonGold.at("/0/amount").decimalValue()).isEqualByComparingTo("720000");
    assertThat(nonGold.at("/1/name").asText()).isEqualTo("고통의 가시");
    assertThat(nonGold.at("/1/amount").decimalValue()).isEqualByComparingTo("15");
    assertThat(json.at("/data/expectedMaterials").toString()).doesNotContain("고통의 가시");
    assertThat(json.at("/data/worstCaseMaterials").toString()).doesNotContain("고통의 가시");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":20}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":9,\"toStage\":12}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":26}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21,\"currentArtisanEnergy\":100.01}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21,\"currentArtisanEnergy\":10.123}",
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21,\"currentFailedAttempts\":11}",
        "{\"grade\":\"MYTHIC\",\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21}",
        "{\"equipmentType\":\"ARMOR\",\"fromStage\":20,\"toStage\":21}"
      })
  void 잘못된_입력은_400이다(String body) throws Exception {
    assertThat(request(body).statusCode()).isEqualTo(400);
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.loakraken.market.application.MarketCache marketCache;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.data.redis.core.StringRedisTemplate redis;

  @org.junit.jupiter.api.BeforeEach
  @org.junit.jupiter.api.AfterEach
  void 시세_정리() {
    redis.delete("market:v1:refining-materials");
  }

  @Test
  void 숨결_OPTIMAL은_시세가_없으면_409이고_있으면_회차별_선택과_기대_비용을_준다() throws Exception {
    var body =
        """
        {"grade":"UPHWA","equipmentType":"ARMOR","fromStage":10,"toStage":12,
         "breath":"OPTIMAL","growthSupport":true}
        """;
    // 시세가 없으면 숨결 없음·최대로 바꿔 계산하지 않는다.
    var pending = request(body);
    assertThat(pending.statusCode()).isEqualTo(409);
    ApiContractFixture.write("refining-plan-price-pending.json", pending.body());
    assertThat(mapper.readTree(pending.body()).path("code").asText())
        .isEqualTo("REFINING_PRICE_PENDING");
    assertThat(pending.headers().firstValue("Retry-After")).contains("60");
    var observedAt = java.time.Instant.parse("2026-10-24T15:54:51Z");
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            observedAt,
            java.util.List.of(
                material(1, "운명의 수호석", 100, "32"),
                material(2, "운명의 돌파석", 1, "5"),
                material(3, "아비도스 융화 재료", 1, "197"),
                material(4, "운명의 파편 주머니(소)", 1, "92"),
                material(5, "빙하의 숨결", 1, "430"),
                material(6, "재봉술 : 업화 [11-14]", 1, "2000"))),
        java.time.Duration.ofMinutes(5));
    var response = request(body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("refining-plan-optimal.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("breath").asText()).isEqualTo("OPTIMAL");
    assertThat(data.path("useBreath").asBoolean()).isTrue();
    assertThat(data.path("pricesObservedAt").asText()).isEqualTo("2026-10-24T15:54:51Z");
    long cost = 0;
    for (var stage : data.path("stages")) {
      assertThat(stage.path("breathPath").isArray()).isTrue();
      assertThat(stage.path("breathPath").size()).isPositive();
      assertThat(stage.path("expectedBreaths").isNumber()).isTrue();
      assertThat(stage.path("expectedBooks").isNumber()).isTrue();
      cost += stage.path("expectedCostGold").asLong();
    }
    // 회차별 최적 기준값(성장 지원 20%) 18,095 + 18,866 = 36,962, 단계마다 올림이라 2골드 안.
    assertThat(cost).isBetween(36_962L, 36_964L);
    // 계획 합계는 올리기 전 값의 합을 한 번 올린 값(스펙업과 같은 방식, PM 총괄 10-25): 단계 올림 합보다 크지 않다.
    long total = data.path("expectedCostGold").asLong();
    assertThat(total).isBetween(36_962L, 36_963L).isLessThanOrEqualTo(cost);
    // breath를 생략하면 useBreath로 정하고 회차별 정보는 없다
    var max =
        mapper.readTree(
            request(
                    "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12,\"useBreath\":true}")
                .body());
    assertThat(max.at("/data/breath").asText()).isEqualTo("MAX");
    assertThat(max.at("/data/pricesObservedAt").isNull()).isTrue();
    assertThat(max.at("/data/expectedCostGold").isNull()).isTrue();
    assertThat(max.at("/data/stages/0/breathPath").isNull()).isTrue();
  }

  @Test
  void useBreath와_breath가_어긋나면_400이다() throws Exception {
    for (var pair :
        new String[] {
          "\"useBreath\":true,\"breath\":\"NONE\"", "\"useBreath\":false,\"breath\":\"MAX\""
        }) {
      var response =
          request(
              "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12,"
                  + pair
                  + "}");
      assertThat(response.statusCode()).as(pair).isEqualTo(400);
    }
  }

  @org.springframework.beans.factory.annotation.Autowired
  com.loakraken.refining.application.RefiningPlanService plans;

  /**
   * 풀이 자리(이 테스트는 1개)가 다 차 있으면 캐시에 없는 OPTIMAL은 기다리지 않고 바로 429 REFINING_SOLVE_BUSY이고, 자리를 쓰지 않는 다른
   * 방식(MAX·NONE)은 그대로 답한다. 자리는 테스트가 잡고 있어 시간 겹침에 기대지 않는다(PM 총괄 10-25). 자리를 놓으면 같은 요청이 200이다.
   */
  @Test
  void 풀이_자리가_다_차_있으면_OPTIMAL은_바로_REFINING_SOLVE_BUSY이고_다른_방식은_답한다() throws Exception {
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            java.time.Instant.parse("2026-10-24T15:54:51Z"),
            java.util.List.of(
                material(1, "운명의 수호석", 100, "32"),
                material(2, "운명의 돌파석", 1, "5"),
                material(3, "아비도스 융화 재료", 1, "197"),
                material(4, "운명의 파편 주머니(소)", 1, "92"),
                material(5, "빙하의 숨결", 1, "430"),
                material(6, "재봉술 : 업화 [11-14]", 1, "2000"))),
        java.time.Duration.ofMinutes(5));
    // 이 클래스에서 쓰지 않는 시작 상태라 첫 단계 풀이가 반드시 캐시에 없다.
    var optimal =
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12,"
            + "\"breath\":\"OPTIMAL\",\"currentArtisanEnergy\":63.21,\"currentFailedAttempts\":7}";
    try (var hold = com.loakraken.refining.application.SolveSlotHold.hold(plans)) {
      var busy = request(optimal);
      assertThat(busy.statusCode()).as(busy.body()).isEqualTo(429);
      assertThat(mapper.readTree(busy.body()).path("code").asText())
          .isEqualTo("REFINING_SOLVE_BUSY");
      assertThat(busy.headers().firstValue("Retry-After")).contains("2");
      for (var breath : java.util.List.of("MAX", "NONE")) {
        var other =
            request(
                "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12,\"breath\":\""
                    + breath
                    + "\"}");
        assertThat(other.statusCode()).as(breath + " " + other.body()).isEqualTo(200);
      }
    }
    var after = request(optimal);
    assertThat(after.statusCode()).as(after.body()).isEqualTo(200);
  }

  /** OPTIMAL 스위치(기본 꺼짐)가 꺼져 있으면 breath=OPTIMAL은 계산하지 않고 400, 다른 방식은 그대로(코드 리뷰·PM 총괄 10-25). */
  @Test
  void OPTIMAL_스위치가_꺼져_있으면_OPTIMAL은_400이고_다른_방식은_그대로다() throws Exception {
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            java.time.Instant.parse("2026-10-24T15:54:51Z"),
            java.util.List.of(
                material(1, "운명의 수호석", 100, "32"),
                material(2, "운명의 돌파석", 1, "5"),
                material(3, "아비도스 융화 재료", 1, "197"),
                material(4, "운명의 파편 주머니(소)", 1, "92"),
                material(5, "빙하의 숨결", 1, "430"),
                material(6, "재봉술 : 업화 [11-14]", 1, "2000"))),
        java.time.Duration.ofMinutes(5));
    var optimal =
        "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12,\"breath\":\"OPTIMAL\"}";
    environment
        .getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "optimal-off", java.util.Map.of("refining.optimal.enabled", "false")));
    try {
      var off = request(optimal);
      assertThat(off.statusCode()).as(off.body()).isEqualTo(400);
      assertThat(mapper.readTree(off.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
      var max =
          request(
              "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12,\"breath\":\"MAX\"}");
      assertThat(max.statusCode()).isEqualTo(200);
    } finally {
      environment.getPropertySources().remove("optimal-off");
    }
    assertThat(request(optimal).statusCode()).isEqualTo(200);
  }

  /**
   * 여러 부위 합계(#764): 부위마다 /plans와 같은 계획, 재료는 부위를 더해 한 번 올리고 가진 수량을 뺀 나머지를 통째 매물로 산다. OPTIMAL 계획 기대
   * 비용은 모든 부위 단계 값을 더해 한 번 올린다(부위 값의 합보다 크지 않다).
   */
  @Test
  void 여러_부위_합계는_부위_계획을_모아_재료를_한_번_올리고_가진_수량을_뺀다() throws Exception {
    marketCache.saveRefiningMaterials(
        new com.loakraken.market.application.RefiningMaterialScan(
            java.time.Instant.parse("2026-11-01T02:00:00Z"),
            java.util.List.of(
                material(1, "운명의 수호석", 100, "32"),
                material(2, "운명의 돌파석", 1, "5"),
                material(3, "아비도스 융화 재료", 1, "197"),
                material(4, "운명의 파편 주머니(소)", 1, "92"),
                material(7, "운명의 파편 주머니(대)", 1, "270"),
                material(5, "빙하의 숨결", 1, "430"),
                material(6, "재봉술 : 업화 [11-14]", 1, "2000"))),
        java.time.Duration.ofMinutes(5));
    var part = "{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":10,\"toStage\":12}";
    var body =
        "{\"breath\":\"OPTIMAL\",\"parts\":["
            + part
            + ","
            + part
            + "],\"owned\":[{\"name\":\"운명의 수호석\",\"amount\":100},{\"name\":\"없는 재료\",\"amount\":5}]}";
    var response = total(body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("refining-plan-total.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("totalRuleVersion").asText()).isEqualTo("refining-plan-total-v1");
    assertThat(data.path("parts").size()).isEqualTo(2);
    // 재련 골드 = 모든 부위·단계 expectedGold 합을 한 번 올림
    var stageGold = java.math.BigDecimal.ZERO;
    long partCost = 0;
    for (var p : data.path("parts")) {
      partCost += p.path("expectedCostGold").asLong();
      for (var stage : p.path("stages"))
        stageGold = stageGold.add(stage.path("expectedGold").decimalValue());
    }
    assertThat(data.path("refineGold").asLong())
        .isEqualTo(stageGold.setScale(0, java.math.RoundingMode.CEILING).longValueExact());
    assertThat(data.path("expectedCostGold").asLong()).isLessThanOrEqualTo(partCost);
    long purchase = 0;
    for (var line : data.path("materials")) {
      long amount = line.path("amount").asLong();
      assertThat(
              line.path("expected")
                  .decimalValue()
                  .setScale(0, java.math.RoundingMode.CEILING)
                  .longValueExact())
          .isEqualTo(amount);
      assertThat(line.path("missing").asLong())
          .isEqualTo(Math.max(0, amount - line.path("owned").asLong()));
      if (line.path("listing").isNull()) continue;
      long perUnit =
          line.at("/listing/bundleCount").asLong() * line.at("/listing/contentsPerItem").asLong();
      assertThat(line.path("listings").asLong())
          .isEqualTo((line.path("missing").asLong() + perUnit - 1) / perUnit);
      purchase += line.path("purchaseGold").asLong();
      if ("운명의 수호석".equals(line.path("name").asText()))
        assertThat(line.path("owned").asLong()).isEqualTo(100);
      if ("운명의 파편".equals(line.path("name").asText()))
        // 1개 값이 더 싼 주머니(대) 270 ÷ 3,000 < (소) 92 ÷ 1,000
        assertThat(line.at("/listing/contentsPerItem").asInt()).isEqualTo(3000);
    }
    assertThat(data.path("purchaseGold").asLong()).isEqualTo(purchase);
    assertThat(data.path("totalGold").asLong())
        .isEqualTo(data.path("refineGold").asLong() + purchase);
    assertThat(data.path("pricesObservedAt").asText()).isEqualTo("2026-11-01T02:00:00Z");

    for (var bad :
        java.util.List.of(
            "{\"parts\":[]}",
            "{\"parts\":[" + String.join(",", java.util.Collections.nCopies(7, part)) + "]}",
            "{\"parts\":[" + part + "],\"owned\":[{\"name\":\"운명의 수호석\",\"amount\":-1}]}",
            "{\"parts\":[{\"grade\":\"UPHWA\",\"equipmentType\":\"ARMOR\",\"fromStage\":12,\"toStage\":12}]}"))
      assertThat(total(bad).statusCode()).as(bad).isEqualTo(400);
  }

  private HttpResponse<String> total(String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/refining/plans/total"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  /** 설정을 주지 않으면 OPTIMAL 스위치는 꺼짐이다(운영 기본값). */
  @Test
  void OPTIMAL_스위치_기본값은_꺼짐이다() throws Exception {
    var yaml =
        new String(
            getClass().getResourceAsStream("/application.yaml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yaml).contains("enabled: ${REFINING_OPTIMAL_ENABLED:false}");
  }

  private static com.loakraken.market.domain.MarketPrice material(
      long id, String name, int bundle, String currentMin) {
    return new com.loakraken.market.domain.MarketPrice(
        id, name, "영웅", null, bundle, null, null, null, new java.math.BigDecimal(currentMin));
  }

  private HttpResponse<String> request(String body) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/refining/plans"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
