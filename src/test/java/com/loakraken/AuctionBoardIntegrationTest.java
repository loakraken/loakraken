package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.loakraken.market.application.AuctionPort.AuctionCell;
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
  @Autowired io.micrometer.core.instrument.MeterRegistry meters;
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

  @Autowired com.loakraken.market.application.AuctionPort auctions;

  @Test
  void 마지막_값은_늦게_끝난_옛_관측이_새_값을_덮지_않는다() {
    // 코드 리뷰 10-25: 무조건 덮어써서, 늦게 끝난 옛 관측이 새 값을 지우면 3분 칸이 빈 뒤 옛 시세가 보였다.
    var key = "gem:test-last";
    var newer = new AuctionCell(1_200_000L, 5, Instant.parse("2026-10-25T03:00:00Z"));
    var older = new AuctionCell(900_000L, 4, Instant.parse("2026-10-25T02:59:00Z"));
    auctions.saveLastCell(key, newer);
    auctions.saveLastCell(key, older);
    assertThat(auctions.findLastCell(key)).contains(newer);
    // 같은 시각이나 더 새 관측은 바꾼다
    var same = new AuctionCell(1_100_000L, 6, newer.observedAt());
    auctions.saveLastCell(key, same);
    assertThat(auctions.findLastCell(key)).contains(same);
    var latest = new AuctionCell(1_000_000L, 7, Instant.parse("2026-10-25T03:05:00Z"));
    auctions.saveLastCell(key, latest);
    assertThat(auctions.findLastCell(key)).contains(latest);
    // 관측 시각 없는 칸은 남기지 않고, 마지막 값 키는 만료가 없다
    auctions.saveLastCell(key, new AuctionCell(1L, 1, null));
    assertThat(auctions.findLastCell(key)).contains(latest);
    assertThat(redis.getExpire("market:v1:auction-last:" + key)).isEqualTo(-1L);
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
    assertThat(first.path("stale").asBoolean(true)).isFalse();
    ApiContractFixture.write("auction-gems.json", get("/api/v1/markets/auctions/gems").body());
    verify(search, times(20)).search(any());
    // 3분 칸만 지우면(만료) 다음 요청은 앞 5칸만 다시 채우고, 나머지는 마지막 관측 값을 stale=true로 준다(관측 시각 그대로).
    var observed = data.at("/cells/10/observedAt").asText();
    redis.delete(redis.keys("market:v1:auction-cell:*"));
    var again = mapper.readTree(get("/api/v1/markets/auctions/gems").body()).at("/data/cells/10");
    assertThat(again.path("status").asText()).isEqualTo("READY");
    assertThat(again.path("stale").asBoolean()).isTrue();
    assertThat(again.path("observedAt").asText()).isEqualTo(observed);
    verify(search, org.mockito.Mockito.atMost(25)).search(any());
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
    // 공식 Icon이 매물 응답까지 온다(#609), 없으면 null.
    assertThat(listings.at("/items/0/icon").asText()).isEqualTo(ICON);
    assertThat(listings.at("/items/1/icon").isNull()).isTrue();
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
    assertThat(necklace.path("cells").size()).isEqualTo(6);
    assertThat(necklace.at("/cells/5/label").asText()).isEqualTo("하상");
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
    assertThat(listings.at("/items/0/icon").asText()).isEqualTo(ICON);
    var option = listings.at("/items/0/options/0");
    assertThat(option.path("name").asText()).isEqualTo("적에게 주는 피해 증가");
    assertThat(option.path("level").asText()).isEqualTo("HIGH");
    ApiContractFixture.write(
        "auction-accessory-listings.json",
        get("/api/v1/markets/auctions/accessories/listings?slot=NECKLACE&levels=HIGH,HIGH").body());
    // 표에서 뺀 중하 조합도 매물 목록으로는 그대로 검색한다(예전 링크가 400이 되지 않게).
    var midLow = get("/api/v1/markets/auctions/accessories/listings?slot=NECKLACE&levels=MID,LOW");
    assertThat(midLow.statusCode()).as(midLow.body()).isEqualTo(200);
    assertThat(mapper.readTree(midLow.body()).at("/data/totalCount").asLong()).isEqualTo(25);
  }

  @Test
  void 보석_매물_직업_스킬_목록은_공식_호출_없이_준다() throws Exception {
    var response = get("/api/v1/markets/auctions/gems/skills");
    ApiContractFixture.write("auction-gem-skills.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("checkedOn").asText()).isEqualTo("2026-10-18");
    assertThat(data.path("classes").size()).isEqualTo(30);
    JsonNode demonic = null;
    for (var value : data.path("classes"))
      if (value.path("className").asText().equals("데모닉")) demonic = value;
    assertThat(demonic).isNotNull();
    JsonNode piercing = null;
    for (var skill : demonic.path("skills"))
      if (skill.path("code").asInt() == 27960) piercing = skill;
    assertThat(piercing.path("name").asText()).isEqualTo("블러드 피어싱");
    assertThat(piercing.path("kinds").toString()).isEqualTo("[\"겁화\",\"작열\"]");
    verifyNoInteractions(search);
  }

  @Test
  void 직업_스킬로_거른_보석_매물은_따로_캐시하고_시세판_칸에_저장하지_않는다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(invocation.getArgument(0)));
    String path =
        "/api/v1/markets/auctions/gems/listings?kind="
            + encode("겁화")
            + "&level=10&className="
            + encode("데모닉")
            + "&skill=27960";
    var listings = data(path);
    ApiContractFixture.write("auction-gem-listings-filtered.json", get(path).body());
    assertThat(listings.path("totalCount").asLong()).isEqualTo(25);
    assertThat(listings.at("/filter/className").asText()).isEqualTo("데모닉");
    assertThat(listings.at("/filter/skill").asInt()).isEqualTo(27960);
    assertThat(listings.at("/filter/skillName").asText()).isEqualTo("블러드 피어싱");
    // 공식 검색에 직업과 SkillOptions(스킬 코드, 겁화 = 피해 증가 500)가 들어간다. 두 번째 같은 요청은 1분 캐시.
    var captor = org.mockito.ArgumentCaptor.forClass(AuctionQuery.class);
    verify(search, times(1)).search(captor.capture());
    var query = captor.getValue();
    assertThat(query.itemName()).isEqualTo("10레벨 겁화의 보석");
    assertThat(query.characterClass()).isEqualTo("데모닉");
    assertThat(query.skillOptions())
        .containsExactly(new com.loakraken.market.application.AuctionPort.SkillFilter(27960, 500));
    // 거른 최저가는 시세판 칸(전체 최저가)·일별 기록이 아니다.
    var plain = new AuctionQuery(210000, 4, null, "10레벨 겁화의 보석", null, List.of(), 1);
    assertThat(query.key()).isNotEqualTo(plain.key());
    assertThat(redis.hasKey("market:v1:auction-cell:" + query.key())).isFalse();
    assertThat(redis.hasKey("market:v1:auction-cell:" + plain.key())).isFalse();
    Thread.sleep(200);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM auction_price_daily").query(Long.class).single())
        .isZero();

    // 직업만 거르면 SkillOptions 없이, 작열은 재사용 대기시간 감소(2700)로 간다. 조건 없는 매물은 예전처럼 칸에 저장한다.
    data(
        "/api/v1/markets/auctions/gems/listings?kind="
            + encode("작열")
            + "&level=9&className="
            + encode("데모닉"));
    data("/api/v1/markets/auctions/gems/listings?kind=" + encode("작열") + "&level=9&skill=");
    data(
        "/api/v1/markets/auctions/gems/listings?kind="
            + encode("작열")
            + "&level=8&className="
            + encode("데모닉")
            + "&skill=27960");
    var all = org.mockito.ArgumentCaptor.forClass(AuctionQuery.class);
    verify(search, times(4)).search(all.capture());
    var classOnly = all.getAllValues().get(1);
    assertThat(classOnly.characterClass()).isEqualTo("데모닉");
    assertThat(classOnly.skillOptions()).isEmpty();
    var unfiltered = all.getAllValues().get(2);
    assertThat(unfiltered.filtered()).isFalse();
    assertThat(redis.hasKey("market:v1:auction-cell:" + unfiltered.key())).isTrue();
    assertThat(all.getAllValues().get(3).skillOptions())
        .containsExactly(new com.loakraken.market.application.AuctionPort.SkillFilter(27960, 2700));
  }

  @Test
  void 목록에_없는_직업_스킬_조건은_입력을_되돌리지_않는_400이다() throws Exception {
    var gem = "/api/v1/markets/auctions/gems/listings?level=10&kind=";
    for (var path :
        List.of(
            // 직업 없이 스킬, 목록에 없는 직업, 그 직업에 없는 스킬(블래스터 스킬), 겁화만 되는 스킬로 작열
            gem + encode("겁화") + "&skill=27960",
            gem + encode("겁화") + "&className=" + encode("없는직업<script>"),
            gem + encode("겁화") + "&className=" + encode("데모닉") + "&skill=30210",
            gem + encode("작열") + "&className=" + encode("데모닉") + "&skill=" + demonicDamageOnly())) {
      var response = get(path);
      assertThat(response.statusCode()).as(path).isEqualTo(400);
      assertThat(response.body()).as(path).contains("INVALID_GEM_FILTER").doesNotContain("script");
    }
    verifyNoInteractions(search);
  }

  @Test
  void 필터_전용_분당_몫을_넘으면_캐시만_주고_캐시가_없으면_503이다() throws Exception {
    when(search.search(any())).thenAnswer(invocation -> page(invocation.getArgument(0)));
    double hitsBefore = gemFilter("cache_hit");
    double rejectsBefore = gemFilter("quota_reject");
    String cachedPath =
        "/api/v1/markets/auctions/gems/listings?kind="
            + encode("겁화")
            + "&level=10&className="
            + encode("데모닉");
    data(cachedPath);
    // 이번 분 몫(기본 10)을 다 쓴 상태로 만든다(모든 사용자·인스턴스 공유 카운터).
    var minute = System.currentTimeMillis() / 60_000;
    for (long value = minute - 1; value <= minute + 1; value++)
      redis.opsForValue().set("market:v1:auction-gem-filter-quota:" + value, "10");
    // 캐시된 조건은 그대로 준다.
    assertThat(data(cachedPath).at("/filter/className").asText()).isEqualTo("데모닉");
    // 새 조건은 공식 호출 없이 503 + Retry-After. 조건 없는 매물은 이 몫과 따로다.
    var busy =
        get(
            "/api/v1/markets/auctions/gems/listings?kind="
                + encode("겁화")
                + "&level=9&className="
                + encode("데모닉"));
    assertThat(busy.statusCode()).isEqualTo(503);
    assertThat(busy.body()).contains("MARKET_SEARCH_BUSY");
    assertThat(busy.headers().firstValue("Retry-After")).isPresent();
    data("/api/v1/markets/auctions/gems/listings?kind=" + encode("겁화") + "&level=9");
    verify(search, times(2)).search(any());
    // 운영에서 몫 거절과 캐시 응답을 셀 수 있다.
    assertThat(gemFilter("cache_hit") - hitsBefore).isGreaterThanOrEqualTo(1);
    assertThat(gemFilter("quota_reject") - rejectsBefore).isEqualTo(1);
    // 보안 점검 10-18: 거절된 요청은 공용 몫 카운터를 올리지 않는다(10 그대로).
    for (long value = minute - 1; value <= minute + 1; value++)
      assertThat(redis.opsForValue().get("market:v1:auction-gem-filter-quota:" + value))
          .isEqualTo("10");
  }

  private double gemFilter(String event) {
    var counter = meters.find("loakraken.market.gem_filter").tag("event", event).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void 공식_호출_전에_거절되면_필터_몫을_돌려주고_공식이_429면_돌려주지_않는다() throws Exception {
    // #553: 관문·클라이언트 한도로 공식 호출 없이 거절된 요청은 모두가 함께 쓰는 분당 몫을 쓰지 않는다.
    String base =
        "/api/v1/markets/auctions/gems/listings?kind="
            + encode("작열")
            + "&className="
            + encode("데모닉")
            + "&level=";
    long before = gemFilterQuotaUsed();
    org.mockito.Mockito.doThrow(
            new com.loakraken.common.lostark.LostArkRateLimitedException(
                com.loakraken.common.lostark.LostArkRateLimitedException.Cause.GATE_BUDGET))
        .when(search)
        .search(any());
    var gate = get(base + "3");
    assertThat(gate.statusCode()).isEqualTo(503);
    assertThat(gate.body()).contains("MARKET_SEARCH_BUSY");
    org.mockito.Mockito.doThrow(
            new com.loakraken.common.api.BusinessException(
                com.loakraken.common.lostark.LostArkErrorCode.CLIENT_RATE_LIMITED))
        .when(search)
        .search(any());
    assertThat(get(base + "4").statusCode()).isEqualTo(429);
    assertThat(gemFilterQuotaUsed()).isEqualTo(before);
    // 공식 서버가 429를 준 것은 이미 호출한 것이라 몫을 쓴다.
    org.mockito.Mockito.doThrow(
            new com.loakraken.common.lostark.LostArkRateLimitedException(
                com.loakraken.common.lostark.LostArkRateLimitedException.Cause.UPSTREAM_429))
        .when(search)
        .search(any());
    assertThat(get(base + "5").statusCode()).isEqualTo(503);
    assertThat(gemFilterQuotaUsed()).isEqualTo(before + 1);
    // 몫 카운터 키에는 만료가 걸려 있다(올리기와 첫 만료가 한 번에).
    var keys = redis.keys("market:v1:auction-gem-filter-quota:*");
    assertThat(keys).isNotEmpty();
    for (var key : keys) assertThat(redis.getExpire(key)).as(key).isPositive();
  }

  /** 이번 분 앞뒤 키의 몫 사용량 합(분 경계에 걸려도 같은 값을 보게). */
  private long gemFilterQuotaUsed() {
    long minute = System.currentTimeMillis() / 60_000;
    long sum = 0;
    for (long value = minute - 1; value <= minute + 1; value++) {
      var count = redis.opsForValue().get("market:v1:auction-gem-filter-quota:" + value);
      if (count != null) sum += Long.parseLong(count);
    }
    return sum;
  }

  @Test
  void 같은_조건_동시_요청은_공식_검색_하나로_묶는다() throws Exception {
    var started = new java.util.concurrent.CountDownLatch(1);
    when(search.search(any()))
        .thenAnswer(
            invocation -> {
              started.countDown();
              Thread.sleep(500);
              return page(invocation.getArgument(0));
            });
    String path =
        "/api/v1/markets/auctions/gems/listings?kind="
            + encode("겁화")
            + "&level=7&className="
            + encode("데모닉")
            + "&skill=27960";
    double callsBefore = gemFilter("official_call");
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var calls = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
      calls.add(pool.submit(() -> get(path)));
      started.await(5, java.util.concurrent.TimeUnit.SECONDS);
      for (int i = 0; i < 4; i++) calls.add(pool.submit(() -> get(path)));
      for (var call : calls) assertThat(call.get().statusCode()).isEqualTo(200);
    }
    verify(search, times(1)).search(any());
    assertThat(gemFilter("official_call") - callsBefore).isEqualTo(1);
  }

  /** 데모닉 스킬 가운데 겁화(피해 증가)만 있는 스킬 코드(리소스에서 찾는다). */
  private int demonicDamageOnly() throws Exception {
    var data = data("/api/v1/markets/auctions/gems/skills");
    for (var value : data.path("classes"))
      if (value.path("className").asText().equals("데모닉"))
        for (var skill : value.path("skills"))
          if (skill.path("kinds").size() == 1 && skill.at("/kinds/0").asText().equals("겁화"))
            return skill.path("code").asInt();
    throw new AssertionError("겁화만 되는 데모닉 스킬이 없습니다");
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
    // 가짜 검색도 실제 클라이언트처럼 호출마다 공유 게이트를 지난다. 딜러 장신구 표는 품질마다 18칸이고 클라이언트 공식 한도는 분당 20이다.
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
    // 요청마다 최대 5칸: 품질 70 표 18칸을 4번의 요청이 채우고(18회), 품질 90 표 요청이 2칸을 더 채운 뒤(20회, 클라이언트 한도 20)
    // 세 번째 호출이 한도에 걸린다.
    HttpResponse<String> response = null;
    for (int visit = 1; visit <= 4; visit++) {
      response =
          get("/api/v1/markets/auctions/accessories?grade=ANCIENT&role=DEALER", "203.0.113.40");
      assertThat(response.statusCode()).isEqualTo(200);
    }
    assertThat(statuses(response)).containsOnly("READY").hasSize(18);
    response =
        get(
            "/api/v1/markets/auctions/accessories?grade=ANCIENT&role=DEALER&minQuality=90",
            "203.0.113.40");
    assertThat(response.statusCode()).isEqualTo(200);
    var statuses = statuses(response);
    assertThat(statuses).hasSize(18);
    assertThat(statuses.stream().filter("READY"::equals)).hasSize(2);
    assertThat(statuses.stream().filter("PENDING"::equals)).hasSize(16);
    verify(search, times(21)).search(any());
  }

  private List<String> statuses(HttpResponse<String> response) throws Exception {
    var statuses = new ArrayList<String>();
    for (var slot : mapper.readTree(response.body()).at("/data/slots"))
      for (var cell : slot.path("cells")) statuses.add(cell.path("status").asText());
    return statuses;
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

  private static final String ICON = "https://cdn.example/auction_item.png";

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
            new AuctionListing("매물", "고대", 73, 2_500_000L, 0L, 2_500_000L, now, 0, options, ICON),
            new AuctionListing("매물", "고대", 80, 2_600_000L, 0L, 2_600_000L, now, 1, options, null)),
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
