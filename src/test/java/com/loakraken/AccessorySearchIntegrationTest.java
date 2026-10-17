package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.AuctionPort.AuctionListing;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.ListingOption;
import com.loakraken.market.application.AuctionSearchPort;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** T4 악세 경매 검색기(N13): 기능 플래그를 켠 경로·캐시·한도·계약. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "loakraken.features.auction-search=true")
class AccessorySearchIntegrationTest {
  private static final String PATH = "/api/v1/markets/auctions/accessories/search";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @MockitoBean AuctionSearchPort search;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = new ArrayList<String>();
    for (var pattern :
        List.of(
            "market:v1:auction-search*", "market:v2:auction-search*", "lostark:client-limit:*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 조건을_서버_필터로_검색하고_같은_조건은_캐시에서_준다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(17000));
    var body =
        """
        {"slot":"NECKLACE","grade":"ANCIENT","polish":[{"option":"ENEMY_DAMAGE","level":"HIGH"},{"option":"ADDITIONAL_DAMAGE"}],
         "enlightenmentMin":12,"minQuality":70}
        """;
    var first = post(body, "203.0.113.10");
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    ApiContractFixture.write("market-auction-accessory-search.json", first.body());
    var data = mapper.readTree(first.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("accessory-search-v1");
    assertThat(data.path("cached").asBoolean()).isFalse();
    assertThat(data.path("complete").asBoolean()).isTrue();
    assertThat(data.path("scannedPages").asInt()).isEqualTo(1);
    assertThat(data.path("nextPage").asInt()).isEqualTo(2);
    var item = data.at("/items/0");
    assertThat(item.path("enlightenment").asInt()).isEqualTo(13);
    assertThat(item.path("mainStat").asInt()).isEqualTo(17000);
    // (17000 − 15178) ÷ 2679 = 68.01%, 연마 3단계.
    assertThat(item.path("polishStage").asInt()).isEqualTo(3);
    assertThat(item.path("mainStatRangePct").decimalValue()).isEqualByComparingTo("68.01");
    assertThat(item.at("/polish/0/option").asText()).isEqualTo("ADDITIONAL_DAMAGE");
    assertThat(item.at("/polish/0/shortName").asText()).isEqualTo("추피");
    assertThat(item.at("/polish/0/level").asText()).isEqualTo("MID");
    assertThat(item.at("/polish/1/option").isNull()).isTrue();
    // 매물 이미지·기본 효과(STAT 줄만, 공식 순서, 값 있는 줄만).
    assertThat(item.path("icon").asText()).isEqualTo("https://cdn.example/acc_necklace.png");
    var effects = item.path("baseEffects");
    assertThat(effects.size()).isEqualTo(4);
    assertThat(effects.at("/0/name").asText()).isEqualTo("힘");
    assertThat(effects.at("/3/name").asText()).isEqualTo("체력");
    assertThat(effects.at("/3/value").decimalValue()).isEqualByComparingTo("4200");
    assertThat(effects.toString()).doesNotContain("치명", "깨달음", "추가 피해");

    var second = post(body, "203.0.113.11");
    assertThat(mapper.readTree(second.body()).at("/data/cached").asBoolean()).isTrue();
    verify(search, times(1)).search(any());
    SiteNames.assertAbsent(first.body());
  }

  @Test
  void 잘못된_조건은_400이고_공식_호출이_없다() throws Exception {
    var wrongSlot =
        post(
            "{\"slot\":\"NECKLACE\",\"grade\":\"ANCIENT\",\"polish\":[{\"option\":\"CRIT_DAMAGE\"}]}",
            "203.0.113.12");
    assertThat(wrongSlot.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(wrongSlot.body()).path("code").asText())
        .isEqualTo("INVALID_AUCTION_FILTER");
    assertThat(post("{\"grade\":\"ANCIENT\"}", "203.0.113.12").statusCode()).isEqualTo(400);
    assertThat(
            post("{\"slot\":\"RING\",\"grade\":\"RELIC\",\"page\":11}", "203.0.113.12")
                .statusCode())
        .isEqualTo(400);
    verifyNoInteractions(search);
  }

  @Test
  void 같은_클라이언트의_캐시_미스_공식_호출은_분당_5회까지다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(17000));
    for (int page = 1; page <= 5; page++)
      assertThat(post(pageBody(page), "203.0.113.20").statusCode())
          .as("page " + page)
          .isEqualTo(200);
    var sixth = post(pageBody(6), "203.0.113.20");
    assertThat(sixth.statusCode()).isEqualTo(429);
    assertThat(mapper.readTree(sixth.body()).path("code").asText())
        .isEqualTo("CLIENT_RATE_LIMITED");
    assertThat(sixth.headers().firstValue("Retry-After")).isPresent();
    // 캐시에 맞는 검색은 세지 않는다.
    assertThat(post(pageBody(1), "203.0.113.20").statusCode()).isEqualTo(200);
    verify(search, times(5)).search(any());
  }

  @Test
  void 기능_몫을_넘으면_503과_Retry_After이고_캐시된_결과는_계속_준다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(17000));
    assertThat(post(pageBody(1), "203.0.113.30").statusCode()).isEqualTo(200);
    // 이번 분과 다음 분의 몫을 모두 쓴 상태로 만든다(분 경계에 걸려도 같은 결과).
    long window = System.currentTimeMillis() / 60_000;
    for (long w = window; w <= window + 1; w++)
      redis.opsForValue().set("market:v1:auction-search-quota:" + w, "20", Duration.ofMinutes(3));
    var busy = post(pageBody(2), "203.0.113.30");
    assertThat(busy.statusCode()).isEqualTo(503);
    assertThat(mapper.readTree(busy.body()).path("code").asText()).isEqualTo("MARKET_SEARCH_BUSY");
    assertThat(busy.headers().firstValue("Retry-After")).isPresent();
    var cached = post(pageBody(1), "203.0.113.30");
    assertThat(cached.statusCode()).isEqualTo(200);
    assertThat(mapper.readTree(cached.body()).at("/data/cached").asBoolean()).isTrue();
    verify(search, times(1)).search(any());
  }

  @Test
  void 힘민지_범위_퍼센트_조건은_최대_3페이지에서_거르고_complete를_알린다() throws Exception {
    // 페이지마다 10개 중 1개만 범위 % 100(17857), 나머지는 0%(15178).
    when(search.search(any()))
        .thenAnswer(
            invocation -> {
              var items = new ArrayList<AuctionListing>();
              for (int i = 0; i < 10; i++) items.add(listing(i == 0 ? 17857 : 15178, 1000L + i));
              return new AuctionPage(500, items, Instant.now());
            });
    var response =
        post(
            "{\"slot\":\"NECKLACE\",\"grade\":\"ANCIENT\",\"mainStatRangeMinPct\":90}",
            "203.0.113.31");
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("items").size()).isEqualTo(3);
    assertThat(data.path("scannedPages").asInt()).isEqualTo(3);
    assertThat(data.path("complete").asBoolean()).isFalse();
    assertThat(data.path("nextPage").asInt()).isEqualTo(4);
    verify(search, times(3)).search(any());
  }

  private static String pageBody(int page) {
    return "{\"slot\":\"EARRING\",\"grade\":\"RELIC\",\"page\":" + page + "}";
  }

  private static AuctionPage page(int mainStat) {
    var items = new ArrayList<AuctionListing>();
    for (int i = 0; i < 10; i++) items.add(listing(mainStat, 1000L + i));
    return new AuctionPage(25, items, Instant.now());
  }

  private static AuctionListing listing(int mainStat, long price) {
    return new AuctionListing(
        "도래한 결전의 목걸이",
        "고대",
        94,
        price,
        0L,
        price,
        Instant.now().plusSeconds(3600),
        2,
        List.of(
            new ListingOption("ARK_PASSIVE", "깨달음", new BigDecimal("13.0"), false, null),
            new ListingOption("ACCESSORY_UPGRADE", "추가 피해", new BigDecimal("1.6"), true, null),
            new ListingOption("ACCESSORY_UPGRADE", "최대 생명력", new BigDecimal("3250.0"), false, null),
            new ListingOption("ACCESSORY_UPGRADE", "무기 공격력 ", new BigDecimal("195.0"), false, null),
            new ListingOption("STAT", "힘", BigDecimal.valueOf(mainStat), false, null),
            new ListingOption("STAT", "민첩", BigDecimal.valueOf(mainStat), false, null),
            new ListingOption("STAT", "지능", BigDecimal.valueOf(mainStat), false, null),
            new ListingOption("STAT", "체력", new BigDecimal("4200.0"), false, null),
            // 값이 없는 STAT 줄은 기본 효과에 넣지 않는다.
            new ListingOption("STAT", "치명", null, false, null)),
        "https://cdn.example/acc_necklace.png");
  }

  private HttpResponse<String> post(String body, String clientAddress) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
              .header("Content-Type", "application/json")
              // 테스트 클라이언트(127.0.0.1)는 신뢰 프록시라 이 헤더가 원격 주소가 된다.
              .header("X-Forwarded-For", clientAddress)
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
