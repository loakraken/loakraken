package com.loakraken;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.*;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketSearchIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean MarketPricesPort prices;
  private final JsonMapper mapper = new JsonMapper();

  @Test
  void 출처와_검색조건_조회시각_가격을_직렬화한다() throws Exception {
    when(prices.fetch(any(), any()))
        .thenReturn(
            new MarketPage(
                1,
                10,
                1,
                List.of(
                    new MarketPrice(
                        123,
                        "검증 재료",
                        null,
                        null,
                        100,
                        null,
                        new BigDecimal("12.34"),
                        BigDecimal.ZERO,
                        null))));
    for (var type : List.of("items", "trades")) {
      var response = request("/api/v1/markets/" + type + "?categoryCode=50000&itemName=%20test%20");
      assertThat(response.statusCode()).isEqualTo(200);
      var json = mapper.readTree(response.body());
      assertThat(json.path("code").asText()).isEqualTo("MARKET_SEARCH_SUCCESS");
      assertThat(json.at("/data/source").asText())
          .isEqualTo(type.equals("items") ? "ACTIVE_LISTING" : "RECENT_TRADE");
      assertThat(json.at("/data/observedAt").asText()).isNotBlank();
      assertThat(json.at("/data/windowFrom").isNull()).isTrue();
      assertThat(json.at("/data/windowTo").isNull()).isTrue();
      assertThat(json.at("/data/search/itemName").asText()).isEqualTo("test");
      assertThat(json.at("/data/search/pageNo").asInt()).isEqualTo(1);
      assertThat(json.at("/data/search/sort").asText()).isEqualTo("RECENT_PRICE");
      assertThat(json.at("/data/items/0/recentPrice").decimalValue()).isZero();
      assertThat(json.at("/data/items/0/currentMinPrice").isNull()).isTrue();
      ApiContractFixture.write("market-" + type + ".json", response.body());
    }
    verify(prices).fetch(eq(MarketSource.ACTIVE_LISTING), any());
    verify(prices).fetch(eq(MarketSource.RECENT_TRADE), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "categoryCode=0",
        "categoryCode=-1",
        "categoryCode=abc",
        "categoryCode=50000&pageNo=0",
        "categoryCode=50000&pageNo=1001",
        "categoryCode=50000&itemTier=0",
        "categoryCode=50000&sort=WRONG",
        "categoryCode=50000&sortCondition=WRONG"
      })
  void 잘못된_검색은_외부_포트에_도달하지_않는다(String query) throws Exception {
    var response = request("/api/v1/markets/items?" + query);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(prices);
  }

  @Test
  void 너무_긴_검색어는_거절한다() throws Exception {
    assertThat(
            request("/api/v1/markets/items?categoryCode=50000&itemName=" + "a".repeat(65))
                .statusCode())
        .isEqualTo(400);
    verifyNoInteractions(prices);
  }

  private HttpResponse<String> request(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
