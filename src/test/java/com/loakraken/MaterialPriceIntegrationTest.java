package com.loakraken;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
class MaterialPriceIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean MarketPricesPort prices;
  @MockitoBean MarketOptionsPort options;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void clearCache() {
    // 독립 테스트 컨테이너의 거래소 캐시만 정리한다.
    var keys = redis.keys("market:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 재료_이름별_1개당_가격과_파편_주머니_환산을_반환한다() throws Exception {
    when(prices.fetch(
            any(), argThat(search -> search != null && search.itemName().equals("운명의 수호석"))))
        .thenReturn(page(price(1, "운명의 수호석", 100, "31")));
    when(prices.fetch(
            any(), argThat(search -> search != null && search.itemName().equals("운명의 파편 주머니"))))
        .thenReturn(page(price(2, "운명의 파편 주머니(소)", 1, "85"), price(3, "운명의 파편 주머니(대)", 1, "232")));
    when(prices.fetch(
            any(), argThat(search -> search != null && search.itemName().equals("없는 재료"))))
        .thenReturn(page());
    var response =
        send(
            "{\"priceBasis\":\"RECENT_PRICE\",\"names\":[\" 운명의 수호석 \",\"운명의 파편\",\"없는 재료\",\"운명의 수호석\"]}",
            "application/json");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("MATERIAL_PRICE_SUCCESS");
    assertThat(json.at("/data/priceBasis").asText()).isEqualTo("RECENT_PRICE");
    assertThat(json.at("/data/items").size()).isEqualTo(3);
    assertThat(json.at("/data/items/0/name").asText()).isEqualTo("운명의 수호석");
    assertThat(json.at("/data/items/0/status").asText()).isEqualTo("PRICED");
    assertThat(json.at("/data/items/0/unitPriceGold").decimalValue()).isEqualByComparingTo("0.31");
    assertThat(json.at("/data/items/0/contentsPerItem").isNull()).isTrue();
    assertThat(json.at("/data/items/1/sourceItemName").asText()).isEqualTo("운명의 파편 주머니(대)");
    assertThat(json.at("/data/items/1/contentsPerItem").asInt()).isEqualTo(3000);
    assertThat(json.at("/data/items/1/unitPriceGold").decimalValue())
        .isEqualByComparingTo("0.077333");
    assertThat(json.at("/data/items/2/status").asText()).isEqualTo("NOT_FOUND");
    assertThat(json.at("/data/items/2/unitPriceGold").isNull()).isTrue();
    assertThat(json.at("/data/items/2/observedAt").isString()).isTrue();
    verify(prices, times(3)).fetch(eq(MarketSource.ACTIVE_LISTING), any());
    verifyNoInteractions(options);
    ApiContractFixture.write("material-prices.json", response.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"priceBasis\":\"RECENT_PRICE\",\"names\":[]}",
        "{\"priceBasis\":\"WRONG\",\"names\":[\"a\"]}",
        "{\"priceBasis\":\"RECENT_PRICE\",\"names\":[\" \"]}",
        "{\"priceBasis\":\"RECENT_PRICE\",\"names\":[null]}",
        "{\"names\":[\"a\"]}",
        "{broken"
      })
  void 누락과_잘못된_요청은_400으로_거절한다(String body) throws Exception {
    var response = send(body, "application/json");
    assertThat(response.statusCode()).as(body).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(prices, options);
  }

  @Test
  void 이름이_20개를_넘거나_JSON이_아니면_거절한다() throws Exception {
    var names =
        String.join(
            ",", java.util.stream.IntStream.range(0, 21).mapToObj(i -> "\"재료" + i + "\"").toList());
    assertThat(
            send("{\"priceBasis\":\"RECENT_PRICE\",\"names\":[" + names + "]}", "application/json")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            send("{\"priceBasis\":\"RECENT_PRICE\",\"names\":[\"a\"]}", "text/plain").statusCode())
        .isEqualTo(415);
    verifyNoInteractions(prices);
  }

  private MarketPage page(MarketPrice... items) {
    return new MarketPage(1, 10, items.length, List.of(items));
  }

  private MarketPrice price(long id, String name, int bundle, String recent) {
    return new MarketPrice(id, name, null, null, bundle, null, null, new BigDecimal(recent), null);
  }

  private HttpResponse<String> send(String body, String type) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + port + "/api/v1/markets/material-prices"))
              .header("Content-Type", type)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
