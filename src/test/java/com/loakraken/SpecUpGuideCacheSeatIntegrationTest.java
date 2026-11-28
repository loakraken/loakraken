package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.MarketAuctionService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 스펙업 목록 계산 자리·캐시(보안 v0.3.16 리드: 목록을 요청 스레드에서 자리·캐시 없이 만들었다). 같은 입력의 목록은 60초 캐시이고 limit만 바꿔도 다시 만들지
 * 않는다(전체를 두고 자름). 같은 주소에서 다른 목록을 만드는 중이면 429 SPEC_UP_LIST_BUSY(Retry-After)다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spec-up.enabled=true",
      "kraken-score.enabled=true",
      // 운영(처리기 2개)과 같은 인스턴스 자리 1개.
      "spec-up.list.max-concurrent=1",
      // 연마 시세 채우기 켬: 비어 있는 칸은 PENDING(채우는 중) 비용이 된다.
      "spec-up.polish-price-fill.enabled=true"
    })
@DirtiesContext
class SpecUpGuideCacheSeatIntegrationTest {
  private static final String SERVER = "목록자리서버";
  private static final String NAME = "목록자리";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired MeterRegistry meters;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("*목록자리*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  private double outcome(String outcome) {
    return meters.counter("loakraken.spec_up.guide", "outcome", outcome).count();
  }

  @Test
  void 같은_입력의_목록은_캐시하고_limit만_바꾸면_다시_만들지_않는다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    search();
    double computed = outcome("computed");
    double hits = outcome("cache_hit");

    var five = guide("EFFICIENCY", 5);
    var fiveAgain = guide("EFFICIENCY", 5);
    var two = guide("EFFICIENCY", 2);

    assertThat(outcome("computed") - computed).isEqualTo(1);
    assertThat(outcome("cache_hit") - hits).isEqualTo(2);
    // 만든 시간은 분포(히스토그램)로 남긴다 — 운영에서 p95·p99로 자리 수를 조절한다.
    var build = meters.find("loakraken.spec_up.guide_build").timer();
    assertThat(build).isNotNull();
    assertThat(build.count()).isPositive();
    assertThat(fiveAgain).isEqualTo(five);
    // limit만 다른 목록: 순위에 든 후보는 앞에서 limit개, 순위 밖 후보는 그대로(limit으로 새로 만든 목록과 같은 모양).
    int total = five.path("total").asInt();
    assertThat(two.path("total").asInt()).isEqualTo(total);
    assertThat(two.path("limit").asInt()).isEqualTo(2);
    var fiveRows = ids(five);
    var twoRows = ids(two);
    int rankedFive = Math.min(5, total);
    int rankedTwo = Math.min(2, total);
    assertThat(twoRows.subList(0, rankedTwo)).isEqualTo(fiveRows.subList(0, rankedTwo));
    assertThat(twoRows.subList(rankedTwo, twoRows.size()))
        .isEqualTo(fiveRows.subList(rankedFive, fiveRows.size()));
  }

  @Test
  void 같은_주소에서_목록을_만드는_중이면_다른_목록은_잠깐_기다린_뒤_429다() throws Exception {
    var calls = new AtomicInteger();
    var building = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(auctions.cachedGems())
        .thenAnswer(
            invocation -> {
              // 첫 목록의 두 번째 읽기(키 다음, 만들기 안) — 자리를 쥔 채 멈춘다.
              if (calls.incrementAndGet() == 2) {
                building.countDown();
                release.await(10, TimeUnit.SECONDS);
              }
              return List.of();
            });
    search();
    calls.set(0);
    double busy = outcome("client_busy");
    var pool = Executors.newSingleThreadExecutor();
    var first = pool.submit(() -> get(path("EFFICIENCY", 5)));
    assertThat(building.await(10, TimeUnit.SECONDS)).isTrue();

    // 공유 주소(PC방·통신사)라 바로 거절하지 않고 0.8초까지 기다린다(PM 총괄 조건 1).
    long started = System.nanoTime();
    var second = get(path("DELTA", 5));
    long waitedMs = (System.nanoTime() - started) / 1_000_000;

    release.countDown();
    assertThat(waitedMs).isGreaterThanOrEqualTo(700);
    assertThat(second.statusCode()).as(second.body()).isEqualTo(429);
    assertThat(mapper.readTree(second.body()).path("code").asText()).isEqualTo("SPEC_UP_LIST_BUSY");
    assertThat(second.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(outcome("client_busy") - busy).isEqualTo(1);
    assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    // 자리를 돌려준 뒤에는 다시 만든다.
    assertThat(get(path("DELTA", 5)).statusCode()).isEqualTo(200);
    pool.shutdown();
  }

  @Test
  void 목록이_읽은_연마_칸이_바뀌면_캐시를_쓰지_않고_다시_만든다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    // 합성 연마 시세: 줄 수치 합에 비례 × 배수. 배수를 바꾸면 목표 − 지금 시세(비용)가 바뀐다.
    var scale = new AtomicLong(1);
    var observed = Instant.parse("2026-11-28T12:00:00Z");
    org.mockito.stubbing.Answer<MarketAuctionService.PolishPrice> cell =
        invocation -> {
          List<MarketAuctionService.PolishLine> lines = invocation.getArgument(1);
          long price = 10_000;
          for (var line : lines)
            if (!line.value().isBlank())
              price += (long) (Double.parseDouble(line.value().replaceAll("[+,%]", "")) * 1_000);
          return new MarketAuctionService.PolishPrice(
              MarketAuctionService.PolishStatus.OK, price * scale.get(), observed);
        };
    when(auctions.polishPrice(anyString(), anyList(), any())).thenAnswer(cell);
    when(auctions.cachedPolishPrice(anyString(), anyList())).thenAnswer(cell);
    search(true);
    double computed = outcome("computed");
    double hits = outcome("cache_hit");

    var before = guide("EFFICIENCY", 5);
    guide("EFFICIENCY", 5);
    // 연마 칸 값만 바뀜(보석·재료·각인서·캐릭터 그대로) — 옛 값으로 만든 목록을 주면 안 된다(코드 리뷰 #1146 P1-1).
    scale.set(2);
    var after = guide("EFFICIENCY", 5);

    verify(auctions, atLeastOnce()).polishPrice(anyString(), anyList(), any());
    assertThat(outcome("cache_hit") - hits).isEqualTo(1);
    assertThat(outcome("computed") - computed).isEqualTo(2);
    assertThat(after).isNotEqualTo(before);
  }

  @Test
  void 연마_시세를_채우는_중인_목록은_캐시하지_않아_다음_요청이_다시_채운다() throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    var filled = new java.util.concurrent.atomic.AtomicBoolean(false);
    var observed = Instant.parse("2026-11-28T12:00:00Z");
    org.mockito.stubbing.Answer<MarketAuctionService.PolishPrice> cell =
        invocation ->
            filled.get()
                ? new MarketAuctionService.PolishPrice(
                    MarketAuctionService.PolishStatus.OK, 50_000L, observed)
                : new MarketAuctionService.PolishPrice(
                    MarketAuctionService.PolishStatus.MISSING, null, null);
    when(auctions.polishPrice(anyString(), anyList(), any())).thenAnswer(cell);
    when(auctions.cachedPolishPrice(anyString(), anyList())).thenAnswer(cell);
    search(true);
    double computed = outcome("computed");
    double hits = outcome("cache_hit");
    double skipped = outcome("not_cached");

    // 비어 있는 칸(PENDING) — 캐시에 두면 다음 요청이 채우기를 다시 돌리지 않는다(계산 검증 #1146).
    guide("EFFICIENCY", 5);
    guide("EFFICIENCY", 5);
    filled.set(true);
    guide("EFFICIENCY", 5);
    guide("EFFICIENCY", 5);

    assertThat(outcome("not_cached") - skipped).isEqualTo(2);
    assertThat(outcome("computed") - computed).isEqualTo(3);
    assertThat(outcome("cache_hit") - hits).isEqualTo(1);
  }

  @Test
  void 만드는_동안_입력이_바뀌면_그_목록은_캐시하지_않는다() throws Exception {
    var calls = new AtomicInteger();
    var pending =
        new MarketAuctionService.GemCell(
            "겁화", 10, "10레벨 겁화의 보석", null, null, MarketAuctionService.Status.PENDING, false);
    // 첫 읽기(만들기 전 지문)만 빈 보석 칸, 그 뒤(만들기 안·만든 뒤 지문)는 칸 하나 — 만드는 동안 시세 칸이 바뀐 것.
    when(auctions.cachedGems())
        .thenAnswer(invocation -> calls.incrementAndGet() == 1 ? List.of() : List.of(pending));
    search();
    calls.set(0);
    double computed = outcome("computed");
    double hits = outcome("cache_hit");
    double skipped = outcome("not_cached");

    guide("EFFICIENCY", 5);
    // 바뀐 입력의 키로 옛 입력의 숫자를 두지 않았으므로 다음 요청도 새로 만든다(코드 리뷰 #1146 P1-2). 그 뒤로는 입력이 그대로라 캐시한다.
    guide("EFFICIENCY", 5);
    guide("EFFICIENCY", 5);

    assertThat(outcome("not_cached") - skipped).isEqualTo(1);
    assertThat(outcome("computed") - computed).isEqualTo(2);
    assertThat(outcome("cache_hit") - hits).isEqualTo(1);
  }

  @Test
  void 같은_목록을_만드는_중이면_따라온_요청도_같은_기한까지만_기다리고_429다() throws Exception {
    var calls = new AtomicInteger();
    var building = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(auctions.cachedGems())
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                building.countDown();
                release.await(10, TimeUnit.SECONDS);
              }
              return List.of();
            });
    search();
    calls.set(0);
    double busy = outcome("busy");
    var pool = Executors.newSingleThreadExecutor();
    var first = pool.submit(() -> get(path("EFFICIENCY", 5), "203.0.113.41"));
    assertThat(building.await(10, TimeUnit.SECONDS)).isTrue();

    // 다른 주소, 같은 입력: 만드는 요청을 따라가되 0.8초 기한을 넘기면 기다리지 않는다(코드 리뷰 #1146 P2).
    long started = System.nanoTime();
    var follower = get(path("EFFICIENCY", 5), "203.0.113.42");
    long waitedMs = (System.nanoTime() - started) / 1_000_000;

    release.countDown();
    assertThat(follower.statusCode()).as(follower.body()).isEqualTo(429);
    assertThat(mapper.readTree(follower.body()).path("code").asText())
        .isEqualTo("SPEC_UP_LIST_BUSY");
    assertThat(follower.headers().firstValue("Retry-After")).hasValue("1");
    assertThat(waitedMs).isBetween(700L, 3_000L);
    assertThat(outcome("busy") - busy).isEqualTo(1);
    assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    pool.shutdown();
  }

  private static List<String> ids(JsonNode guide) {
    var ids = new ArrayList<String>();
    for (var candidate : guide.path("candidates")) ids.add(candidate.path("id").asText());
    return ids;
  }

  private String path(String sort, int limit) {
    return "/api/v1/characters/spec-up?characterName="
        + URLEncoder.encode(NAME, StandardCharsets.UTF_8)
        + "&sort="
        + sort
        + "&limit="
        + limit;
  }

  private JsonNode guide(String sort, int limit) throws Exception {
    var response = get(path(sort, limit));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private void search() throws Exception {
    search(false);
  }

  /** tradeLines: 장신구 툴팁에 거래 줄을 합성으로 넣는다 — 이 원문은 거래 줄이 잘려 있어 그대로면 연마 비용을 정하지 못해 연마 시세를 읽지 않는다. */
  private void search(boolean tradeLines) throws Exception {
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    if (tradeLines)
      for (var equipment : raw.path("ArmoryEquipment")) {
        if (!List.of("목걸이", "귀걸이", "반지").contains(equipment.path("Type").asText())) continue;
        ((tools.jackson.databind.node.ObjectNode) equipment)
            .put("Tooltip", withTradeLine(equipment.path("Tooltip").asText()));
      }
    var profile =
        new CharacterProfile(
            NAME,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1770.00"),
            new BigDecimal("4070.75"),
            null,
            null,
            null,
            null,
            List.of());
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    assertThat(
            get("/api/v1/characters/search?characterName="
                    + URLEncoder.encode(NAME, StandardCharsets.UTF_8))
                .statusCode())
        .isEqualTo(200);
  }

  /** 툴팁 JSON의 ItemTitle 바로 뒤에 거래 제한 줄(SingleTextBox)을 넣고 Element 번호를 다시 매긴다. */
  private String withTradeLine(String tooltip) {
    var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(tooltip);
    var elements = new ArrayList<JsonNode>();
    root.forEach(elements::add);
    var out = mapper.createObjectNode();
    int n = 0;
    for (var element : elements) {
      out.set(String.format("Element_%03d", n++), element);
      if ("ItemTitle".equals(element.path("type").asText())) {
        var trade = mapper.createObjectNode();
        trade.put("type", "SingleTextBox");
        trade.put("value", "<FONT SIZE='12'>거래 제한 아이템 레벨 1680</FONT>");
        out.set(String.format("Element_%03d", n++), trade);
      }
    }
    return mapper.writeValueAsString(out);
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return get(pathAndQuery, "203.0.113.41");
  }

  private HttpResponse<String> get(String pathAndQuery, String address) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            // 루프백(신뢰 프록시)에서 공개 주소를 넘긴다 — 운영의 웹 → 백엔드 경로처럼 주소 자리를 센다.
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .header("X-Forwarded-For", address)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
