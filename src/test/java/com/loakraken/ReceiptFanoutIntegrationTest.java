package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 공식 호출 줄이기(기본 설정): 한 번 열 때 새로 받는 전투정보는 2명까지, 받은 입력은 캐릭터 캐시가 지나도 영수증 입력 보관에서 다시 쓴다. 찾지 못한 캐릭터는
 * UNAVAILABLE로 대기 수에서 빠지고 다시 부르지 않는다. 처리정지 목록 캐릭터의 보관 입력은 지운다. 공식 API·시세는 모두 대역이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "receipt.enabled=true")
class ReceiptFanoutIntegrationTest {
  private static final String SERVER = "영수증호출서버";
  private static final String MAIN = "영수증호출본캐";
  private static final String GONE = "영수증호출없음";
  private static final Instant OBSERVED = Instant.parse("2026-10-09T08:00:00Z");
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  @MockitoBean MaterialUnitPriceService materials;
  @MockitoBean MarketAuctionService auctions;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  void 준비() {
    정리();
    // 아이템 레벨 순: 본캐, 부캐1, 없음, 부캐3, 부캐4.
    var names = List.of(MAIN, "영수증호출부캐1", GONE, "영수증호출부캐3", "영수증호출부캐4");
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < names.size(); i++)
      roster.add(new CharacterSibling(names.get(i), SERVER, "바드", 70, new BigDecimal(1750 - i)));
    when(detailsPort.fetchSiblings(MAIN)).thenReturn(roster);
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
    when(detailsPort.fetchArmory(GONE))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
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
    for (var pattern : List.of("character:v*영수증호출*", "receipt:v1:input:*영수증호출*")) {
      var keys = redis.keys(pattern);
      if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
  }

  @Test
  void 한_번에_2명까지만_새로_받고_찾지_못한_캐릭터는_대기에서_빼고_다시_부르지_않는다() throws Exception {
    var first = receipt();
    assertThat(first.at("/coverage/ready").asInt()).isEqualTo(2);
    assertThat(first.at("/coverage/pending").asInt()).isEqualTo(3);
    assertThat(first.at("/coverage/unavailable").asInt()).isZero();
    assertThat(first.at("/coverage/nextAvailableAt").asText()).isNotBlank();
    assertThat(first.at("/equipment/characters/0/observedAt").asText()).isNotBlank();
    assertThat(first.at("/equipment/characters/2/observedAt").isNull()).isTrue();
    verify(detailsPort, times(2)).fetchArmory(anyString());

    // 두 번째: 없음(찾지 못함)과 부캐3. 없음은 대기에서 빠진다.
    var second = receipt();
    assertThat(second.at("/coverage/ready").asInt()).isEqualTo(3);
    assertThat(second.at("/coverage/pending").asInt()).isEqualTo(1);
    assertThat(second.at("/coverage/unavailable").asInt()).isEqualTo(1);
    assertThat(second.at("/equipment/characters/2/status").asText()).isEqualTo("UNAVAILABLE");
    assertThat(second.at("/equipment/characters/2/gold").isNull()).isTrue();
    verify(detailsPort, times(4)).fetchArmory(anyString());

    // 세 번째: 없음은 다시 부르지 않고 남은 부캐4만 받는다. 대기가 없으니 이어 받을 시각도 없다.
    var third = receipt();
    assertThat(third.at("/coverage/ready").asInt()).isEqualTo(4);
    assertThat(third.at("/coverage/pending").asInt()).isZero();
    assertThat(third.at("/coverage/unavailable").asInt()).isEqualTo(1);
    assertThat(third.at("/coverage/nextAvailableAt").isNull()).isTrue();
    // 빠진 캐릭터가 있어 합계는 일부만 센 값이다.
    assertThat(third.at("/total/status").asText()).isEqualTo("PARTIAL");
    verify(detailsPort, times(1)).fetchArmory(GONE);
    verify(detailsPort, times(5)).fetchArmory(anyString());
  }

  @Test
  void 캐릭터_캐시가_지나도_영수증_입력_보관으로_다시_부르지_않는다() throws Exception {
    doAnswer(invocation -> character(invocation.getArgument(0)))
        .when(detailsPort)
        .fetchArmory(GONE);
    receipt();
    receipt();
    var full = receipt();
    assertThat(full.at("/coverage/ready").asInt()).isEqualTo(5);
    var observedAt = full.at("/equipment/characters/0/observedAt").asText();
    verify(detailsPort, times(5)).fetchArmory(anyString());

    // 캐릭터 캐시(5분)가 지난 것처럼 지운다. 영수증 입력 보관에서 다시 쓰니 공식 호출이 없다.
    var keys = redis.keys("character:v*영수증호출*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    var again = receipt();
    assertThat(again.at("/coverage/ready").asInt()).isEqualTo(5);
    assertThat(again.at("/coverage/pending").asInt()).isZero();
    assertThat(again.at("/equipment/characters/0/observedAt").asText()).isEqualTo(observedAt);
    verify(detailsPort, times(5)).fetchArmory(anyString());
  }

  @Test
  void 처리정지_목록에_든_캐릭터의_보관_입력은_지우고_영수증에서_뺀다() throws Exception {
    receipt();
    assertThat(redis.hasKey("receipt:v1:input:영수증호출부캐1")).isTrue();
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('영수증호출부캐1', :s, 'REQUEST')")
        .param("s", SERVER)
        .update();
    try {
      var after = receipt();
      assertThat(redis.hasKey("receipt:v1:input:영수증호출부캐1")).isFalse();
      assertThat(after.at("/coverage/rosterCharacters").asInt()).isEqualTo(4);
      assertThat(after.at("/equipment/characters").toString()).doesNotContain("영수증호출부캐1\"");
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE server_name = :s").param("s", SERVER).update();
    }
  }

  private JsonNode receipt() throws Exception {
    var response = get("/api/v1/characters/receipt?characterName=" + encode(MAIN));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
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
