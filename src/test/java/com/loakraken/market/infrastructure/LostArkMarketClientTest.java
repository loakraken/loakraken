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
  void 상품_상세는_GET으로_조회하고_변형별_일별_시세를_보존한다() {
    body =
        """
        [{"Name":"검증 재료","TradeRemainCount":null,"BundleCount":100,"Stats":[
          {"Date":"2026-10-06","AvgPrice":175.5,"TradeCount":8817},
          {"Date":"2026-10-05","AvgPrice":172.6,"TradeCount":247145},
          {"Date":null,"AvgPrice":1,"TradeCount":1}],"ToolTip":"{}"},
         {"Name":"검증 재료","TradeRemainCount":2,"BundleCount":1,"Stats":null}]
        """;
    var history =
        new LostArkMarketItemClient(httpClient("test-token", Duration.ofSeconds(2)))
            .fetchHistory(66102006)
            .orElseThrow();
    assertThat(path).isEqualTo("/markets/items/66102006");
    assertThat(method).isEqualTo("GET");
    assertThat(history.itemId()).isEqualTo(66102006);
    assertThat(history.variants()).hasSize(2);
    var first = history.variants().getFirst();
    assertThat(first.bundleCount()).isEqualTo(100);
    assertThat(first.tradeRemainCount()).isNull();
    assertThat(first.days()).hasSize(2);
    assertThat(first.days().getFirst().averagePrice()).isEqualByComparingTo("175.5");
    assertThat(history.variants().get(1).days()).isEmpty();
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "market_item_history", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 공식_API가_null을_돌려주면_상품_없음으로_처리한다() {
    body = "null";
    assertThat(
            new LostArkMarketItemClient(httpClient("test", Duration.ofSeconds(2))).fetchHistory(1))
        .isEmpty();
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "market_item_history", "outcome", "not_found")
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
    return new LostArkMarketClient(httpClient(key, timeout));
  }

  @Test
  void 검색_옵션은_GET으로_조회하고_하위목록의_null과_빈목록을_보존한다() {
    body =
        """
      {"Categories":[{"Code":50000,"CodeName":"재료","Subs":[{"Code":50010,"CodeName":"강화"}]},
        {"Code":60000,"CodeName":"소모품","Subs":[]},{"Code":70000,"CodeName":"기타","Subs":null}],
       "ItemGrades":["일반","희귀"],"ItemTiers":[3,4],"Classes":["바드"],"FutureField":true}
      """;
    var options =
        new LostArkMarketOptionsClient(httpClient("test-token", Duration.ofSeconds(2)))
            .fetchOptions();
    assertThat(path).isEqualTo("/markets/options");
    assertThat(method).isEqualTo("GET");
    assertThat(auth).isEqualTo("Bearer test-token");
    assertThat(requestBody).isEmpty();
    assertThat(options.categories().getFirst().subs().getFirst().code()).isEqualTo(50010);
    assertThat(options.categories().get(1).subs()).isEmpty();
    assertThat(options.categories().get(2).subs()).isNull();
    assertThat(options.itemGrades()).containsExactly("일반", "희귀");
    assertThat(options.itemTiers()).containsExactly(3, 4);
    assertThat(options.classes()).containsExactly("바드");
    assertThat(
            registry
                .get("loakraken.lostark.requests")
                .tags("endpoint", "market_options", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null",
        "{}",
        "broken",
        "{\"Categories\":null,\"ItemGrades\":[],\"ItemTiers\":[],\"Classes\":[]}",
        "{\"Categories\":[{\"CodeName\":\"missing code\"}],\"ItemGrades\":[],\"ItemTiers\":[],\"Classes\":[]}",
        "{\"Categories\":[],\"ItemGrades\":[null],\"ItemTiers\":[],\"Classes\":[]}",
        "{\"Categories\":[],\"ItemGrades\":[],\"ItemTiers\":[-1],\"Classes\":[]}"
      })
  void 필수_검색_옵션이_없거나_잘못되면_실패한다(String response) {
    body = response;
    var client = new LostArkMarketOptionsClient(httpClient("test", Duration.ofSeconds(2)));
    assertThatThrownBy(client::fetchOptions)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.errorCode()).isEqualTo(LostArkErrorCode.LOST_ARK_API_ERROR));
  }

  @Test
  void 제공된_빈_옵션은_누락으로_취급하지_않는다() {
    body = "{\"Categories\":[],\"ItemGrades\":[],\"ItemTiers\":[],\"Classes\":[]}";
    var options =
        new LostArkMarketOptionsClient(httpClient("test", Duration.ofSeconds(2))).fetchOptions();
    assertThat(options.categories()).isEmpty();
    assertThat(options.classes()).isEmpty();
  }

  @Test
  void 옵션_429도_가격_요청을_함께_차단한다() {
    var http = httpClient("test", Duration.ofSeconds(2));
    var options = new LostArkMarketOptionsClient(http);
    status = 429;
    assertThatThrownBy(options::fetchOptions)
        .isInstanceOfSatisfying(
            BusinessException.class,
            error ->
                assertThat(error.errorCode()).isEqualTo(LostArkErrorCode.LOST_ARK_RATE_LIMITED));
    status = 200;
    assertError(new LostArkMarketClient(http), LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    assertThat(calls).hasValue(1);
  }

  @Test
  void 공식_호출_로그에_거래소_분류_품목_이름_쪽과_상품_ID만_남기고_키는_남기지_않는다() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(LostArkRequestGate.CALL_LOGGER);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    var previous = logger.getLevel();
    try {
      logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
      var http = httpClient("test-token", Duration.ofSeconds(2));
      new LostArkMarketClient(http).fetch(MarketSource.ACTIVE_LISTING, search);
      body = "[]";
      new LostArkMarketItemClient(http).fetchHistory(66102006L);
      body = "{\"Categories\":[],\"ItemGrades\":[],\"ItemTiers\":[],\"Classes\":[]}";
      new LostArkMarketOptionsClient(http).fetchOptions();

      assertThat(appender.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .containsExactly(
              "official-call endpoint=MARKET_ITEMS origin=USER reserve=0 category=50000 item=\"검증 재료\" page=1",
              "official-call endpoint=MARKET_ITEM_HISTORY origin=USER reserve=0 itemId=66102006",
              "official-call endpoint=MARKET_OPTIONS origin=USER reserve=0")
          .noneMatch(line -> line.contains("test-token") || line.contains("http"));
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
    }
  }

  @Test
  void 호출_대상은_이름이_없으면_대시다() {
    assertThat(LostArkMarketHttpClient.target(60000, null, 3))
        .isEqualTo("category=60000 item=- page=3");
    assertThat(LostArkMarketHttpClient.target(60000, " ", 1))
        .isEqualTo("category=60000 item=- page=1");
  }

  @Test
  void 호출_대상의_품목_이름은_제어_문자를_지우고_따옴표와_역슬래시를_이스케이프한다() {
    assertThat(
            LostArkMarketHttpClient.target(
                50000, "가짜\" page=9\nofficial-call endpoint=PROFILE\\", 1))
        .isEqualTo("category=50000 item=\"가짜\\\" page=9official-call endpoint=PROFILE\\\\\" page=1")
        .doesNotContain("\n");
    assertThat(LostArkMarketHttpClient.target(50000, "\r\n\t", 1))
        .isEqualTo("category=50000 item=- page=1");
    assertThat(LostArkMarketHttpClient.target(50000, "들꽃 ", 2))
        .isEqualTo("category=50000 item=\"들꽃\" page=2");
  }

  private LostArkMarketHttpClient httpClient(String key, Duration timeout) {
    var properties =
        new LostArkProperties(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            key,
            Duration.ofSeconds(2),
            timeout,
            90,
            1,
            Duration.ofMinutes(5));
    return new LostArkMarketHttpClient(
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
