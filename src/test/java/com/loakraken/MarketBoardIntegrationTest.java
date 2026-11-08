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
    // 이름 검색(#619)은 같은 스캔 캐시(탭+티어)를 거른다 — 공식 추가 호출 0.
    var named =
        mapper.readTree(
            get("/api/v1/markets/board?tab=ENGRAVING_BOOK&q="
                    + java.net.URLEncoder.encode(" 품목1 ", java.nio.charset.StandardCharsets.UTF_8))
                .body());
    assertThat(named.at("/data/totalItems").asInt()).isEqualTo(5);
    assertThat(named.at("/data/scannedItems").asInt()).isEqualTo(13);
    assertThat(named.at("/data/complete").asBoolean()).isTrue();
    assertThat(named.at("/data/items/0/itemId").asLong()).isEqualTo(1L);
    var initials =
        mapper.readTree(
            get("/api/v1/markets/board?tab=ENGRAVING_BOOK&sort=high&q="
                    + java.net.URLEncoder.encode("ㅍㅁ", java.nio.charset.StandardCharsets.UTF_8))
                .body());
    assertThat(initials.at("/data/totalItems").asInt()).isEqualTo(13);
    assertThat(initials.at("/data/items/0/itemId").asLong()).isEqualTo(13L);
    verify(prices, times(2)).fetch(any(), any());
    ApiContractFixture.write("market-board.json", response.body());
  }

  /** 즐겨찾기 묶음 시세: 보드와 같은 탭 스캔에서 찾고, 같은 스캔 캐시를 다시 쓴다(공식 추가 호출 없음). 요청 순서·중복 한 번·없는 품목 NOT_FOUND. */
  @Test
  void 즐겨찾기_묶음_시세는_보드_탭_스캔에서_찾고_스캔을_다시_쓴다() throws Exception {
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
    var body =
        """
        {"items": [
          {"itemId": 13, "tab": "ENGRAVING_BOOK"},
          {"itemId": 999, "tab": "ENGRAVING_BOOK"},
          {"itemId": 2, "tab": "ENGRAVING_BOOK"},
          {"itemId": 13, "tab": "ENGRAVING_BOOK"}
        ]}
        """;
    var response = post("/api/v1/markets/items/prices", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    ApiContractFixture.write("market-item-prices.json", response.body());
    var json = mapper.readTree(response.body());
    assertThat(json.path("code").asText()).isEqualTo("MARKET_ITEM_PRICES_SUCCESS");
    var items = json.at("/data/items");
    assertThat(items.size()).isEqualTo(3);
    assertThat(items.at("/0/itemId").asLong()).isEqualTo(13L);
    assertThat(items.at("/0/status").asText()).isEqualTo("READY");
    assertThat(items.at("/0/name").asText()).isEqualTo("품목13");
    assertThat(items.at("/0/changeDay").decimalValue()).isEqualByComparingTo("13.0");
    assertThat(items.at("/0/observedAt").isNull()).isFalse();
    assertThat(items.at("/1/itemId").asLong()).isEqualTo(999L);
    assertThat(items.at("/1/status").asText()).isEqualTo("NOT_FOUND");
    assertThat(items.at("/1/currentMinPrice").isNull()).isTrue();
    assertThat(items.at("/2/itemId").asLong()).isEqualTo(2L);
    assertThat(items.at("/2/status").asText()).isEqualTo("READY");
    // 13개 탭 = 2페이지. 두 번째 요청과 상승 보드는 같은 스캔 캐시를 쓴다.
    verify(prices, times(2)).fetch(any(), any());
    assertThat(post("/api/v1/markets/items/prices", body).statusCode()).isEqualTo(200);
    assertThat(get("/api/v1/markets/board?tab=ENGRAVING_BOOK&sort=rise").statusCode())
        .isEqualTo(200);
    verify(prices, times(2)).fetch(any(), any());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"items\": []}",
        "{\"items\": [{\"itemId\": 1}]}",
        "{\"items\": [{\"itemId\": 0, \"tab\": \"REFINING\"}]}",
        "{\"items\": [{\"itemId\": 1, \"tab\": \"REFINING\", \"tier\": 5}]}",
        // 티어가 없는 탭에 티어를 붙이면 스캔 키만 늘어나므로 막는다(웹은 재련 두 탭에서만 티어를 보냄).
        "{\"items\": [{\"itemId\": 1, \"tab\": \"FOOD\", \"tier\": 4}]}",
        "{\"items\": [{\"itemId\": 1, \"tab\": \"ARK_GRID\", \"tier\": 1}]}",
        "{\"items\": [{\"itemId\": 1, \"tab\": \"없는탭\"}]}"
      })
  void 잘못된_즐겨찾기_요청은_조회_전에_400이다(String body) throws Exception {
    assertThat(post("/api/v1/markets/items/prices", body).statusCode()).isEqualTo(400);
    var many = new StringBuilder("{\"items\": [");
    for (int i = 1; i <= 51; i++)
      many.append(i > 1 ? "," : "")
          .append("{\"itemId\": ")
          .append(i)
          .append(", \"tab\": \"REFINING\"}");
    assertThat(post("/api/v1/markets/items/prices", many.append("]}").toString()).statusCode())
        .isEqualTo(400);
    verifyNoInteractions(prices);
  }

  @Autowired com.loakraken.market.application.MarketCache marketCache;
  @Autowired org.springframework.context.ApplicationContext context;

  @Test
  void 거래량_정렬은_거래량_지도로_탭_전체를_정렬하고_행에_거래량과_날짜를_싣는다() throws Exception {
    when(prices.fetch(any(), any()))
        .thenAnswer(
            invocation -> {
              MarketSearch search = invocation.getArgument(1);
              return new MarketPage(
                  1,
                  10,
                  4,
                  IntStream.rangeClosed(1, 4).mapToObj(MarketBoardIntegrationTest::item).toList());
            });
    var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    var volumeDate = today.minusDays(1);
    marketCache.saveVolumes(
        today,
        List.of(
            new com.loakraken.market.domain.MarketVolume(3, volumeDate, 12, 80),
            new com.loakraken.market.domain.MarketVolume(1, volumeDate, 40, 80),
            new com.loakraken.market.domain.MarketVolume(4, volumeDate, 0, 5)));
    // 거래량 지도는 Valkey 해시 하나, TTL 36시간 이하(DB 저장 없음).
    long ttl = redis.getExpire("market:v1:volume:" + today, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(ttl).isPositive().isLessThanOrEqualTo(36 * 3600);
    assertThat(marketCache.addVolumeCalls(today, 3)).isEqualTo(3);
    assertThat(marketCache.addVolumeCalls(today, 2)).isEqualTo(5);

    var response = get("/api/v1/markets/board?tab=FOOD&sort=volume");
    assertThat(response.statusCode()).isEqualTo(200);
    var json = mapper.readTree(response.body());
    assertThat(json.at("/data/sort").asText()).isEqualTo("volume");
    assertThat(json.at("/data/scannedItems").asInt()).isEqualTo(4);
    var order = new ArrayList<Long>();
    for (var node : json.at("/data/items")) order.add(node.path("itemId").asLong());
    assertThat(order).containsExactly(1L, 3L, 4L, 2L);
    assertThat(json.at("/data/items/0/tradesYesterday").asLong()).isEqualTo(40);
    assertThat(json.at("/data/items/0/trades7d").asLong()).isEqualTo(80);
    assertThat(json.at("/data/items/0/volumeDate").asText()).isEqualTo(volumeDate.toString());
    assertThat(json.at("/data/items/3/tradesYesterday").isNull()).isTrue();
    assertThat(json.at("/data/items/3/trades7d").isNull()).isTrue();
    assertThat(json.at("/data/items/3/volumeDate").isNull()).isTrue();

    // 다른 정렬도 같은 거래량 칸을 싣는다(같은 스캔 재사용, 공식 추가 호출 없음).
    var rise = mapper.readTree(get("/api/v1/markets/board?tab=FOOD&sort=rise").body());
    assertThat(rise.at("/data/items/0/itemId").asLong()).isEqualTo(4L);
    assertThat(rise.at("/data/items/0/trades7d").asLong()).isEqualTo(5);
    verify(prices, times(1)).fetch(any(), any());

    // 채우기 작업은 기본 꺼짐이라 빈이 없다.
    assertThat(context.containsBean("marketVolumeWarmup")).isFalse();
    assertThat(mapper.readTree(get("/api/v1/markets/tabs").body()).at("/data/sorts").toString())
        .contains("volume");
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
    // #11: 아바타·장비 상자(목표 사이트 실관찰, 공식 분류 코드). 티어 조건 없음.
    var added = new java.util.HashMap<String, tools.jackson.databind.JsonNode>();
    json.at("/data/tabs").forEach(tab -> added.put(tab.path("tab").asText(), tab));
    assertThat(added.get("AVATAR").path("label").asText()).isEqualTo("아바타");
    assertThat(added.get("AVATAR").path("categoryCode").asInt()).isEqualTo(20000);
    assertThat(added.get("AVATAR").path("defaultTier").isNull()).isTrue();
    assertThat(added.get("EQUIPMENT_BOX").path("label").asText()).isEqualTo("장비 상자");
    assertThat(added.get("EQUIPMENT_BOX").path("categoryCode").asInt()).isEqualTo(10100);
    assertThat(added.get("EQUIPMENT_BOX").path("defaultTier").isNull()).isTrue();
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
        "tab=REFINING&tier=5",
        "tab=REFINING&q=aaaaaaaaaaaaaaaaaaaaa",
        "tab=REFINING&q=a%3Cb",
        "tab=REFINING&q=%25"
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

  private HttpResponse<String> post(String path, String body) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
