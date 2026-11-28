package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.infrastructure.ArmoryRawFixtures;
import com.loakraken.market.application.MarketAuctionService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 시뮬레이터 카르마 랭크·레벨 입력(계약 초안 2026-11-22, 프론트 검토 OK, 계산 검증 5a 판정). ARK_PASSIVE_POINT에 karmaRank, 도약
 * karmaLevel. 깨달음 랭크 +1·도약 랭크 +2 포인트(공식 가이드), 진화 랭크 × (1 + 0.006·랭크)·도약 레벨 × (1 + 0.0002·레벨)은 모델값
 * (modelValue true). 기준 원문 l01 딜러(진화 140 6랭크 21레벨, 깨달음 101 6랭크 29레벨, 도약 70 6랭크 27레벨). 공식 호출 0.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"combat-power-sim.enabled=true", "spec-up.karma-cp.enabled=true"})
class SimulateKarmaInputsIntegrationTest {
  private static final String SERVER = "카르마입력서버";
  private static final String DEALER = "카르마입력딜러";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MarketAuctionService auctions;
  @Autowired com.loakraken.character.application.CombatPowerSimulationService simulator;

  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("*카르마입력*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 깨달음_랭크는_포인트를_1씩_옮기고_100_상한을_지나고_모델값이_아니다() throws Exception {
    search(raw(Map.of()));
    // 101 → 100(랭크 6 → 5)은 100 상한 때문에 Δ 0, 6 → 4(99)는 포인트 99와 같은 값(1.693/1.70).
    var five = row(simulate("[" + rank("깨달음", 5) + "]"));
    assertThat(five.path("status").asString()).as(five.toString()).isEqualTo("OK");
    assertThat(five.path("delta").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(five.path("from").asString()).isEqualTo("6");
    assertThat(five.path("to").asString()).isEqualTo("5");
    assertThat(five.path("modelValue").isBoolean()).as(five.toString()).isTrue();
    assertThat(five.path("modelValue").asBoolean()).isFalse();
    var four = data(simulate("[" + rank("깨달음", 4) + "]"));
    var points = data(simulate("[" + points("깨달음", 99) + "]"));
    assertThat(four.path("delta").decimalValue())
        .isEqualByComparingTo(points.path("delta").decimalValue());
    assertThat(four.path("deltaPercent").doubleValue()).isCloseTo(-0.412, within(0.001));
  }

  @Test
  void 도약_랭크는_포인트를_2씩_옮기고_같은_포인트_변경과_같다() throws Exception {
    search(raw(Map.of()));
    var rank = data(simulate("[" + rank("도약", 5) + "]"));
    var points = data(simulate("[" + points("도약", 68) + "]"));
    assertThat(rank.path("partial").asBoolean()).as(rank.toString()).isFalse();
    assertThat(rank.path("delta").decimalValue())
        .isEqualByComparingTo(points.path("delta").decimalValue());
    assertThat(rank.at("/contributions/0/modelValue").asBoolean()).isFalse();
  }

  @Test
  void 진화_랭크와_도약_레벨은_독립_배수의_모델값이다() throws Exception {
    search(raw(Map.of()));
    var evolution = data(simulate("[" + rank("진화", 5) + "]"));
    assertThat(evolution.path("partial").asBoolean()).as(evolution.toString()).isFalse();
    // (1 + 0.006·5) / (1 + 0.006·6) − 1 = −0.579%
    assertThat(evolution.path("deltaPercent").doubleValue()).isCloseTo(-0.579, within(0.001));
    assertThat(evolution.at("/contributions/0/modelValue").asBoolean()).isTrue();
    assertThat(evolution.at("/contributions/0/basis").asString()).contains("모델값");
    var leap = data(simulate("[" + level("도약", 30) + "]"));
    assertThat(leap.path("partial").asBoolean()).as(leap.toString()).isFalse();
    // (1 + 0.0002·30) / (1 + 0.0002·27) − 1 = +0.060%
    assertThat(leap.path("deltaPercent").doubleValue()).isCloseTo(0.060, within(0.001));
    assertThat(leap.at("/contributions/0/modelValue").asBoolean()).isTrue();
    assertThat(leap.at("/contributions/0/from").asString()).isEqualTo("27");
    // 진화 포인트(기울기 모델값)도 modelValue true, 깨달음 카르마 레벨은 false.
    var evolutionPoints = data(simulate("[" + points("진화", 130) + "]"));
    assertThat(evolutionPoints.at("/contributions/0/modelValue").asBoolean()).isTrue();
    var enlightenmentLevel = data(simulate("[" + level("깨달음", 30) + "]"));
    assertThat(enlightenmentLevel.at("/contributions/0/modelValue").isBoolean()).isTrue();
    assertThat(enlightenmentLevel.at("/contributions/0/modelValue").asBoolean()).isFalse();
  }

  @Test
  void 랭크는_같은_요청의_앞_변경_뒤_포인트에_더한다() throws Exception {
    search(raw(Map.of()));
    var sequence = data(simulate("[" + points("깨달음", 95) + "," + rank("깨달음", 5) + "]"));
    var single = data(simulate("[" + points("깨달음", 94) + "]"));
    assertThat(sequence.path("partial").asBoolean()).as(sequence.toString()).isFalse();
    assertThat(sequence.path("after").decimalValue())
        .isEqualByComparingTo(single.path("after").decimalValue());
    assertThat(sequence.at("/contributions/1/from").asString()).isEqualTo("6");
    assertThat(sequence.at("/contributions/1/to").asString()).isEqualTo("5");
  }

  /**
   * 포인트 합이 범위(깨달음 0~101, 도약 0~70)를 벗어나는 랭크 변경은 400 KARMA_POINTS_OUT_OF_RANGE(웹은 코드로 문구를 고른다 —
   * 프론트·프론트2 2026-11-22, PM 총괄 결정). 랭크 자체가 0~6 밖이면 그대로 INVALID_REQUEST.
   */
  @Test
  void 포인트가_범위를_넘는_랭크_변경은_400이고_자르지_않는다() throws Exception {
    // 도약 70에서 4랭크로 읽히면 5랭크는 72 > 70, 깨달음 101에서 4랭크면 5랭크는 102 > 101.
    search(raw(Map.of("도약", "4랭크 27레벨", "깨달음", "4랭크 29레벨")));
    assertKarmaPointsOutOfRange(simulate("[" + rank("도약", 5) + "]"));
    assertKarmaPointsOutOfRange(simulate("[" + rank("깨달음", 5) + "]"));
    search(raw(Map.of()));
    // 0 아래: 깨달음 3 → 랭크 6 → 0은 −3.
    assertKarmaPointsOutOfRange(simulate("[" + points("깨달음", 3) + "," + rank("깨달음", 0) + "]"));
    // 랭크 자체가 범위 밖이면 일반 입력 오류.
    var badRank = simulate("[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"깨달음\",\"karmaRank\":7}]");
    assertThat(badRank.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(badRank.body()).path("code").asString())
        .isEqualTo("INVALID_REQUEST");
  }

  private void assertKarmaPointsOutOfRange(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    var body = mapper.readTree(response.body());
    assertThat(body.path("code").asString())
        .as(response.body())
        .isEqualTo("KARMA_POINTS_OUT_OF_RANGE");
    assertThat(body.path("message").asString()).isNotBlank();
  }

  @Test
  void 잘못된_입력은_400이다() throws Exception {
    search(raw(Map.of()));
    for (var bad :
        List.of(
            "[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"진화\",\"karmaRank\":7}]",
            "[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"진화\",\"karmaRank\":-1}]",
            "[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"진화\",\"karmaRank\":5,\"points\":130}]",
            "[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"도약\",\"karmaRank\":5,\"karmaLevel\":20}]",
            "[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"도약\",\"karmaLevel\":31}]",
            "[{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"진화\",\"karmaLevel\":20}]",
            "[{\"kind\":\"GEM_LEVEL\",\"slot\":0,\"level\":9,\"karmaRank\":5}]")) {
      var response = simulate(bad);
      assertThat(response.statusCode()).as(bad + " " + response.body()).isEqualTo(400);
    }
  }

  @Test
  void 랭크를_읽지_못하거나_스위치가_꺼져_있으면_계산하지_않는다() throws Exception {
    search(raw(Map.of("진화", "")));
    var unread = data(simulate("[" + rank("진화", 5) + "]"));
    assertThat(unread.at("/contributions/0/status").asString()).isEqualTo("UNSUPPORTED");
    assertThat(unread.at("/contributions/0/reasonCode").asString()).isNotBlank();
    assertThat(unread.path("delta").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    search(raw(Map.of()));
    org.springframework.test.util.ReflectionTestUtils.setField(simulator, "karmaCpEnabled", false);
    try {
      for (var change : List.of(rank("진화", 5), rank("도약", 5), level("도약", 30))) {
        var off = data(simulate("[" + change + "]"));
        assertThat(off.at("/contributions/0/status").asString())
            .as(change)
            .isEqualTo("UNSUPPORTED");
        assertThat(off.path("delta").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
      }
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(simulator, "karmaCpEnabled", true);
    }
  }

  @Test
  void 검색_응답의_아크_패시브_포인트에_카르마_랭크를_준다() throws Exception {
    var body = search(raw(Map.of()));
    var points = mapper.readTree(body).at("/data/loadout/arkPassive/points");
    assertThat(points.isArray()).as(body).isTrue();
    assertThat(points.size()).isEqualTo(3);
    for (var point : points) {
      assertThat(point.path("karmaRank").isInt()).as(point.toString()).isTrue();
      assertThat(point.path("karmaRank").asInt()).isEqualTo(6);
    }
  }

  private static String rank(String tree, int rank) {
    return "{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"" + tree + "\",\"karmaRank\":" + rank + "}";
  }

  private static String level(String tree, int level) {
    return "{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\""
        + tree
        + "\",\"karmaLevel\":"
        + level
        + "}";
  }

  private static String points(String tree, int points) {
    return "{\"kind\":\"ARK_PASSIVE_POINT\",\"tree\":\"" + tree + "\",\"points\":" + points + "}";
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private JsonNode row(HttpResponse<String> response) throws Exception {
    return data(response).path("contributions").get(0);
  }

  /** l01 원문, 트리 이름 → 바꿀 포인트 설명(없으면 그대로). */
  private JsonNode raw(Map<String, String> descriptions) throws Exception {
    var raw =
        mapper
            .readTree(
                new ClassPathResource("kraken-score/l01.json")
                    .getContentAsString(StandardCharsets.UTF_8))
            .path("raw");
    for (var point : raw.path("ArkPassive").path("Points")) {
      var name = point.path("Name").asString();
      if (descriptions.containsKey(name))
        ((ObjectNode) point).put("Description", descriptions.get(name));
    }
    return raw;
  }

  private String search(JsonNode raw) throws Exception {
    when(auctions.cachedGems()).thenReturn(List.of());
    var profile =
        new CharacterProfile(
            DEALER,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1770.00"),
            new BigDecimal("4052.77"),
            null,
            null,
            null,
            null,
            stats(raw));
    var details = CharacterTestFixture.details(profile, ArmoryRawFixtures.loadout(raw), List.of());
    when(detailsPort.fetch(anyString()))
        .thenReturn(new FetchedCharacter(details, "{}", "[]", KrakenScoreInputMapper.from(raw)));
    // 원문을 바꿔 다시 찾을 때 지난 캐시를 쓰지 않게 지운다.
    정리();
    var response = get("/api/v1/characters/search?characterName=" + encode(DEALER));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return response.body();
  }

  /** 원문 프로필 특성. 공격력 툴팁의 기본 공격력은 합성(SpecUpKarmaCpIntegrationTest와 같음). */
  private static List<CharacterProfile.Stat> stats(JsonNode raw) {
    var stats = new java.util.ArrayList<CharacterProfile.Stat>();
    for (var stat : raw.path("ArmoryProfile").path("Stats")) {
      var tooltip = new java.util.ArrayList<String>();
      for (var line : stat.path("Tooltip")) tooltip.add(line.asString());
      if ("공격력".equals(stat.path("Type").asString()) && tooltip.isEmpty())
        tooltip.add("힘, 민첩, 지능과 무기 공격력을 기반으로 증가한 기본 공격력은 180000 입니다.");
      stats.add(
          new CharacterProfile.Stat(
              stat.path("Type").asString(), stat.path("Value").asString(), tooltip));
    }
    return stats;
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> simulate(String changes) throws Exception {
    var body = "{\"characterName\": \"" + DEALER + "\", \"changes\": " + changes + "}";
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:" + port + "/api/v1/characters/combat-power/simulate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
