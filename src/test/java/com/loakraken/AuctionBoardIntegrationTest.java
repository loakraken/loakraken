package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.AuctionPort.AuctionListing;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.AuctionQuery;
import com.loakraken.market.application.AuctionPort.ListingOption;
import com.loakraken.market.application.AuctionSearchPort;
import com.loakraken.market.application.MarketAuctionService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuctionBoardIntegrationTest {
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean AuctionSearchPort search;
  @Autowired com.loakraken.common.lostark.LostArkRequestGate gate;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = new ArrayList<String>();
    var found = redis.keys("market:v1:auction-*");
    if (found != null) keys.addAll(found);
    var limits = redis.keys("lostark:client-limit:*");
    if (limits != null) keys.addAll(limits);
    if (!keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM auction_price_daily").update();
  }

  @Test
  void 보석_표는_칸을_채워_캐시하고_전날_관측과_비교한다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(invocation.getArgument(0)));
    var tenFire = new AuctionQuery(210000, 4, null, "10레벨 겁화의 보석", null, List.of(), 1).key();
    jdbc.sql(
            """
        INSERT INTO auction_price_daily (cell_key, kst_date, min_buy_price, total_count, observed_at)
        VALUES (:k, :d, 2000000, 30, now())
        """)
        .param("k", tenFire)
        .param("d", LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1))
        .update();

    // 요청 하나는 빈 칸을 최대 5칸만 채운다(#526). 첫 방문은 5칸 READY·15칸 PENDING이다.
    var firstVisit = data("/api/v1/markets/auctions/gems");
    assertThat(firstVisit.path("complete").asBoolean()).isFalse();
    assertThat(firstVisit.path("cells").size()).isEqualTo(20);
    long ready = 0;
    for (var cell : firstVisit.path("cells"))
      if (cell.path("status").asText().equals("READY")) ready++;
    assertThat(ready).isEqualTo(MarketAuctionService.MAX_FETCH_PER_REQUEST);
    verify(search, times(MarketAuctionService.MAX_FETCH_PER_REQUEST)).search(any());
    // 이어지는 방문이 남은 칸을 채운다(20칸 = 5칸 × 4번).
    JsonNode data = firstVisit;
    for (int visit = 2; visit <= 4; visit++) data = data("/api/v1/markets/auctions/gems");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("auction-board-v1");
    assertThat(data.path("complete").asBoolean()).isTrue();
    assertThat(data.path("cells").size()).isEqualTo(20);
    var first = data.at("/cells/0");
    assertThat(first.path("itemName").asText()).isEqualTo("10레벨 겁화의 보석");
    assertThat(first.path("minBuyPrice").asLong()).isEqualTo(2_500_000L);
    assertThat(first.path("totalCount").asLong()).isEqualTo(25);
    assertThat(first.path("previousDayChangeRate").decimalValue()).isEqualByComparingTo("25.0");
    assertThat(data.at("/cells/1/previousDayChangeRate").isNull()).isTrue();
    assertThat(first.path("status").asText()).isEqualTo("READY");
    ApiContractFixture.write("auction-gems.json", get("/api/v1/markets/auctions/gems").body());
    verify(search, times(20)).search(any());
    assertThat(
            jdbc.sql("SELECT COUNT(*) FROM auction_price_daily WHERE kst_date = :d")
                .param("d", LocalDate.now(ZoneId.of("Asia/Seoul")))
                .query(Long.class)
                .single())
        .isEqualTo(20);

    var listings =
        data("/api/v1/markets/auctions/gems/listings?kind=" + encode("겁화") + "&level=10");
    assertThat(listings.path("totalCount").asLong()).isEqualTo(25);
    assertThat(listings.at("/items/0/options/0/className").asText()).isEqualTo("건슬링어");
    assertThat(listings.at("/items/0/options/0/level").isNull()).isTrue();
  }

  @Test
  void 장신구_표와_매물은_연마_단계를_표시한다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(invocation.getArgument(0)));
    var data = data("/api/v1/markets/auctions/accessories?grade=ANCIENT&role=DEALER");
    assertThat(data.path("minQuality").asInt()).isEqualTo(70);
    assertThat(data.path("slots").size()).isEqualTo(3);
    var necklace = data.at("/slots/0");
    assertThat(necklace.path("slot").asText()).isEqualTo("NECKLACE");
    assertThat(necklace.at("/options/0/shortName").asText()).isEqualTo("적주피");
    assertThat(necklace.at("/options/0/values/2").decimalValue()).isEqualByComparingTo("2.00");
    assertThat(necklace.path("cells").size()).isEqualTo(8);
    assertThat(necklace.at("/cells/1/label").asText()).isEqualTo("상중");
    assertThat(necklace.at("/cells/1/levels/1").asText()).isEqualTo("MID");
    ApiContractFixture.write(
        "auction-accessories.json",
        get("/api/v1/markets/auctions/accessories?grade=ANCIENT&role=DEALER").body());

    var support = data("/api/v1/markets/auctions/accessories?role=SUPPORT&minQuality=0");
    assertThat(support.path("minQuality").isNull()).isTrue();
    assertThat(support.at("/slots/1/cells").size()).isEqualTo(3);

    var listings =
        data("/api/v1/markets/auctions/accessories/listings?slot=NECKLACE&levels=HIGH,HIGH");
    var option = listings.at("/items/0/options/0");
    assertThat(option.path("name").asText()).isEqualTo("적에게 주는 피해 증가");
    assertThat(option.path("level").asText()).isEqualTo("HIGH");
    ApiContractFixture.write(
        "auction-accessory-listings.json",
        get("/api/v1/markets/auctions/accessories/listings?slot=NECKLACE&levels=HIGH,HIGH").body());
  }

  @Test
  void 잘못된_조건은_400이다() throws Exception {
    for (var path :
        List.of(
            "/api/v1/markets/auctions/gems/listings?kind=" + encode("멸화") + "&level=10",
            "/api/v1/markets/auctions/gems/listings?kind=" + encode("겁화") + "&level=11",
            "/api/v1/markets/auctions/accessories?minQuality=95",
            "/api/v1/markets/auctions/accessories?grade=LEGEND",
            "/api/v1/markets/auctions/accessories/listings?slot=EARRING&role=SUPPORT&levels=HIGH,MID"))
      assertThat(get(path).statusCode()).as(path).isEqualTo(400);
    verifyNoInteractions(search);
  }

  @Test
  void 시세표_칸_채우기도_요청_클라이언트의_공식_호출_한도에_매긴다() throws Exception {
    // 가짜 검색도 실제 클라이언트처럼 호출마다 공유 게이트를 지난다. 딜러 장신구 표는 24칸이고 클라이언트 공식 한도는 분당 20이다.
    when(search.search(any()))
        .thenAnswer(
            invocation -> {
              gate.acquire();
              try {
                return page(invocation.getArgument(0));
              } finally {
                gate.release();
              }
            });
    // 요청마다 최대 5칸: 4번의 요청이 20칸(클라이언트 한도 20)을 채우고, 5번째 요청의 첫 호출이 한도에 걸린다.
    HttpResponse<String> response = null;
    for (int visit = 1; visit <= 5; visit++) {
      response =
          get("/api/v1/markets/auctions/accessories?grade=ANCIENT&role=DEALER", "203.0.113.40");
      assertThat(response.statusCode()).isEqualTo(200);
    }
    var cells = new ArrayList<JsonNode>();
    for (var slot : mapper.readTree(response.body()).at("/data/slots"))
      for (var cell : slot.path("cells")) cells.add(cell);
    assertThat(cells).hasSize(24);
    assertThat(cells.stream().filter(cell -> cell.path("status").asText().equals("READY")))
        .hasSize(20);
    assertThat(cells.stream().filter(cell -> cell.path("status").asText().equals("PENDING")))
        .hasSize(4);
    verify(search, times(21)).search(any());
  }

  @Test
  void 장신구_조건_검색은_기능_플래그가_꺼져_있으면_404다() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/markets/auctions/accessories/search"))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "{\"slot\":\"NECKLACE\",\"grade\":\"ANCIENT\"}"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(404);
    }
    verifyNoInteractions(search);
  }

  private static AuctionPage page(AuctionQuery query) {
    var now = Instant.now();
    var options =
        query.itemName() != null
            ? List.of(
                new ListingOption(
                    "GEM_SKILL_DAMAGE", "마탄의 사수", new BigDecimal("44.0"), true, "건슬링어"))
            : List.of(
                new ListingOption(
                    "ACCESSORY_UPGRADE", "적에게 주는 피해 증가", new BigDecimal("2.0"), true, null),
                new ListingOption("STAT", "힘", new BigDecimal("15393"), false, null));
    return new AuctionPage(
        25,
        List.of(
            new AuctionListing("매물", "고대", 73, 2_500_000L, 0L, 2_500_000L, now, 0, options),
            new AuctionListing("매물", "고대", 80, 2_600_000L, 0L, 2_600_000L, now, 1, options)),
        now);
  }

  private JsonNode data(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path, String clientAddress) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              // 테스트 클라이언트(127.0.0.1)는 신뢰 프록시라 이 헤더가 원격 주소가 된다.
              .header("X-Forwarded-For", clientAddress)
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
