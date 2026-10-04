package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiIntegrationTest {
  @LocalServerPort private int port;

  @Test
  void OpenAPI_명세에_서비스_정보와_공통_오류_스키마가_포함된다() throws Exception {
    var response = request("/v3/api-docs");
    assertThat(response.statusCode()).isEqualTo(200);
    var document = JsonMapper.builder().build().readTree(response.body());
    assertThat(document.path("openapi").asText()).startsWith("3.1.");
    assertThat(document.at("/info/title").asText()).isEqualTo("LOAKraken API");
    assertThat(document.at("/components/schemas/ErrorResponse/properties/code").isMissingNode())
        .isFalse();
    assertThat(document.at("/components/schemas/ErrorResponse/properties/data/type").asText())
        .isEqualTo("null");
    assertThat(document.path("paths").has("/test/success")).isFalse();
    assertThat(document.path("paths").has("/actuator/health")).isFalse();
    ApiContractFixture.write("openapi.json", response.body());
    assertThat(
            document
                .at("/components/schemas/CharacterLoadoutResponse/properties/gems/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/GemsResponse/type").asText()).isEqualTo("object");
    assertThat(document.at("/components/schemas/CharacterResponse/required").toString())
        .contains("characterName", "combatPower", "analysis", "coverage");
    assertThat(
            document
                .at("/components/schemas/CharacterObservationCoverageResponse/required")
                .toString())
        .contains("equipment", "engravings", "gems", "cards", "arkPassive", "arkGrid", "siblings");
    assertThat(document.at("/components/schemas/ObservationSectionResponse/required").toString())
        .contains("state", "collections");
    assertThat(document.at("/components/schemas/CharacterAnalysisResponse/required").toString())
        .contains("diagnostics");
    assertThat(document.at("/components/schemas/DiagnosticFindingResponse/required").toString())
        .contains("code", "category", "evidencePath", "observedCount", "message");
    assertThat(
            document.at("/components/schemas/CharacterSnapshotHistoryResponse/required").toString())
        .contains("items", "nextCursor");
    assertThat(
            document
                .at(
                    "/components/schemas/CharacterSnapshotHistoryResponse/properties/nextCursor/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/SnapshotSummaryResponse/required").toString())
        .contains("snapshotId", "fetchedAt", "className", "itemLevel", "combatPower");
    assertThat(
            document
                .at(
                    "/components/schemas/SnapshotSummaryResponse/properties/combatPower/anyOf/1/type")
                .asText())
        .isEqualTo("null");
  }

  @Test
  void Swagger_UI_정적_페이지에_접근한다() throws Exception {
    var response = request("/swagger-ui/index.html");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Swagger UI");
  }

  @Test
  void 거래소_가격의_출처와_null_계약을_명시한다() throws Exception {
    var document = JsonMapper.builder().build().readTree(request("/v3/api-docs").body());
    for (var path : java.util.List.of("/api/v1/markets/items", "/api/v1/markets/trades")) {
      var operation = document.path("paths").path(path).path("get");
      assertThat(operation.isMissingNode()).isFalse();
      assertThat(operation.path("responses").toString())
          .contains("200", "400", "429", "502", "503", "504");
    }
    assertThat(document.at("/components/schemas/MarketPriceResponse/required").toString())
        .contains("source", "observedAt", "windowFrom", "windowTo", "search", "items");
    assertThat(
            document
                .at("/components/schemas/MarketPriceResponse/properties/windowFrom/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(
            document
                .at("/components/schemas/MarketPriceItem/properties/currentMinPrice/anyOf/1/type")
                .asText())
        .isEqualTo("null");
    assertThat(document.at("/components/schemas/MarketPriceItem/required").toString())
        .contains("bundleCount", "recentPrice", "yDayAvgPrice", "currentMinPrice");
  }

  @Test
  void 운영_지표_엔드포인트는_기본으로_공개하지_않는다() throws Exception {
    assertThat(request("/actuator/metrics").statusCode()).isEqualTo(404);
    assertThat(request("/actuator/prometheus").statusCode()).isEqualTo(404);
  }

  private HttpResponse<String> request(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
