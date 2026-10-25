package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MaterialUnitPriceService;
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

/**
 * 영수증 이어 받기와 방문자 공식 호출 한도: 원정대 20명을 이어 받는 동안 방문자의 분당 공식 호출 칸(20)을 넘지 않고, 다른 조회 몫이 늘 남는다. 칸이 모자라면 이어
 * 받을 시각은 칸이 다시 차는 분 경계다. 공식 API는 대역이지만 호출마다 실제 게이트를 지나 방문자 칸에 매긴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "receipt.enabled=true",
      "lostark.client-limit.exempt-internal=false",
      "lostark.client-limit.official-per-minute=20",
      "lostark.requests-per-minute=600"
    })
class ReceiptClientBudgetIntegrationTest {
  private static final String SERVER = "영수증예산서버";
  private static final String PREFIX = "영수증예산원정";
  private static final String MAIN = PREFIX + "0";
  private static final String SEARCH = "영수증예산검색";
  private static final int ROSTER = 20;
  private static final Instant OBSERVED = Instant.parse("2026-10-09T08:00:00Z");
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @Autowired LostArkRequestGate gate;
  @Autowired MeterRegistry registry;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MaterialUnitPriceService materials;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  void 준비() {
    정리();
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < ROSTER; i++)
      roster.add(new CharacterSibling(PREFIX + i, SERVER, "바드", 70, new BigDecimal(1750 - i)));
    when(detailsPort.fetchSiblings(MAIN)).thenAnswer(invocation -> official(() -> roster));
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> official(() -> character(invocation.getArgument(0))));
    when(materials.prices(any(), anyList()))
        .thenAnswer(
            invocation -> {
              List<String> wanted = invocation.getArgument(1);
              return wanted.stream()
                  .map(
                      name ->
                          new MaterialUnitPriceService.UnitPrice(
                              name,
                              MaterialUnitPriceService.UnitPrice.Status.PRICED,
                              BigDecimal.ONE,
                              name,
                              1,
                              BigDecimal.ONE,
                              null,
                              OBSERVED))
                  .toList();
            });
    when(auctions.gems()).thenReturn(List.of());
  }

  @AfterEach
  void 정리() {
    var keys = new ArrayList<String>();
    for (var pattern :
        List.of("character:v*영수증예산*", "receipt:v1:input:*영수증예산*", "lostark:client-limit:*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
    for (var table :
        List.of(
            "character_card",
            "character_title_observation",
            "character_observation",
            "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name = :s)")
          .param("s", SERVER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM character_popular_hourly WHERE server_name = :s")
        .param("s", SERVER)
        .update();
    jdbc.sql("DELETE FROM character_view_daily WHERE character_name LIKE '영수증예산%'").update();
  }

  @Test
  void 원정대_20명을_이어_받아도_방문자_공식_호출_한도에_걸리지_않고_다른_조회_몫이_남는다() throws Exception {
    double rejectsBefore = rejects();
    // 방문자가 방금 다른 캐릭터를 8번 검색했다(공식 호출 8번).
    int searches = 0;
    for (; searches < 8; searches++) assertThat(search(SEARCH + searches)).isEqualTo(200);

    JsonNode last = null;
    int refills = 0;
    for (int round = 0; round < 40; round++) {
      last = receipt();
      // 이어 받는 중에도 다른 조회 하나는 늘 거절되지 않는다.
      assertThat(search(SEARCH + searches++)).isEqualTo(200);
      if (last.at("/coverage/pending").asInt() == 0) break;
      var next = Instant.parse(last.at("/coverage/nextAvailableAt").asText());
      if (next.isAfter(Instant.now().plusSeconds(1))) {
        // 칸이 다시 차는 시각은 1분 창의 끝(분 경계)이다. 기다리는 대신 창이 넘어간 것처럼 방문자 칸을 비운다.
        assertThat(next.getEpochSecond() % 60).isZero();
        var keys = redis.keys("lostark:client-limit:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        refills++;
      }
    }

    assertThat(last.at("/coverage/pending").asInt()).isZero();
    assertThat(last.at("/coverage/ready").asInt()).isEqualTo(ROSTER);
    assertThat(last.at("/coverage/nextAvailableAt").isNull()).isTrue();
    // 한 창(20번)에 다 받을 수 없으니 칸이 다시 차기를 적어도 한 번 기다렸다.
    assertThat(refills).isPositive();
    // 방문자 공식 호출 칸 거절이 한 번도 없었다(영수증 안의 호출도, 사이사이 검색도).
    assertThat(rejects()).isEqualTo(rejectsBefore);
    verify(detailsPort, times(ROSTER)).fetchArmory(startsWith(PREFIX));
  }

  @Test
  void 칸이_넉넉하면_이어_받을_시각은_지금이다() throws Exception {
    var first = receipt();
    assertThat(first.at("/coverage/ready").asInt()).isEqualTo(2);
    var next = Instant.parse(first.at("/coverage/nextAvailableAt").asText());
    assertThat(next).isBeforeOrEqualTo(Instant.now());
  }

  private double rejects() {
    var counter =
        registry
            .find("loakraken.client.limit")
            .tags("bucket", "official", "event", "reject")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /** 공식 호출 하나: 실제 게이트를 지나 방문자 칸에 매긴다(대역 응답). */
  private <T> T official(java.util.function.Supplier<T> response) {
    gate.acquire();
    try {
      return response.get();
    } finally {
      gate.release();
    }
  }

  private JsonNode receipt() throws Exception {
    var response = get("/api/v1/characters/receipt?characterName=" + encode(MAIN));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private int search(String name) throws Exception {
    return get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(name))
        .statusCode();
  }

  private FetchedCharacter character(String name) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "바드",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            List.of());
    var loadout =
        new CharacterLoadout(
            List.of(
                new CharacterLoadout.Equipment(
                    "투구",
                    "+18 운명의 업화 머리장식",
                    null,
                    "고대",
                    new CharacterLoadout.EquipmentDetail(
                        18, 100, 1700, 4, null, List.of(), List.of(), List.of(), List.of()))),
            null,
            null,
            null,
            null,
            null,
            List.of());
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
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
