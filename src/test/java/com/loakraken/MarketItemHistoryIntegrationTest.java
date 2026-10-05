package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.market.application.MarketItemHistoryPort;
import com.loakraken.market.application.MarketOptionsPort;
import com.loakraken.market.application.MarketPricesPort;
import com.loakraken.market.domain.MarketItemHistory;
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
class MarketItemHistoryIntegrationTest {
  @LocalServerPort int port;
  @MockitoBean MarketItemHistoryPort history;
  @MockitoBean MarketPricesPort prices;
  @MockitoBean MarketOptionsPort options;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void clearCache() {
    var keys = redis.keys("market:v1:item-history:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 오늘_값은_부분_집계로_표시하고_요약은_완료일만_쓰며_캐시를_재사용한다() throws Exception {
    var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    days.add(new MarketItemHistory.DailyStat(today, new BigDecimal("175.5"), 8_817));
    for (int i = 1; i <= 13; i++)
      days.add(
          new MarketItemHistory.DailyStat(
              today.minusDays(i), new BigDecimal(i == 1 ? "172.6" : "165.4"), 200_000));
    when(history.fetchHistory(66102006L))
        .thenReturn(
            Optional.of(
                new MarketItemHistory(
                    66102006L,
                    List.of(new MarketItemHistory.Variant("운명의 파괴석", null, 100, days)))));

    var response = get("/api/v1/markets/items/66102006/history");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("MARKET_ITEM_HISTORY_SUCCESS");
    assertThat(json.at("/data/itemId").asLong()).isEqualTo(66102006L);
    assertThat(json.at("/data/variants/0/tradeRemainCount").isNull()).isTrue();
    assertThat(json.at("/data/variants/0/days").size()).isEqualTo(14);
    // 자정 경계에서 baseDate가 하루 넘어갈 수 있어 응답 기준일로 판단한다.
    var baseDate = LocalDate.parse(json.at("/data/baseDate").asText());
    for (var day : json.at("/data/variants/0/days"))
      assertThat(day.path("partial").asBoolean())
          .isEqualTo(!LocalDate.parse(day.path("date").asText()).isBefore(baseDate));
    if (baseDate.equals(today)) {
      assertThat(json.at("/data/variants/0/summary/latestAveragePrice").decimalValue())
          .isEqualByComparingTo("172.6");
      assertThat(json.at("/data/variants/0/summary/dayChangeRate").decimalValue())
          .isEqualByComparingTo("4.35");
      assertThat(json.at("/data/variants/0/summary/sevenDayDays").asInt()).isEqualTo(7);
    }
    assertThat(get("/api/v1/markets/items/66102006/history").body()).isEqualTo(response.body());
    verify(history, times(1)).fetchHistory(66102006L);
    verifyNoInteractions(prices, options);
    ApiContractFixture.write("market-item-history.json", response.body());
  }

  @Test
  void 공식_API가_모르는_상품은_404이고_캐시하지_않는다() throws Exception {
    when(history.fetchHistory(anyLong())).thenReturn(Optional.empty());
    var response = get("/api/v1/markets/items/1/history");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("MARKET_ITEM_NOT_FOUND");
    get("/api/v1/markets/items/1/history");
    verify(history, times(2)).fetchHistory(1L);
  }

  @Test
  void 외부_호출_제한은_공통_오류로_전달한다() throws Exception {
    when(history.fetchHistory(anyLong()))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED));
    var response = get("/api/v1/markets/items/2/history");
    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("LOST_ARK_RATE_LIMITED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "abc", "1000000000", "1.5"})
  void 잘못된_상품_ID는_조회_전에_400으로_거절한다(String itemId) throws Exception {
    var response = get("/api/v1/markets/items/" + itemId + "/history");
    assertThat(response.statusCode()).as(itemId).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
    verifyNoInteractions(history);
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
