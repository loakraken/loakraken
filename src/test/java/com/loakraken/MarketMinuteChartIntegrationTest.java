package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.MarketMinuteService;
import com.loakraken.market.application.MarketPricesObserved;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 거래소 분 단위 차트(K35): 예열 가격 이벤트를 관측 시각의 UTC 분 칸으로 저장하고 최근 N분을 준다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketMinuteChartIntegrationTest {
  private static final long BOARD_ITEM = 990001L;
  private static final long BOOK_ITEM = 990002L;
  private static final long OLD_ITEM = 990003L;
  @LocalServerPort int port;
  @Autowired ApplicationEventPublisher events;
  @Autowired JdbcClient jdbc;
  @Autowired MarketMinuteService minutes;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM market_price_minute WHERE item_id BETWEEN 990000 AND 990099").update();
  }

  @Test
  void 관측_시각의_분_칸으로_저장하고_같은_분은_나중_값이며_빈_분은_채우지_않는다() throws Exception {
    var base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(3));
    observe(base.plusSeconds(5), Duration.ofSeconds(30), BOARD_ITEM, "100", "98");
    observe(base.plusSeconds(40), Duration.ofSeconds(30), BOARD_ITEM, "120", "118");
    // 한 분 건너뛰고(빈 분) 그다음 분.
    observe(
        base.plus(Duration.ofMinutes(2)).plusSeconds(10),
        Duration.ofSeconds(30),
        BOARD_ITEM,
        "130",
        null);
    observe(base.plusSeconds(20), Duration.ofMinutes(30), BOOK_ITEM, "5000", "4900");

    var data = data("/api/v1/markets/items/" + BOARD_ITEM + "/minutes?minutes=10");
    assertThat(data.path("itemId").asLong()).isEqualTo(BOARD_ITEM);
    assertThat(data.path("itemName").asText()).isEqualTo("시험 품목 " + BOARD_ITEM);
    assertThat(data.path("intervalSeconds").asInt()).isEqualTo(60);
    assertThat(data.path("sampleEverySeconds").asInt()).isEqualTo(30);
    var points = data.path("points");
    assertThat(points.size()).isEqualTo(2);
    assertThat(Instant.parse(points.at("/0/minute").asText())).isEqualTo(base);
    assertThat(points.at("/0/currentMinPrice").decimalValue()).isEqualByComparingTo("120");
    assertThat(points.at("/0/recentPrice").decimalValue()).isEqualByComparingTo("118");
    assertThat(Instant.parse(points.at("/1/minute").asText()))
        .isEqualTo(base.plus(Duration.ofMinutes(2)));
    assertThat(points.at("/1/recentPrice").isNull()).isTrue();
    // 창: 진행 중인 분까지 10칸.
    var to = Instant.parse(data.path("to").asText());
    assertThat(Instant.parse(data.path("from").asText()))
        .isEqualTo(to.minus(Duration.ofMinutes(9)));

    // 30분 주기 품목은 그 주기가 드러난다.
    var book = data("/api/v1/markets/items/" + BOOK_ITEM + "/minutes");
    assertThat(book.path("sampleEverySeconds").asInt()).isEqualTo(1800);
    assertThat(book.path("points").size()).isEqualTo(1);
  }

  @Test
  void 관측한_적_없는_품목은_빈_목록이고_이름과_주기는_null이다() throws Exception {
    var data = data("/api/v1/markets/items/990099/minutes");
    assertThat(data.path("points").size()).isZero();
    assertThat(data.path("itemName").isNull()).isTrue();
    assertThat(data.path("sampleEverySeconds").isNull()).isTrue();
  }

  @Test
  void 두_시간보다_오래된_분은_정리하고_창_밖은_주지_않는다() throws Exception {
    var now = Instant.now();
    observe(now.minus(Duration.ofMinutes(130)), Duration.ofSeconds(30), OLD_ITEM, "10", null);
    observe(now.minus(Duration.ofMinutes(30)), Duration.ofSeconds(30), OLD_ITEM, "11", null);
    // 120분 창에도 130분 전 점은 없다.
    assertThat(
            data("/api/v1/markets/items/" + OLD_ITEM + "/minutes?minutes=120")
                .path("points")
                .size())
        .isEqualTo(1);
    assertThat(minutes.cleanup()).isGreaterThanOrEqualTo(1);
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM market_price_minute WHERE item_id = :id")
                .param("id", OLD_ITEM)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/markets/items/990001/minutes?minutes=0",
        "/api/v1/markets/items/990001/minutes?minutes=121",
        "/api/v1/markets/items/990001/minutes?minutes=-5",
        "/api/v1/markets/items/990001/minutes?minutes=abc",
        "/api/v1/markets/items/0/minutes",
        "/api/v1/markets/items/abc/minutes"
      })
  void 잘못된_입력은_400이다(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(400);
  }

  private void observe(Instant at, Duration every, long itemId, String min, String recent) {
    events.publishEvent(
        new MarketPricesObserved(
            at,
            every,
            List.of(
                new MarketPrice(
                    itemId,
                    "시험 품목 " + itemId,
                    "일반",
                    null,
                    1,
                    null,
                    null,
                    recent == null ? null : new BigDecimal(recent),
                    new BigDecimal(min)))));
  }

  private JsonNode data(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
