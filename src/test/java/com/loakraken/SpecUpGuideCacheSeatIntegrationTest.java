package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
      "spec-up.list.max-concurrent=1"
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
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
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

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            // 루프백(신뢰 프록시)에서 공개 주소를 넘긴다 — 운영의 웹 → 백엔드 경로처럼 주소 자리를 센다.
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .header("X-Forwarded-For", "203.0.113.41")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
