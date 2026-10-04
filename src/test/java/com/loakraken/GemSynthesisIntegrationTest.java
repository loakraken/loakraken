package com.loakraken;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GemSynthesisIntegrationTest {
  @LocalServerPort int port;
  private final JsonMapper mapper = new JsonMapper();

  @Test
  void 부족한_기준_보석과_조건부_합성_순서_공식_근거를_반환한다() throws Exception {
    var response =
        request(
            """
        {"tier":4,"baseLevel":7,"targetLevel":10,"targetQuantity":1,"inventory":[{"level":9,"quantity":2},{"level":8,"quantity":2},{"level":7,"quantity":1}]}
        """,
            "application/json");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("GEM_SYNTHESIS_PLAN_SUCCESS");
    assertThat(json.at("/data/status").asText()).isEqualTo("MISSING_BASE_GEMS");
    assertThat(json.at("/data/additionalBaseQuantity").asLong()).isEqualTo(2);
    assertThat(json.at("/data/synthesisCount").asLong()).isEqualTo(3);
    assertThat(json.at("/data/steps/0/inputLevel").asInt()).isEqualTo(7);
    assertThat(json.at("/data/steps/2/outputLevel").asInt()).isEqualTo(10);
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("t4-gem-synthesis-v1");
    assertThat(json.at("/data/rulesVerifiedOn").asText()).isEqualTo("2026-10-04");
    assertThat(json.at("/data/tier").asInt()).isEqualTo(4);
    assertThat(json.at("/data/sources").size()).isEqualTo(2);
    assertThat(json.at("/data/outcomesPerSynthesis/0/probability").decimalValue())
        .isEqualByComparingTo("0.5");
    assertThat(json.at("/data/outcomesPerSynthesis/1/probability").decimalValue())
        .isEqualByComparingTo("0.5");
    ApiContractFixture.write("gem-synthesis-plan.json", response.body());
  }

  @Test
  void 보유분이_충분하면_READY와_잔여_수량을_반환한다() throws Exception {
    var response =
        request(
            """
        {"tier":4,"baseLevel":9,"targetLevel":10,"targetQuantity":1,"inventory":[{"level":9,"quantity":5}]}
        """,
            "application/json");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.at("/data/status").asText()).isEqualTo("READY");
    assertThat(json.at("/data/inventory/0/remainingQuantity").asLong()).isEqualTo(2);
    assertThat(json.at("/data/additionalBaseQuantity").asLong()).isZero();
    ApiContractFixture.write("gem-synthesis-ready.json", response.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{broken",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":7,\"targetQuantity\":1,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":0,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":11,\"targetQuantity\":1,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":0,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1001,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1.5,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":7.1,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[null]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[{}]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[{\"level\":6,\"quantity\":1}]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[{\"level\":7,\"quantity\":-1}]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[{\"level\":7,\"quantity\":1000000000}]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[{\"level\":7,\"quantity\":1.5}]}",
        "{\"tier\":4,\"baseLevel\":7,\"targetLevel\":10,\"targetQuantity\":1,\"inventory\":[{\"level\":7,\"quantity\":1},{\"level\":7,\"quantity\":2}]}"
      })
  void 잘못된_조건은_계산하지_않는다(String body) throws Exception {
    var response = request(body, "application/json");
    assertThat(response.statusCode()).as(body).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
  }

  @Test
  void 티어_누락과_다른_티어는_4티어로_추정하지_않는다() throws Exception {
    var body =
        """
        {"tier":4,"baseLevel":7,"targetLevel":10,"targetQuantity":1,"inventory":[]}
        """;
    assertThat(request(body.replace("\"tier\":4,", ""), "application/json").statusCode())
        .isEqualTo(400);
    assertThat(request(body.replace("\"tier\":4", "\"tier\":3"), "application/json").statusCode())
        .isEqualTo(400);
  }

  @Test
  void 빈_재고와_최대_목표를_계산하고_JSON_이외_본문은_거절한다() throws Exception {
    var body =
        "{\"tier\":4,\"baseLevel\":1,\"targetLevel\":10,\"targetQuantity\":1000,\"inventory\":[]}";
    var response = request(body, "application/json");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(response.body()).at("/data/additionalBaseQuantity").asLong())
        .isEqualTo(19_683_000L);
    assertThat(request(body, "text/plain").statusCode()).isEqualTo(415);
  }

  private HttpResponse<String> request(String body, String contentType) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + port + "/api/v1/gems/synthesis-plans"))
              .header("Content-Type", contentType)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
