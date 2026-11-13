package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketHistoryRangeService;
import com.loakraken.market.application.MarketItemHistoryPort;
import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** 시세 기간 조회: 저장 표 읽기(범위·처음 저장한 날)와 거래소·보석·장신구 기간 API(range 검증, 공식 14일 + 저장 완료일). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketRangeHistoryIntegrationTest {
  private static final long ITEM = 990151L;
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  @LocalServerPort int port;
  @MockitoBean MarketItemHistoryPort history;

  // 공식 상세를 받을 때 일별 표에 쓰는 비동기 기록을 막아, 시험이 넣은 행만 읽게 한다(기록 경합 없음).
  @MockitoBean com.loakraken.market.application.MarketDailyRecorder recorder;
  @Autowired MarketDailyStore store;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  private final JsonMapper mapper = new JsonMapper();
  private final LocalDate today = LocalDate.now(KST);

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM market_price_daily WHERE item_id BETWEEN 990150 AND 990159").update();
    jdbc.sql("DELETE FROM auction_price_daily WHERE cell_key IN (:keys)")
        .param("keys", List.of(gemKey(), accessoryKey()))
        .update();
    var keys = redis.keys("market:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 저장_표는_기간_안의_완료일을_최신순으로_주고_변형마다_처음_저장한_날을_준다() {
    exchangeRow(-1, 1, today.minusDays(40), "5", 1);
    exchangeRow(-1, 1, today.minusDays(20), "8", 4);
    exchangeRow(-1, 1, today.minusDays(2), "9", 6);
    exchangeRow(3, 1, today.minusDays(25), "1", 1);

    var range = store.findRange(ITEM, today.minusDays(29));

    assertThat(range.days())
        .extracting(day -> day.variantKey() + "/" + day.date())
        .containsExactly(
            "-1/1/" + today.minusDays(2),
            "-1/1/" + today.minusDays(20),
            "3/1/" + today.minusDays(25));
    assertThat(range.firstDates())
        .containsEntry("-1/1", today.minusDays(40))
        .containsEntry("3/1", today.minusDays(25));
  }

  @Test
  void 거래소_기간_API는_공식_14일에_저장_완료일을_더하고_잘못된_range는_400이다() throws Exception {
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    for (int i = 0; i < 14; i++)
      days.add(new MarketItemHistory.DailyStat(today.minusDays(i), BigDecimal.TEN, 5));
    when(history.fetchHistory(anyLong()))
        .thenReturn(
            Optional.of(
                new MarketItemHistory(
                    ITEM, List.of(new MarketItemHistory.Variant("품목", null, 1, days)))));
    exchangeRow(-1, 1, today.minusDays(20), "8", 4);

    var response = get("/api/v1/markets/items/" + ITEM + "/history?range=30d");
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("range").asText()).isEqualTo("30d");
    var variant = data.path("variants").get(0);
    assertThat(variant.path("days").size()).isEqualTo(15);
    assertThat(variant.path("storedDays").asInt()).isEqualTo(1);
    assertThat(variant.path("recordedSince").asText()).isEqualTo(today.minusDays(20).toString());

    // 생략 = 14d(지금 응답), range를 늘 돌려준다.
    var plain = mapper.readTree(get("/api/v1/markets/items/" + ITEM + "/history").body());
    assertThat(plain.path("data").path("range").asText()).isEqualTo("14d");
    assertThat(plain.path("data").path("variants").get(0).path("days").size()).isEqualTo(14);

    assertThat(get("/api/v1/markets/items/" + ITEM + "/history?range=8d").statusCode())
        .isEqualTo(400);
  }

  @Test
  void 보석_장신구_칸_기간_API는_저장한_날만_최신순으로_주고_오늘은_partial이다() throws Exception {
    auctionRow(gemKey(), today, 1_200_000L, 7);
    auctionRow(gemKey(), today.minusDays(3), 1_100_000L, 9);
    auctionRow(gemKey(), today.minusDays(60), 900_000L, 2);
    auctionRow(accessoryKey(), today.minusDays(1), 50_000L, 3);

    var gems =
        mapper.readTree(
            get("/api/v1/markets/auctions/gems/history?kind=%EA%B2%81%ED%99%94&level=10&range=30d")
                .body());
    var data = gems.path("data");
    assertThat(data.path("range").asText()).isEqualTo("30d");
    assertThat(data.path("storedDays").asInt()).isEqualTo(2);
    assertThat(data.path("recordedSince").asText()).isEqualTo(today.minusDays(60).toString());
    assertThat(data.path("days").get(0).path("date").asText()).isEqualTo(today.toString());
    assertThat(data.path("days").get(0).path("partial").asBoolean()).isTrue();
    assertThat(data.path("days").get(1).path("minBuyPrice").asLong()).isEqualTo(1_100_000L);
    assertThat(data.path("days").get(1).path("partial").asBoolean()).isFalse();

    var accessories =
        mapper.readTree(
            get("/api/v1/markets/auctions/accessories/history?slot=NECKLACE&levels=HIGH,MID&range=7d")
                .body());
    assertThat(accessories.path("data").path("storedDays").asInt()).isEqualTo(1);

    // 표에 없는 조합(중하)·잘못된 range는 400
    assertThat(
            get("/api/v1/markets/auctions/accessories/history?slot=NECKLACE&levels=MID,LOW")
                .statusCode())
        .isEqualTo(400);
    assertThat(
            get("/api/v1/markets/auctions/gems/history?kind=%EA%B2%81%ED%99%94&level=10&range=1y")
                .statusCode())
        .isEqualTo(400);
  }

  private static String gemKey() {
    return MarketHistoryRangeService.gemCellKey("겁화", 10);
  }

  /** 표 기본값(고대·딜러·품질 70) 목걸이 상중. */
  private static String accessoryKey() {
    return MarketHistoryRangeService.accessoryCellKey(
        com.loakraken.market.domain.AuctionCatalog.Grade.ANCIENT,
        com.loakraken.market.domain.AuctionCatalog.Role.DEALER,
        70,
        com.loakraken.market.domain.AuctionCatalog.Slot.NECKLACE,
        List.of(
            com.loakraken.market.domain.AuctionCatalog.Level.HIGH,
            com.loakraken.market.domain.AuctionCatalog.Level.MID));
  }

  private void exchangeRow(int remain, int bundle, LocalDate day, String avg, long trades) {
    jdbc.sql(
            """
            INSERT INTO market_price_daily (item_id, trade_remain_key, bundle_key, kst_date, item_name,
              avg_price, trade_count, observed_at)
            VALUES (:id, :remain, :bundle, :day, '품목', :avg, :trades, :at)
            """)
        .param("id", ITEM)
        .param("remain", remain)
        .param("bundle", bundle)
        .param("day", day)
        .param("avg", new BigDecimal(avg))
        .param("trades", trades)
        .param("at", OffsetDateTime.now(ZoneOffset.UTC))
        .update();
  }

  private void auctionRow(String key, LocalDate day, long price, long count) {
    jdbc.sql(
            """
            INSERT INTO auction_price_daily (cell_key, kst_date, min_buy_price, total_count, observed_at)
            VALUES (:key, :day, :price, :count, :at)
            """)
        .param("key", key)
        .param("day", day)
        .param("price", price)
        .param("count", count)
        .param("at", OffsetDateTime.now(ZoneOffset.UTC))
        .update();
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
