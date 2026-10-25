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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefiningPlanIntegrationTest {
  @LocalServerPort int port;
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
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("t4-refining-v3");
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
