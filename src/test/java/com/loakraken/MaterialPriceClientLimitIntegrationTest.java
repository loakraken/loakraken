package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.LifeMaterialScan;
import com.loakraken.market.application.MarketCache;
import com.loakraken.market.application.RefiningMaterialScan;
import com.loakraken.market.domain.MarketPrice;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재료 시세는 사람 클라이언트(내부 주소 면제 끔)도 429 없이 답한다. 예열 캐시에 있으면 공식 호출 0이고, 없으면 요청 1번을 재료 시세 버킷에 세고 안쪽 검색은 공식
 * 호출마다 클라이언트 재료 검색 버킷(분당 30, 공식 호출 한도 20과 따로)에 센다 — 넘은 이름부터 지금 알 수 없음(보안 점검 10-18). 공식 API는 가짜
 * 서버(분류 안 이름 부분 일치·10개씩)다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "lostark.client-limit.exempt-internal=false",
      "lostark.client-limit.official-per-minute=20",
      "lostark.client-limit.material-prices-per-minute=3"
    })
class MaterialPriceClientLimitIntegrationTest {
  private static final JsonMapper MAPPER = new JsonMapper();
  private static final AtomicInteger calls = new AtomicInteger();

  /** 공식 검색 기록(분류|이름|페이지). */
  private static final java.util.Queue<String> searched =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /** 아비도스 제작 화면 20개(완성품 2 + 생활 재료 18). */
  static final List<String> CRAFT =
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

  /** 생명의 기운 효율 화면이 더 묻는 5개(제작 20개와 합쳐 25개). */
  static final List<String> LIFE_EXTRA = List.of("튼튼한 목재", "단단한 철광석", "오레하 유물", "화사한 들꽃", "자연산 진주");

  // 위 이름 목록 뒤에 만든다(정적 초기화 순서).
  private static final List<Object[]> CATALOG = catalog();
  private static final HttpServer server = server();

  @LocalServerPort int port;
  @Autowired MarketCache cache;
  @Autowired StringRedisTemplate redis;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("lostark.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    properties.add("lostark.api-key", () -> "local-test-token");
    properties.add("lostark.requests-per-minute", () -> "600");
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @BeforeEach
  void 정리() {
    var keys = new ArrayList<String>();
    for (var pattern : List.of("market:v1:*", "lostark:*"))
      if (redis.keys(pattern) != null) keys.addAll(redis.keys(pattern));
    if (!keys.isEmpty()) redis.delete(keys);
    calls.set(0);
    searched.clear();
  }

  @Test
  void 예열_캐시에_있으면_20개와_25개_모두_공식_호출_없이_답하고_재료_시세_한도도_쓰지_않는다() throws Exception {
    var now = Instant.now();
    var refining = new ArrayList<MarketPrice>();
    var life = new ArrayList<MarketPrice>();
    for (var row : CATALOG) {
      var price = (MarketPrice) row[1];
      if ((int) row[0] == 50000) refining.add(price);
      else life.add(price);
    }
    cache.saveRefiningMaterials(new RefiningMaterialScan(now, refining), Duration.ofMinutes(30));
    cache.saveLifeMaterials(90000, new LifeMaterialScan(now, life), Duration.ofMinutes(30));

    // 재료 시세 버킷(시험 3)보다 많이 불러도 캐시에서 답하므로 429가 없다.
    for (int i = 0; i < 5; i++) {
      var items = priced(post(CRAFT, "198.51.100.30"));
      assertThat(items).hasSize(20);
    }
    var rest = new ArrayList<>(CRAFT.subList(5, 20));
    rest.addAll(LIFE_EXTRA);
    assertThat(priced(post(rest, "198.51.100.30"))).hasSize(20);
    assertThat(priced(post(CRAFT.subList(0, 5), "198.51.100.30"))).hasSize(5);
    assertThat(calls).hasValue(0);
  }

  @Test
  void 예열_전에는_공식_검색을_클라이언트_재료_검색_버킷에_세고_넘은_이름부터_지금_알_수_없음이며_429가_없다() throws Exception {
    // 한도는 실제 시각 기준 1분 고정 창이다. 네 요청이 한 창 안에 들어야 네 번째가 429다(분 경계를 넘으면 새 창에서 허용).
    awaitMinuteWithAtLeast(Duration.ofSeconds(40));
    // 첫 요청: 생활 18개 × 분류 2 + 2 ≈ 38번 검색이 필요하지만 재료 검색 버킷(분당 30)까지만 공식 검색, 그 뒤 이름은 지금 알 수 없음(429 아님).
    var first = items(post(CRAFT, "203.0.113.40"));
    assertThat(first).hasSize(20);
    assertThat(calls).hasValue(30);
    assertThat(status(first, "PRICED")).isPositive();
    assertThat(status(first, "PRICE_UNAVAILABLE")).isPositive();
    assertThat(status(first, "PRICED") + status(first, "PRICE_UNAVAILABLE")).isEqualTo(20);
    assertThat(first.get(4).path("unitPriceGold").decimalValue()).isEqualByComparingTo("2.85");

    // 같은 분 두 번째(검색 캐시 비움): 재료 검색 버킷을 다 써서 공식 검색 없이 모두 지금 알 수 없음, 429 아님.
    redis.delete(redis.keys("market:v1:*"));
    calls.set(0);
    var second = items(post(CRAFT, "203.0.113.40"));
    assertThat(status(second, "PRICE_UNAVAILABLE")).isEqualTo(20);
    assertThat(calls).hasValue(0);

    // 생명의 기운 효율 25개(20 + 5): 세 번째 요청까지 재료 시세 한도(시험 3) 안.
    redis.delete(redis.keys("market:v1:*"));
    var rest = new ArrayList<>(CRAFT.subList(5, 20));
    rest.addAll(LIFE_EXTRA);
    assertThat(items(post(rest, "203.0.113.40"))).hasSize(20);
    // 네 번째 캐시 미스 요청은 재료 시세 한도로 429다(공식 검색 전에 멈춤).
    redis.delete(redis.keys("market:v1:*"));
    calls.set(0);
    var limited = post(CRAFT.subList(0, 5), "203.0.113.40");
    assertThat(limited.statusCode()).isEqualTo(429);
    assertThat(calls).hasValue(0);
  }

  private List<JsonNode> items(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var items = new ArrayList<JsonNode>();
    MAPPER.readTree(response.body()).at("/data/items").forEach(items::add);
    return items;
  }

  private static long status(List<JsonNode> items, String status) {
    return items.stream().filter(item -> status.equals(item.path("status").asText())).count();
  }

  /** 지금 분(UTC 1분 창)에 남은 시간이 needed보다 짧으면 다음 분이 시작될 때까지 기다린다. */
  private static void awaitMinuteWithAtLeast(Duration needed) throws InterruptedException {
    long minute = Duration.ofMinutes(1).toMillis();
    long remaining = minute - System.currentTimeMillis() % minute;
    if (remaining < needed.toMillis()) Thread.sleep(remaining + 50);
  }

  /** 로아샵 화면이 묻는 15개(재련 5 + 배틀 아이템 5 + 페온 + 거래 불가 4). */
  static final List<String> SHOP =
      List.of(
          "운명의 파괴석 결정",
          "운명의 수호석 결정",
          "상급 아비도스 융화 재료",
          "용암의 숨결",
          "빙하의 숨결",
          "빛나는 정령의 회복약",
          "빛나는 암흑 수류탄",
          "빛나는 성스러운 폭탄",
          "아드로핀 물약",
          "각성 물약",
          "고급 생명의 기운 회복물약",
          "중급 생명의 기운 회복물약",
          "환희의 전설 카드 팩",
          "환희의 전설~영웅 카드 팩",
          "페온");

  @Test
  void 예열이_있으면_로아샵_첫_열기는_공식_호출_10회_이하이고_두_번째는_0회다() throws Exception {
    var now = Instant.now();
    var refining = new ArrayList<MarketPrice>();
    var life = new ArrayList<MarketPrice>();
    var battle = new ArrayList<MarketPrice>();
    for (var row : CATALOG) {
      var price = (MarketPrice) row[1];
      int category = (int) row[0];
      if (category == 90000) life.add(price);
      else if (category == 60000) battle.add(price);
      else if (!price.name().equals("페온")) refining.add(price);
    }
    cache.saveRefiningMaterials(new RefiningMaterialScan(now, refining), Duration.ofMinutes(30));
    cache.saveLifeMaterials(
        90000, new LifeMaterialScan(now, life, List.of(90000)), Duration.ofMinutes(30));
    cache.saveLifeMaterials(
        60000, new LifeMaterialScan(now, battle, List.of(60000)), Duration.ofMinutes(30));

    var first = MAPPER.readTree(post(SHOP, "198.51.100.60").body()).at("/data/items");
    var statuses = new java.util.LinkedHashMap<String, String>();
    first.forEach(item -> statuses.put(item.path("name").asText(), item.path("status").asText()));
    for (var name : SHOP.subList(0, 10)) assertThat(statuses).containsEntry(name, "PRICED");
    for (var name : SHOP.subList(10, 14)) assertThat(statuses).containsEntry(name, "UNTRADEABLE");
    assertThat(statuses).containsEntry("페온", "PRICED");
    // 페온만 검색(강화 재료 1번). 목표 10회 이하.
    assertThat(calls.get()).isLessThanOrEqualTo(10).isEqualTo(1);

    // 두 번째(캐시 유효 동안): 공식 호출 0.
    calls.set(0);
    var second = MAPPER.readTree(post(SHOP, "198.51.100.60").body()).at("/data/items");
    assertThat(second.size()).isEqualTo(15);
    assertThat(calls).hasValue(0);
  }

  @Test
  void 예열_전_캐시가_빈_상태에서_로아샵_5건이_동시에_와도_같은_검색은_한_번이고_429가_없다() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    var responses = new ArrayList<HttpResponse<String>>();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(5)) {
      var futures = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      for (int i = 0; i < 5; i++) {
        // 서로 다른 사람(주소)이 같은 화면을 동시에 연다.
        var address = "192.0.2." + (90 + i);
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return post(SHOP, address);
                }));
      }
      start.countDown();
      for (var future : futures)
        responses.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
    }
    for (var response : responses) {
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var statuses = new java.util.HashMap<String, String>();
      MAPPER
          .readTree(response.body())
          .at("/data/items")
          .forEach(item -> statuses.put(item.path("name").asText(), item.path("status").asText()));
      for (var name : SHOP.subList(0, 10)) assertThat(statuses).containsEntry(name, "PRICED");
      assertThat(statuses).containsEntry("페온", "PRICED");
      for (var name : SHOP.subList(10, 14)) assertThat(statuses).containsEntry(name, "UNTRADEABLE");
    }
    // 같은 검색(분류·이름·페이지)은 한 번만: 5건이 겹쳐도 한 요청 분량(재련 5 + 배틀 5 × 3분류 + 페온 1 = 21) 이하.
    assertThat(searched).doesNotHaveDuplicates();
    assertThat(calls.get()).isLessThanOrEqualTo(21);
  }

  private List<JsonNode> priced(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var items = new ArrayList<JsonNode>();
    MAPPER.readTree(response.body()).at("/data/items").forEach(items::add);
    for (var item : items)
      assertThat(item.path("status").asText()).as(item.path("name").asText()).isEqualTo("PRICED");
    return items;
  }

  private HttpResponse<String> post(List<String> names, String clientAddress) throws Exception {
    var body =
        "{\"priceBasis\":\"CURRENT_MIN_PRICE\",\"names\":["
            + String.join(",", names.stream().map(name -> "\"" + name + "\"").toList())
            + "]}";
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create("http://localhost:" + port + "/api/v1/markets/material-prices"))
              .header("Content-Type", "application/json")
              .header("X-Forwarded-For", clientAddress)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  /** [분류 코드, 상품]. 융화 재료는 강화 재료, 나머지는 생활(100개 묶음 285골드). */
  private static List<Object[]> catalog() {
    var rows = new ArrayList<Object[]>();
    long id = 1;
    rows.add(new Object[] {50000, price(id++, "아비도스 융화 재료", 1, "88")});
    rows.add(new Object[] {50000, price(id++, "상급 아비도스 융화 재료", 1, "126")});
    for (var name : CRAFT.subList(2, CRAFT.size()))
      rows.add(new Object[] {90000, price(id++, name, 100, "285")});
    for (var name : LIFE_EXTRA) rows.add(new Object[] {90000, price(id++, name, 100, "150")});
    for (var name : List.of("운명의 파괴석 결정", "운명의 수호석 결정", "용암의 숨결", "빙하의 숨결", "페온"))
      rows.add(new Object[] {50000, price(id++, name, 1, "100")});
    for (var name : List.of("빛나는 정령의 회복약", "빛나는 암흑 수류탄", "빛나는 성스러운 폭탄", "아드로핀 물약", "각성 물약"))
      rows.add(new Object[] {60000, price(id++, name, 1, "30")});
    return rows;
  }

  private static MarketPrice price(long id, String name, int bundle, String min) {
    return new MarketPrice(
        id, name, "일반", null, bundle, null, null, new BigDecimal(min), new BigDecimal(min));
  }

  private static HttpServer server() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/markets/items",
          exchange -> {
            calls.incrementAndGet();
            var request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            int category = request.path("CategoryCode").asInt();
            String name = request.path("ItemName").asText("");
            int page = request.path("PageNo").asInt(1);
            searched.add(category + "|" + name + "|" + page);
            try {
              // 공식 응답이 느린 상황(동시 요청이 겹치게).
              Thread.sleep(20);
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
            }
            var matched =
                CATALOG.stream()
                    .filter(row -> (int) row[0] == category)
                    .map(row -> (MarketPrice) row[1])
                    .filter(item -> item.name().contains(name))
                    .toList();
            var items = new StringBuilder();
            for (int i = (page - 1) * 10; i < Math.min(page * 10, matched.size()); i++) {
              var item = matched.get(i);
              if (!items.isEmpty()) items.append(',');
              items.append(
                  "{\"Id\":%d,\"Name\":\"%s\",\"Grade\":\"일반\",\"BundleCount\":%d,\"RecentPrice\":%s,\"CurrentMinPrice\":%s}"
                      .formatted(
                          item.id(),
                          item.name(),
                          item.bundleCount(),
                          item.recentPrice().toPlainString(),
                          item.currentMinPrice().toPlainString()));
            }
            var json =
                "{\"PageNo\":%d,\"PageSize\":10,\"TotalCount\":%d,\"Items\":[%s]}"
                    .formatted(page, matched.size(), items);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return server;
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }
}
