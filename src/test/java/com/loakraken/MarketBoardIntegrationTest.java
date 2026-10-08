package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.MarketItemHistoryPort;
import com.loakraken.market.application.MarketOptionsPort;
import com.loakraken.market.application.MarketPage;
import com.loakraken.market.application.MarketPricesPort;
import com.loakraken.market.application.MarketSearch;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
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
class MarketBoardIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean MarketPricesPort prices;
  @MockitoBean MarketOptionsPort options;
  @MockitoBean MarketItemHistoryPort history;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void clearCache() {
    var keys = redis.keys("market:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 상승_정렬은_탭_전체를_한_번만_스캔해_재사용하고_이력_캐시로_7일_값을_채운다() throws Exception {
    when(prices.fetch(any(), any()))
        .thenAnswer(
            invocation -> {
              MarketSearch search = invocation.getArgument(1);
              int from = (search.pageNo() - 1) * 10 + 1;
              int to = Math.min(13, from + 9);
              return new MarketPage(
                  search.pageNo(),
                  10,
                  13,
                  IntStream.rangeClosed(from, to)
                      .mapToObj(MarketBoardIntegrationTest::item)
                      .toList());
            });
    var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    for (int i = 0; i <= 13; i++)
      days.add(new MarketItemHistory.DailyStat(today.minusDays(i), BigDecimal.valueOf(100), 10));
    when(history.fetchHistory(13L))
        .thenReturn(
            Optional.of(
                new MarketItemHistory(
                    13L, List.of(new MarketItemHistory.Variant("품목13", null, 1, days)))));
    assertThat(get("/api/v1/markets/items/13/history").statusCode()).isEqualTo(200);

    var response = get("/api/v1/markets/board?tab=ENGRAVING_BOOK&sort=rise");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("MARKET_BOARD_SUCCESS");
    assertThat(json.at("/data/ruleVersion").asText()).isEqualTo("market-board-v1");
    assertThat(json.at("/data/sort").asText()).isEqualTo("rise");
    assertThat(json.at("/data/tier").isNull()).isTrue();
    assertThat(json.at("/data/totalItems").asInt()).isEqualTo(13);
    assertThat(json.at("/data/scannedItems").asInt()).isEqualTo(13);
    assertThat(json.at("/data/complete").asBoolean()).isTrue();
    assertThat(json.at("/data/items/0/itemId").asLong()).isEqualTo(13L);
    assertThat(json.at("/data/items/0/changeDay").decimalValue()).isEqualByComparingTo("13.0");
    assertThat(json.at("/data/items/0/historyAvailable").asBoolean()).isTrue();
    assertThat(json.at("/data/items/0/spark7").size()).isEqualTo(7);
    assertThat(json.at("/data/items/0/changeWeek").decimalValue()).isEqualByComparingTo("13.0");
    assertThat(json.at("/data/items/1/historyAvailable").asBoolean()).isFalse();
    assertThat(json.at("/data/items/1/changeWeek").isNull()).isTrue();
    assertThat(json.at("/data/items/1/spark7").size()).isZero();

    assertThat(get("/api/v1/markets/board?tab=ENGRAVING_BOOK&sort=fall&page=2").statusCode())
        .isEqualTo(200);
    verify(prices, times(2)).fetch(any(), any());
    ApiContractFixture.write("market-board.json", response.body());
  }

  @Test
  void 탭_정의는_외부_호출_없이_돌려준다() throws Exception {
    var response = get("/api/v1/markets/tabs");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.at("/data/tabs/0/tab").asText()).isEqualTo("REFINING");
    assertThat(json.at("/data/tabs/0/categoryCode").asInt()).isEqualTo(50010);
    assertThat(json.at("/data/tabs/0/defaultTier").asInt()).isEqualTo(4);
    assertThat(json.at("/data/sorts").toString()).contains("rise", "fall");
    verifyNoInteractions(prices, options, history);
    ApiContractFixture.write("market-tabs.json", response.body());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "tab=GEM",
        "tab=REFINING&sort=RISE",
        "tab=REFINING&sort=up",
        "tab=REFINING&page=0",
        "tab=REFINING&page=11",
        "tab=REFINING&tier=5"
      })
  void 잘못된_보드_조건은_조회_전에_400이다(String query) throws Exception {
    var response = get("/api/v1/markets/board?" + query);
    assertThat(response.statusCode()).as(query).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(prices);
  }

  private static MarketPrice item(int i) {
    return new MarketPrice(
        i,
        "품목" + i,
        "일반",
        null,
        1,
        null,
        BigDecimal.valueOf(100),
        null,
        BigDecimal.valueOf(100 + i));
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
