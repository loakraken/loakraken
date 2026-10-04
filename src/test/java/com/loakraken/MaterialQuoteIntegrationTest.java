package com.loakraken;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.market.application.*;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaterialQuoteIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean MarketPricesPort prices;
  @MockitoBean MarketOptionsPort options;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();
  private static final String ITEM =
      """
      {"itemId":123,"requiredQuantity":250,"ownedQuantity":40,"search":{"categoryCode":50000,"itemName":" test "}}
      """;

  @BeforeEach
  @AfterEach
  void clearCache() {
    // 독립 테스트 컨테이너의 거래소 캐시만 정리한다.
    var keys = redis.keys("market:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 기존_검색_캐시를_재사용하고_묶음_수량과_계산_근거를_반환한다() throws Exception {
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
                        new BigDecimal("10.125"),
                        BigDecimal.ONE,
                        BigDecimal.TEN))));
    var search = send("/api/v1/markets/items?categoryCode=50000&itemName=test", null, null);
    var response = quote("YDAY_AVG_PRICE", ITEM);
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("MATERIAL_QUOTE_SUCCESS");
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("material-purchase-v1");
    assertThat(json.at("/data/source").asText()).isEqualTo("ACTIVE_LISTING");
    assertThat(json.at("/data/complete").asBoolean()).isTrue();
    assertThat(json.at("/data/totalCostGold").decimalValue()).isEqualByComparingTo("30.375");
    assertThat(json.at("/data/items/0/observedAt").asText())
        .isEqualTo(mapper.readTree(search.body()).at("/data/observedAt").asText());
    assertThat(json.at("/data/items/0/search/itemName").asText()).isEqualTo("test");
    assertThat(json.at("/data/items/0/missingQuantity").asLong()).isEqualTo(210);
    assertThat(json.at("/data/items/0/purchase/bundleQuantity").asLong()).isEqualTo(3);
    assertThat(json.at("/data/items/0/purchase/surplusQuantity").asLong()).isEqualTo(90);
    assertThat(quote("YDAY_AVG_PRICE", ITEM).statusCode()).isEqualTo(200);
    verify(prices).fetch(eq(MarketSource.ACTIVE_LISTING), any());
    verifyNoInteractions(options);
    ApiContractFixture.write("material-quote.json", response.body());
  }

  @Test
  void 빈_페이지는_전체_상품_미존재로_단정하지_않고_미완성_견적을_반환한다() throws Exception {
    when(prices.fetch(any(), any())).thenReturn(new MarketPage(1, 10, 0, List.of()));
    var response = quote("CURRENT_MIN_PRICE", ITEM);
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.at("/data/complete").asBoolean()).isFalse();
    assertThat(json.at("/data/totalCostGold").isNull()).isTrue();
    assertThat(json.at("/data/knownCostGold").decimalValue()).isZero();
    assertThat(json.at("/data/items/0/status").asText()).isEqualTo("ITEM_NOT_IN_PAGE");
    assertThat(json.at("/data/items/0/purchase").isNull()).isTrue();
    ApiContractFixture.write("material-quote-incomplete.json", response.body());
  }

  @Test
  void 보유량_충당은_인증_API나_옵션_조회가_필요하지_않다() throws Exception {
    var response =
        quote("CURRENT_MIN_PRICE", ITEM.replace("\"ownedQuantity\":40", "\"ownedQuantity\":300"));
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.at("/data/totalCostGold").decimalValue()).isZero();
    assertThat(json.at("/data/items/0/status").asText()).isEqualTo("COVERED_BY_INVENTORY");
    assertThat(json.at("/data/items/0/observedAt").isNull()).isTrue();
    verifyNoInteractions(prices, options);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"items\":[]}",
        "{\"priceBasis\":\"WRONG\",\"items\":[]}",
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"items\":[null]}",
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"items\":[{}]}",
        "null",
        "{broken"
      })
  void 누락과_잘못된_JSON은_400으로_거절한다(String body) throws Exception {
    assertInvalid(body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "1000000000", "1.5", "1e100", "null"})
  void 수량을_절삭하지_않고_정수_범위를_검증한다(String quantity) throws Exception {
    assertInvalid(
        body(
            "CURRENT_MIN_PRICE",
            ITEM.replace("\"requiredQuantity\":250", "\"requiredQuantity\":" + quantity)));
    assertInvalid(
        body(
            "CURRENT_MIN_PRICE",
            ITEM.replace("\"ownedQuantity\":40", "\"ownedQuantity\":" + quantity)));
  }

  @Test
  void 중복_ID_소수_ID_항목수_초과와_잘못된_검색을_거절한다() throws Exception {
    assertInvalid(body("CURRENT_MIN_PRICE", ITEM + "," + ITEM));
    assertInvalid(body("CURRENT_MIN_PRICE", ITEM.replace("\"itemId\":123", "\"itemId\":123.5")));
    assertInvalid(
        body("CURRENT_MIN_PRICE", ITEM.replace("\"categoryCode\":50000", "\"categoryCode\":0")));
    assertInvalid(
        body(
            "CURRENT_MIN_PRICE",
            ITEM.replace("\"categoryCode\":50000", "\"categoryCode\":50000.1")));
    assertInvalid(body("CURRENT_MIN_PRICE", ITEM).replace("\"CURRENT_MIN_PRICE\"", "0"));
    assertInvalid(
        body(
            "CURRENT_MIN_PRICE",
            String.join(
                ",",
                java.util.stream.IntStream.range(1, 12)
                    .mapToObj(i -> ITEM.replace("\"itemId\":123", "\"itemId\":" + i))
                    .toList())));
  }

  @Test
  void 외부_호출_제한을_공통_오류로_전달한다() throws Exception {
    when(prices.fetch(any(), any()))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED));
    var response = quote("CURRENT_MIN_PRICE", ITEM);
    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("LOST_ARK_RATE_LIMITED");
    assertThat(mapper.readTree(response.body()).path("data").isNull()).isTrue();
  }

  @Test
  void JSON_외_요청은_415를_반환한다() throws Exception {
    assertThat(
            send("/api/v1/markets/material-quotes", body("CURRENT_MIN_PRICE", ITEM), "text/plain")
                .statusCode())
        .isEqualTo(415);
    verifyNoInteractions(prices);
  }

  private void assertInvalid(String body) throws Exception {
    var response = send("/api/v1/markets/material-quotes", body, "application/json");
    assertThat(response.statusCode()).as(body).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(prices, options);
  }

  private HttpResponse<String> quote(String basis, String items) throws Exception {
    return send("/api/v1/markets/material-quotes", body(basis, items), "application/json");
  }

  private String body(String basis, String items) {
    return "{\"priceBasis\":\"" + basis + "\",\"items\":[" + items + "]}";
  }

  private HttpResponse<String> send(String path, String body, String type) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
      if (body != null)
        builder.header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body));
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
