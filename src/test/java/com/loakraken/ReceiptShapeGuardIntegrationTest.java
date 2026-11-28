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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원정대 영수증의 2부 대비 막기(계약 2026-11-28, PM 총괄 결정): 막힌 캐릭터는 UNAVAILABLE + unavailableReasonCode
 * GAME_UPDATE_UNSUPPORTED이고 장비·보석 골드에 넣지 않는다. 스위치는 읽을 때 적용되고(다시 조회하지 않음), 영수증 끄기 스위치는 모두를 막는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"receipt.enabled=true", "receipt.max-fetch=8"})
@DirtiesContext
class ReceiptShapeGuardIntegrationTest {
  private static final String SERVER = "영수증가드서버";
  private static final String MAIN = "영수증가드본캐";
  private static final String ALT = "영수증가드부캐";
  private static final String CODE = "GAME_UPDATE_UNSUPPORTED";
  private static final String SWITCHES = "receipt-shape-guard-test";
  private static final Instant OBSERVED = Instant.parse("2026-11-28T00:00:00Z");
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @Autowired ConfigurableEnvironment environment;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MaterialUnitPriceService materials;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  void 준비() {
    정리();
    when(detailsPort.fetchSiblings(MAIN))
        .thenReturn(
            List.of(
                new CharacterSibling(MAIN, SERVER, "바드", 70, new BigDecimal("1750")),
                new CharacterSibling(ALT, SERVER, "바드", 70, new BigDecimal("1740"))));
    // 본캐는 원본 형태 판정이 어긋났고(H1), 부캐는 오늘의 모양이다.
    when(detailsPort.fetchArmory(MAIN)).thenReturn(character(MAIN, List.of("H1_UNKNOWN_PART")));
    when(detailsPort.fetchArmory(ALT)).thenReturn(character(ALT, List.of()));
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
    environment.getPropertySources().remove(SWITCHES);
    var keys = redis.keys("character:v*영수증가드*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    var inputs = redis.keys("receipt:v1:input:*영수증가드*");
    if (inputs != null && !inputs.isEmpty()) redis.delete(inputs);
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
  }

  private void switches(Map<String, Object> values) {
    environment.getPropertySources().remove(SWITCHES);
    environment.getPropertySources().addFirst(new MapPropertySource(SWITCHES, values));
  }

  @Test
  void 막기를_켜면_어긋난_캐릭터만_골드에서_빠지고_끄기_스위치는_모두를_막는다() throws Exception {
    var open = receipt();
    assertThat(open.toString()).doesNotContain(CODE);
    assertThat(open.at("/total/status").asText()).isEqualTo("COMPLETE");
    var alt = character(open, ALT);
    assertThat(alt.path("status").asText()).isEqualTo("READY");
    assertThat(alt.has("unavailableReasonCode")).isTrue();
    assertThat(alt.path("unavailableReasonCode").isNull()).isTrue();
    assertThat(character(open, MAIN).path("status").asText()).isEqualTo("READY");

    switches(Map.of("combat-power.raw-shape-guard.enforce", "true"));
    var guarded = receipt();
    var main = character(guarded, MAIN);
    assertThat(main.path("status").asText()).isEqualTo("UNAVAILABLE");
    assertThat(main.path("unavailableReasonCode").asText()).isEqualTo(CODE);
    assertThat(main.path("gold").isNull()).isTrue();
    assertThat(main.path("parts").size()).isZero();
    assertThat(character(guarded, ALT).path("status").asText()).isEqualTo("READY");
    assertThat(character(guarded, ALT).path("unavailableReasonCode").isNull()).isTrue();
    // 본캐 몫(장비·보석)이 빠져 합계는 부캐 것만이고 일부만 센 값이다.
    assertThat(guarded.at("/equipment/gold").decimalValue())
        .isEqualByComparingTo(character(open, ALT).path("gold").decimalValue());
    assertThat(guarded.at("/gems/gold").decimalValue())
        .isLessThan(open.at("/gems/gold").decimalValue());
    assertThat(guarded.at("/total/status").asText()).isEqualTo("PARTIAL");
    // 판정은 캐시·보관 입력에 있고 스위치는 읽을 때 적용된다(다시 받지 않음).
    verify(detailsPort, times(2)).fetchArmory(anyString());

    switches(Map.of("combat-power.kill.receipt", "true"));
    var killed = receipt();
    for (var name : List.of(MAIN, ALT)) {
      assertThat(character(killed, name).path("status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(character(killed, name).path("unavailableReasonCode").asText()).isEqualTo(CODE);
    }
    assertThat(killed.at("/total/gold").decimalValue()).isZero();
    assertThat(killed.at("/total/status").asText()).isEqualTo("UNAVAILABLE");

    environment.getPropertySources().remove(SWITCHES);
    assertThat(receipt().toString()).doesNotContain(CODE);
  }

  /** 코덱스 P2: 영수증 끄기는 받기 전에 끝난다 — 캐시에 없는 대원도 공식 조회 없이 UNAVAILABLE + 이유, 대기·이어 받을 시각 없음. */
  @Test
  void 영수증_끄기는_캐시에_없는_대원을_받지_않고_모두_막는다() throws Exception {
    switches(Map.of("combat-power.kill.receipt", "true"));

    var killed = receipt();

    for (var name : List.of(MAIN, ALT)) {
      assertThat(character(killed, name).path("status").asText()).isEqualTo("UNAVAILABLE");
      assertThat(character(killed, name).path("unavailableReasonCode").asText()).isEqualTo(CODE);
    }
    assertThat(killed.at("/coverage/pending").asInt()).isZero();
    assertThat(killed.at("/coverage/nextAvailableAt").isNull()).isTrue();
    assertThat(killed.at("/total/status").asText()).isEqualTo("UNAVAILABLE");
    verify(detailsPort, never()).fetchArmory(anyString());
  }

  private JsonNode receipt() throws Exception {
    var response = get("/api/v1/characters/receipt?characterName=" + encode(MAIN));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private static JsonNode character(JsonNode receipt, String name) {
    for (var row : receipt.at("/equipment/characters"))
      if (name.equals(row.path("characterName").asText())) return row;
    throw new AssertionError("no " + name + " in " + receipt);
  }

  private FetchedCharacter character(String name, List<String> verdict) {
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
            List.of(equipment("무기", "+19 운명의 전율 검", 19), equipment("투구", "+18 운명의 업화 머리장식", 18)),
            null,
            new CharacterLoadout.Gems(
                List.of(new CharacterLoadout.Gem(0, "10레벨 겁화의 보석", null, 10, "고대")), null),
            null,
            null,
            null,
            List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, null), "{}", null, null, verdict);
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
