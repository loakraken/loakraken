package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.market.application.AuctionPort;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MaterialUnitPriceService;
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
import tools.jackson.databind.json.JsonMapper;

/** 원정대 영수증(#173). 공식 API·시세는 모두 대역이다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "receipt.enabled=true")
class ReceiptIntegrationTest {
  private static final String SERVER = "영수증테스트서버";
  private static final String MAIN = "영수증본캐";
  private static final Instant OBSERVED = Instant.parse("2026-10-09T08:00:00Z");
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MaterialUnitPriceService materials;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();
  private final List<String> names = new ArrayList<>();

  @BeforeEach
  void 준비() {
    정리();
    names.add(MAIN);
    for (int i = 1; i <= 9; i++) names.add("영수증부캐" + i);
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < names.size(); i++)
      roster.add(new CharacterSibling(names.get(i), SERVER, "바드", 70, new BigDecimal(1750 - i)));
    when(detailsPort.fetchSiblings(MAIN)).thenReturn(roster);
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
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
    when(auctions.gems())
        .thenReturn(
            List.of(
                new MarketAuctionService.GemCell(
                    "겁화",
                    10,
                    "10레벨 겁화의 보석",
                    new AuctionPort.AuctionCell(2_000_000L, 5, OBSERVED.minusSeconds(30)),
                    null,
                    MarketAuctionService.Status.READY)));
  }

  @AfterEach
  void 정리() {
    var keys = redis.keys("character:v*영수증*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  @Test
  void 한_요청에_8명까지_받고_나머지는_PENDING이며_다시_요청하면_이어서_채운다() throws Exception {
    var first = get("/api/v1/characters/receipt?characterName=" + encode(MAIN));
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    var data = mapper.readTree(first.body()).path("data");
    assertThat(data.path("ruleVersion").asText()).isEqualTo("receipt-v2");
    assertThat(data.path("refiningRuleVersion").asText()).isEqualTo("t4-refining-v3");
    assertThat(data.at("/coverage/rosterCharacters").asInt()).isEqualTo(10);
    assertThat(data.at("/coverage/ready").asInt()).isEqualTo(8);
    assertThat(data.at("/coverage/pending").asInt()).isEqualTo(2);
    // 8명 상한으로 멈췄으니 바로 이어 받을 수 있다.
    assertThat(data.at("/coverage/nextAvailableAt").asText()).isNotBlank();
    assertThat(data.path("computedAt").asText()).isNotBlank();
    assertThat(data.at("/equipment/characters/9/status").asText()).isEqualTo("PENDING");
    assertThat(data.at("/equipment/characters/9/gold").isNull()).isTrue();
    var main = data.at("/equipment/characters/0");
    assertThat(main.path("status").asText()).isEqualTo("READY");
    assertThat(main.at("/parts/0/toStage").asInt()).isEqualTo(19);
    assertThat(main.at("/parts/0/stages").size()).isEqualTo(8);
    assertThat(main.path("excluded").toString()).contains("ADVANCED_REFINING");
    // 보석: 캐릭터마다 거래 가능 겁화 10 1개 + 귀속 1개
    assertThat(data.at("/gems/tradable").asInt()).isEqualTo(8);
    assertThat(data.at("/gems/bound").asInt()).isEqualTo(8);
    assertThat(data.at("/gems/gold").decimalValue()).isEqualByComparingTo("16000000");
    assertThat(data.at("/total/gold").decimalValue())
        .isEqualByComparingTo(
            data.at("/equipment/gold").decimalValue().add(data.at("/gems/gold").decimalValue()));
    assertThat(data.path("priceObservedAt").asText()).isEqualTo("2026-10-09T07:59:30Z");
    assertThat(data.path("notIncluded").toString()).contains("ARK_GRID_GEM", "VERIFYING");
    assertThat(data.path("assumptions").size()).isGreaterThan(0);
    verify(detailsPort, times(8)).fetchArmory(anyString());
    ApiContractFixture.write("character-receipt.json", first.body());
    // 다른 사이트 이름·간접 표현 금지(#261).
    assertThat(first.body()).doesNotContain(SiteNames.FORBIDDEN);

    var second = get("/api/v1/characters/receipt?characterName=" + encode(MAIN));
    var again = mapper.readTree(second.body()).path("data");
    assertThat(again.at("/coverage/ready").asInt()).isEqualTo(10);
    assertThat(again.at("/coverage/nextAvailableAt").isNull()).isTrue();
    // 앞의 8명은 캐시를 쓰고 남은 2명만 새로 받는다.
    verify(detailsPort, times(10)).fetchArmory(anyString());

    // 기본은 책 포함(#279, PM 총괄 10-11 결정), false를 명시하면 책 제외.
    assertThat(data.at("/assumptions/0").asText()).contains("책 효과는 항상 적용된다고 가정합니다");
    var withoutBook =
        mapper
            .readTree(
                get("/api/v1/characters/receipt?assumeBook=false&characterName=" + encode(MAIN))
                    .body())
            .path("data");
    assertThat(withoutBook.at("/assumptions/0").asText()).contains("넣지 않습니다");
  }

  @Test
  void 잘못된_이름은_400이다() throws Exception {
    assertThat(get("/api/v1/characters/receipt?characterName=" + encode("a-b")).statusCode())
        .isEqualTo(400);
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
                equipment("무기", "+19 운명의 전율 검", 19),
                equipment("투구", "+18 운명의 업화 머리장식", 18),
                equipment("완갑", "+13 운명의 전율 완갑", 13)),
            null,
            new CharacterLoadout.Gems(
                List.of(
                    new CharacterLoadout.Gem(0, "10레벨 겁화의 보석", null, 10, "고대"),
                    new CharacterLoadout.Gem(1, "8레벨 작열의 보석 (귀속)", null, 8, "유물")),
                null),
            null,
            null,
            null,
            List.of());
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }

  private static CharacterLoadout.Equipment equipment(String type, String name, int refine) {
    return new CharacterLoadout.Equipment(
        type,
        name,
        null,
        "고대",
        new CharacterLoadout.EquipmentDetail(
            refine, 100, 1700, 4, null, List.of(), List.of(), List.of(), List.of()));
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
