package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.*;
import com.loakraken.market.application.*;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class LostArkMarketClientTest {
  private final JsonMapper mapper = new JsonMapper();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final LostArkMetrics metrics = new LostArkMetrics(registry);
  private final AtomicInteger calls = new AtomicInteger();
  private HttpServer server;
  private ExecutorService executor;
  private volatile String path, method, auth, requestBody;
  private volatile int status = 200;
  private volatile long delay;
  private volatile String body =
      """
      {"PageNo":1,"PageSize":10,"TotalCount":1,"Items":[
       {"Id":123,"Name":"검증 재료","Grade":"일반","Icon":"https://example.com/item.png",
        "BundleCount":100,"TradeRemainCount":null,"YDayAvgPrice":12.34,"RecentPrice":0,
        "CurrentMinPrice":13,"FutureField":"ignored"}]}
      """;
  private final MarketSearch search =
      new MarketSearch(
          50000,
          "",
          4,
          "",
          "검증 재료",
          1,
          MarketSearch.Sort.RECENT_PRICE,
          MarketSearch.SortCondition.ASC);

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          path = exchange.getRequestURI().getPath();
          method = exchange.getRequestMethod();
          auth = exchange.getRequestHeaders().getFirst("Authorization");
          requestBody =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          try {
            if (delay > 0) Thread.sleep(delay);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    executor.close();
    for (var meter : registry.getMeters()) {
      assertThat(meter.getId().toString())
          .doesNotContain("검증 재료", "test-token", "http", "/markets/");
    }
  }

  @Test
  void 공식_필드와_인증을_POST로_전송하고_가격과_묶음_수량을_보존한다() {
    var result =
        client("test-token", Duration.ofSeconds(2)).fetch(MarketSource.ACTIVE_LISTING, search);
    assertThat(path).isEqualTo("/markets/items");
    assertThat(method).isEqualTo("POST");
    assertThat(auth).isEqualTo("Bearer test-token");
    var sent = mapper.readTree(requestBody);
    assertThat(sent.path("CategoryCode").asInt()).isEqualTo(50000);
    assertThat(sent.path("ItemName").asText()).isEqualTo("검증 재료");
    assertThat(sent.path("ItemTier").asInt()).isEqualTo(4);
    assertThat(sent.path("Sort").asText()).isEqualTo("RECENT_PRICE");
    assertThat(sent.path("SortCondition").asText()).isEqualTo("ASC");
    assertThat(sent.path("PageNo").asInt()).isEqualTo(1);
    var item = result.items().getFirst();
    assertThat(item.bundleCount()).isEqualTo(100);
    assertThat(item.yDayAvgPrice()).isEqualByComparingTo("12.34");
    assertThat(item.recentPrice()).isZero();
    assertThat(item.currentMinPrice()).isEqualByComparingTo("13");
    assertThat(item.tradeRemainCount()).isNull();
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "market_items", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 최근_거래는_현재_최저가를_추론하지_않고_누락_가격을_보존한다() {
    body = body.replace("\"RecentPrice\":0", "\"RecentPrice\":null");
    var result =
        client("test-token", Duration.ofSeconds(2)).fetch(MarketSource.RECENT_TRADE, search);
    assertThat(path).isEqualTo("/markets/trades");
    assertThat(result.items().getFirst().currentMinPrice()).isNull();
    assertThat(result.items().getFirst().recentPrice()).isNull();
  }

  @Test
  void 빈_검색_결과는_정상_응답이다() {
    body = "{\"PageNo\":1,\"PageSize\":10,\"TotalCount\":0,\"Items\":[]}";
    assertThat(
            client("test", Duration.ofSeconds(2))
                .fetch(MarketSource.ACTIVE_LISTING, search)
                .items())
        .isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "broken",
        "{\"PageNo\":1,\"PageSize\":10,\"TotalCount\":0,\"Items\":null}",
        "{\"PageNo\":1,\"PageSize\":10,\"TotalCount\":1,\"Items\":[null]}"
      })
  void 누락되거나_깨진_응답은_빈_결과로_꾸미지_않는다(String response) {
    body = response;
    assertError(client("test", Duration.ofSeconds(2)), LostArkErrorCode.LOST_ARK_API_ERROR);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"BundleCount\":100",
        "\"Id\":123",
        "\"YDayAvgPrice\":12.34",
        "\"CurrentMinPrice\":13"
      })
  void 잘못된_수치나_식별자는_실패한다(String field) {
    body = body.replace(field, field.substring(0, field.indexOf(':') + 1) + "-1");
    assertError(client("test", Duration.ofSeconds(2)), LostArkErrorCode.LOST_ARK_API_ERROR);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 404, 500, 503})
  void 외부_실패는_일관된_오류로_변환한다(int responseStatus) {
    status = responseStatus;
    assertError(client("test", Duration.ofSeconds(2)), LostArkErrorCode.LOST_ARK_API_ERROR);
  }

  @Test
  void 외부_429_이후에는_재요청하지_않는다() {
    var client = client("test", Duration.ofSeconds(2));
    status = 429;
    assertError(client, LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    status = 200;
    assertError(client, LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    assertThat(calls).hasValue(1);
  }

  @Test
  void 키가_없으면_외부_호출하지_않는다() {
    assertError(client("", Duration.ofSeconds(2)), LostArkErrorCode.LOST_ARK_NOT_CONFIGURED);
    assertThat(calls).hasValue(0);
  }

  @Test
  void 타임아웃을_구분한다() {
    delay = 300;
    assertError(client("test", Duration.ofMillis(50)), LostArkErrorCode.LOST_ARK_API_TIMEOUT);
    assertThat(registry.get("loakraken.lostark.requests").tag("outcome", "timeout").timer().count())
        .isEqualTo(1);
  }

  private LostArkMarketClient client(String key, Duration timeout) {
    var properties =
        new LostArkProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            key,
            Duration.ofSeconds(2),
            timeout,
            90,
            1,
            Duration.ofMinutes(5));
    return new LostArkMarketClient(
        new LostArkConfiguration().lostArkRestClient(properties),
        properties,
        new LostArkRequestGate(Clock.systemUTC(), properties, metrics),
        metrics,
        mapper);
  }

  private void assertError(LostArkMarketClient client, LostArkErrorCode code) {
    assertThatThrownBy(() -> client.fetch(MarketSource.ACTIVE_LISTING, search))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(code));
  }
}
