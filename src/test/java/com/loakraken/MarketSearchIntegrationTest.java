package com.loakraken;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.*;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class MarketSearchIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean MarketPricesPort prices;
  @MockitoBean MarketOptionsPort options;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void clearCache() {
    // 독립 Testcontainers Valkey의 이 테스트 전용 네임스페이스만 정리한다.
    var keys = redis.keys("market:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

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
      assertThat(request("/api/v1/markets/" + type + "?categoryCode=50000&itemName=test").body())
          .isEqualTo(response.body());
    }
    verify(prices).fetch(eq(MarketSource.ACTIVE_LISTING), any());
    verify(prices).fetch(eq(MarketSource.RECENT_TRADE), any());
    verifyNoInteractions(options);
    assertThat(redis.keys("market:v1:prices:*")).hasSize(2);
    for (var key : redis.keys("market:v1:prices:*")) {
      assertThat(redis.getExpire(key)).isBetween(1L, 30L);
      assertThat(key).matches("market:v1:prices:[0-9a-f]{64}");
    }
  }

  @Test
  void 검색_옵션의_하위_목록과_캐시_시각을_보존한다() throws Exception {
    when(options.fetchOptions()).thenReturn(MarketTestFixture.options());
    var first = request("/api/v1/markets/options");
    var second = request("/api/v1/markets/options");
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    var json = mapper.readTree(first.body());
    assertThat(json.path("code").asText()).isEqualTo("MARKET_OPTIONS_SUCCESS");
    assertThat(json.at("/data/observedAt").asText()).isNotBlank();
    assertThat(json.at("/data/categories/0/subs/0/code").asInt()).isEqualTo(50010);
    assertThat(json.at("/data/categories/1/subs").isEmpty()).isTrue();
    assertThat(json.at("/data/categories/2/subs").isNull()).isTrue();
    assertThat(json.at("/data/itemTiers").toString()).isEqualTo("[3,4]");
    assertThat(redis.getExpire("market:v1:options")).isBetween(1L, 600L);
    verify(options, times(1)).fetchOptions();
    verifyNoInteractions(prices);
    ApiContractFixture.write("market-options.json", first.body());
  }

  @Test
  void 모든_검색조건과_가격_출처는_독립적인_캐시를_사용한다() throws Exception {
    when(prices.fetch(any(), any()))
        .thenAnswer(
            invocation -> {
              MarketSearch query = invocation.getArgument(1);
              return new MarketPage(query.pageNo(), 10, 0, List.of());
            });
    var queries =
        List.of(
            "categoryCode=50000",
            "categoryCode=50010",
            "categoryCode=50000&characterClass=bard",
            "categoryCode=50000&itemTier=4",
            "categoryCode=50000&itemGrade=rare",
            "categoryCode=50000&itemName=item",
            "categoryCode=50000&pageNo=2",
            "categoryCode=50000&sort=GRADE",
            "categoryCode=50000&sortCondition=DESC");
    for (var query : queries) {
      assertThat(request("/api/v1/markets/items?" + query).statusCode()).isEqualTo(200);
      assertThat(request("/api/v1/markets/items?" + query).statusCode()).isEqualTo(200);
    }
    assertThat(request("/api/v1/markets/trades?categoryCode=50000").statusCode()).isEqualTo(200);
    assertThat(redis.keys("market:v1:prices:*")).hasSize(10);
    verify(prices, times(10)).fetch(any(), any());
  }

  @Test
  void 만료_가격과_손상_옵션은_다시_수집한다() throws Exception {
    when(prices.fetch(any(), any())).thenReturn(new MarketPage(1, 10, 0, List.of()));
    String path = "/api/v1/markets/items?categoryCode=50000";
    assertThat(request(path).statusCode()).isEqualTo(200);
    var key = redis.keys("market:v1:prices:*").iterator().next();
    redis.expire(key, Duration.ZERO);
    assertThat(request(path).statusCode()).isEqualTo(200);
    verify(prices, times(2)).fetch(any(), any());
    redis.opsForValue().set("market:v1:options", "broken");
    when(options.fetchOptions()).thenReturn(MarketTestFixture.options());
    assertThat(request("/api/v1/markets/options").statusCode()).isEqualTo(200);
    assertThat(redis.getExpire("market:v1:options")).isBetween(1L, 600L);
    verify(options).fetchOptions();
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
