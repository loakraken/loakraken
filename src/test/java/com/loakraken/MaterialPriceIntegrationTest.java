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
    // 없는 재료는 강화 재료·생활·전투 용품 세 분류에서 찾아보고 NOT_FOUND다.
    verify(prices, times(5)).fetch(eq(MarketSource.ACTIVE_LISTING), any());
    verify(prices)
        .fetch(
            any(),
            argThat(search -> search.itemName().equals("없는 재료") && search.categoryCode() == 60000));
    verify(prices)
        .fetch(
            any(),
            argThat(search -> search.itemName().equals("없는 재료") && search.categoryCode() == 50000));
    verify(prices)
        .fetch(
            any(),
            argThat(search -> search.itemName().equals("없는 재료") && search.categoryCode() == 90000));
    verifyNoInteractions(options);
    ApiContractFixture.write("material-prices.json", response.body());
  }

  /** 아비도스 제작 화면이 묻는 20개(완성품 2 + 생활 재료 18). */
  private static final List<String> CRAFT_NAMES =
      List.of(
          "아비도스 융화 재료",
          "상급 아비도스 융화 재료",
          "아비도스 목재",
          "부드러운 목재",
          "목재",
          "아비도스 철광석",
          "묵직한 철광석",
          "철광석",
          "아비도스 두툼한 생고기",
          "다듬은 생고기",
          "두툼한 생고기",
          "아비도스 태양 잉어",
          "붉은 살 생선",
          "생선",
          "아비도스 유물",
          "희귀한 유물",
          "고대 유물",
          "아비도스 들꽃",
          "수줍은 들꽃",
          "들꽃");

  @Test
  void 생활_재료도_생활_분류에서_정확한_이름으로_찾아_묶음_수량으로_나눈다() throws Exception {
    // 공식 거래소처럼 분류 안에서 이름 부분 일치·현재 최저가 오름차순·페이지당 10개로 답하는 가짜 목록.
    var catalog = new java.util.ArrayList<java.util.Map.Entry<Integer, MarketPrice>>();
    long id = 1;
    catalog.add(java.util.Map.entry(50000, price(id++, "아비도스 융화 재료", 1, "90", "88")));
    catalog.add(java.util.Map.entry(50000, price(id++, "상급 아비도스 융화 재료", 1, "130", "126")));
    for (var name : CRAFT_NAMES.subList(2, CRAFT_NAMES.size()))
      catalog.add(java.util.Map.entry(90000, price(id++, name, 100, "300", "285")));
    // 이름이 겹치는 더 싼 품목 10개: '고대 유물'은 2페이지에 있다.
    for (int i = 0; i < 10; i++)
      catalog.add(java.util.Map.entry(90000, price(id++, "고대 유물 꾸러미 " + i, 1, "1", "1")));
    when(prices.fetch(any(), any()))
        .thenAnswer(
            invocation -> {
              MarketSearch search = invocation.getArgument(1);
              var matched =
                  catalog.stream()
                      .filter(entry -> entry.getKey() == search.categoryCode())
                      .map(java.util.Map.Entry::getValue)
                      .filter(item -> item.name().contains(search.itemName()))
                      .sorted(java.util.Comparator.comparing(MarketPrice::currentMinPrice))
                      .toList();
              int from = (search.pageNo() - 1) * 10;
              var items =
                  matched.subList(
                      Math.min(from, matched.size()), Math.min(from + 10, matched.size()));
              return new MarketPage(search.pageNo(), 10, matched.size(), items);
            });
    var body =
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"names\":["
            + String.join(",", CRAFT_NAMES.stream().map(name -> "\"" + name + "\"").toList())
            + "]}";

    var json = mapper.readTree(send(body, "application/json").body());
    var items = json.at("/data/items");
    assertThat(items.size()).isEqualTo(20);
    for (var item : items) {
      assertThat(item.path("status").asText()).as(item.path("name").asText()).isEqualTo("PRICED");
      assertThat(item.path("sourceItemName").asText()).isEqualTo(item.path("name").asText());
    }
    // 융화 재료는 강화 재료 분류에서 1개 묶음, 생활 재료는 100개 묶음 285골드 → 1개당 2.85.
    assertThat(items.at("/0/unitPriceGold").decimalValue()).isEqualByComparingTo("88");
    assertThat(items.at("/4/name").asText()).isEqualTo("목재");
    assertThat(items.at("/4/bundleCount").asInt()).isEqualTo(100);
    assertThat(items.at("/4/unitPriceGold").decimalValue()).isEqualByComparingTo("2.85");
    assertThat(items.at("/16/name").asText()).isEqualTo("고대 유물");
    assertThat(items.at("/16/unitPriceGold").decimalValue()).isEqualByComparingTo("2.85");

    // 한 번 찾은 이름은 그 분류부터 찾는다: 캐시를 비운 두 번째 요청은 이름마다 1번(고대 유물만 2페이지라 2번) = 21번.
    clearCache();
    clearInvocations(prices);
    var again = mapper.readTree(send(body, "application/json").body());
    assertThat(again.at("/data/items/5/status").asText()).isEqualTo("PRICED");
    verify(prices, times(21)).fetch(eq(MarketSource.ACTIVE_LISTING), any());
    verify(prices, never())
        .fetch(
            any(),
            argThat(search -> search.itemName().equals("목재") && search.categoryCode() == 50000));
  }

  @Test
  void 캐시가_빈_상태에서_같은_요청_5건이_동시에_와도_같은_검색은_한_번만_공식_조회하고_모두_답한다() throws Exception {
    var catalog = new java.util.ArrayList<java.util.Map.Entry<Integer, MarketPrice>>();
    long id = 100;
    catalog.add(java.util.Map.entry(50000, price(id++, "아비도스 융화 재료", 1, "90", "88")));
    catalog.add(java.util.Map.entry(50000, price(id++, "상급 아비도스 융화 재료", 1, "130", "126")));
    for (var name : CRAFT_NAMES.subList(2, CRAFT_NAMES.size()))
      catalog.add(java.util.Map.entry(90000, price(id++, name, 100, "300", "285")));
    // 공식 응답이 느린 상황(동시 요청이 겹치게).
    when(prices.fetch(any(), any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(40);
              MarketSearch search = invocation.getArgument(1);
              var matched =
                  catalog.stream()
                      .filter(entry -> entry.getKey() == search.categoryCode())
                      .map(java.util.Map.Entry::getValue)
                      .filter(item -> item.name().contains(search.itemName()))
                      .toList();
              int from = (search.pageNo() - 1) * 10;
              return new MarketPage(
                  search.pageNo(),
                  10,
                  matched.size(),
                  matched.subList(
                      Math.min(from, matched.size()), Math.min(from + 10, matched.size())));
            });
    var body =
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"names\":["
            + String.join(",", CRAFT_NAMES.stream().map(name -> "\"" + name + "\"").toList())
            + "]}";
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(5)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      for (int i = 0; i < 5; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return send(body, "application/json");
                }));
      start.countDown();
      for (var future : futures) {
        var response = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        mapper
            .readTree(response.body())
            .at("/data/items")
            .forEach(item -> assertThat(item.path("status").asText()).isEqualTo("PRICED"));
      }
    }
    // 같은 검색(분류·이름·페이지)은 공식 조회가 한 번뿐이다(5건이 겹쳐도 1건 분량).
    var searches =
        org.mockito.Mockito.mockingDetails(prices).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("fetch"))
            .map(invocation -> (MarketSearch) invocation.getArgument(1))
            .toList();
    assertThat(searches).isNotEmpty().doesNotHaveDuplicates();
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

  private MarketPrice price(long id, String name, int bundle, String recent, String currentMin) {
    return new MarketPrice(
        id,
        name,
        null,
        null,
        bundle,
        null,
        null,
        new BigDecimal(recent),
        new BigDecimal(currentMin));
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
