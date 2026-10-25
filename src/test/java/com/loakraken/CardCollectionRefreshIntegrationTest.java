package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.CharacterSibling;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardCollectionRefreshIntegrationTest {
  private static final String SERVER = "채우기테스트서버";
  private static final String MAIN = "채우기본캐";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();
  private final List<String> names = new ArrayList<>();

  @BeforeEach
  void 원정대() {
    정리();
    names.add(MAIN);
    for (int i = 1; i <= 13; i++) names.add("채우기부캐" + i);
  }

  @AfterEach
  void 정리() {
    var keys = new ArrayList<String>();
    for (var pattern : List.of("cards:v1:refresh:*", "character:v1:*채우기*", "character:v11:*채우기*")) {
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
  }

  @Test
  void 최근_기록이_없는_캐릭터를_레벨순으로_12명까지_채우고_10분_쿨다운을_건다() throws Exception {
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < names.size(); i++)
      roster.add(new CharacterSibling(names.get(i), SERVER, "바드", 70, new BigDecimal(1700 - i)));
    roster.add(new CharacterSibling("다른서버캐", "다른서버", "바드", 70, new BigDecimal("1800")));
    when(detailsPort.fetchSiblings(MAIN)).thenReturn(roster);
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
    when(detailsPort.fetchArmory("채우기부캐5"))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    // 본캐는 오늘 이미 관측해서 건너뛴다.
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
          combat_power, last_synced_at, profile_refreshed_at)
        VALUES (:id, :n, :s, '바드', 1700, 3000, :at, :at)
        """)
        .param("id", UUID.randomUUID())
        .param("n", MAIN)
        .param("s", SERVER)
        .param("at", OffsetDateTime.now())
        .update();

    var response = post("/api/v1/characters/card-collection/refresh?characterName=" + encode(MAIN));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("rosterCharacters").asInt()).isEqualTo(14);
    assertThat(data.path("skipped").asInt()).isEqualTo(1);
    assertThat(data.path("refreshed").asInt()).isEqualTo(11);
    assertThat(data.path("failed").asInt()).isEqualTo(1);
    // 대상 13명 중 상한 12명을 시도해 1명이 남는다(가장 낮은 레벨).
    assertThat(data.path("remaining").asInt()).isEqualTo(1);
    assertThat(data.path("nextAvailableAt").asText()).isNotBlank();
    verify(detailsPort, never()).fetchArmory("채우기부캐13");
    verify(detailsPort, never()).fetchArmory(MAIN);
    verify(detailsPort, never()).fetchArmory("다른서버캐");
    ApiContractFixture.write("card-collection-refresh.json", response.body());

    var again = post("/api/v1/characters/card-collection/refresh?characterName=" + encode(MAIN));
    assertThat(again.statusCode()).isEqualTo(429);
    assertThat(mapper.readTree(again.body()).path("code").asText())
        .isEqualTo("CARD_COLLECTION_REFRESH_COOLDOWN");
    assertThat(Long.parseLong(again.headers().firstValue("Retry-After").orElseThrow()))
        .isBetween(1L, 600L);
  }

  @Test
  void 제외_목록에_든_원정대_동료는_채우지도_세지도_않는다() throws Exception {
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < 3; i++)
      roster.add(new CharacterSibling(names.get(i), SERVER, "바드", 70, new BigDecimal(1700 - i)));
    when(detailsPort.fetchSiblings(MAIN)).thenReturn(roster);
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('채우기부캐1', :s, 'PRIVACY_STOP')")
        .param("s", SERVER)
        .update();
    try {
      var response =
          post("/api/v1/characters/card-collection/refresh?characterName=" + encode(MAIN));
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var data = mapper.readTree(response.body()).path("data");
      assertThat(data.path("rosterCharacters").asInt()).isEqualTo(2);
      assertThat(data.path("refreshed").asInt()).isEqualTo(2);
      verify(detailsPort, never()).fetchArmory("채우기부캐1");
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE character_name = '채우기부캐1'").update();
    }
  }

  @Test
  void 공식_호출_한도를_만나면_멈추고_남은_수로_돌려준다() throws Exception {
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < 4; i++)
      roster.add(new CharacterSibling(names.get(i), SERVER, "바드", 70, new BigDecimal(1700 - i)));
    when(detailsPort.fetchSiblings(MAIN)).thenReturn(roster);
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
    when(detailsPort.fetchArmory("채우기부캐1"))
        .thenThrow(new CharacterException(CharacterErrorCode.LOST_ARK_RATE_LIMITED));

    var data =
        mapper
            .readTree(
                post("/api/v1/characters/card-collection/refresh?characterName=" + encode(MAIN))
                    .body())
            .path("data");
    assertThat(data.path("refreshed").asInt()).isEqualTo(1);
    assertThat(data.path("failed").asInt()).isEqualTo(1);
    assertThat(data.path("remaining").asInt()).isEqualTo(2);
  }

  @Test
  void 첫_호출부터_한도에_막혀_한_명도_못_채우면_쿨다운을_풀어_바로_다시_할_수_있다() throws Exception {
    var roster = new ArrayList<CharacterSibling>();
    for (int i = 0; i < 3; i++)
      roster.add(new CharacterSibling(names.get(i), SERVER, "바드", 70, new BigDecimal(1700 - i)));
    when(detailsPort.fetchSiblings(MAIN)).thenReturn(roster);
    // 클라이언트 한도(#151)도 공유 한도처럼 중단 사유다.
    when(detailsPort.fetchArmory(anyString()))
        .thenThrow(
            new com.loakraken.common.api.BusinessException(
                com.loakraken.common.lostark.LostArkErrorCode.CLIENT_RATE_LIMITED));

    var first = post("/api/v1/characters/card-collection/refresh?characterName=" + encode(MAIN));
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    var data = mapper.readTree(first.body()).path("data");
    assertThat(data.path("refreshed").asInt()).isZero();
    assertThat(data.path("failed").asInt()).isEqualTo(1);
    assertThat(data.path("remaining").asInt()).isEqualTo(2);
    // 쿨다운이 풀려 곧바로 다시 시도할 수 있다(429 아님).
    var again = post("/api/v1/characters/card-collection/refresh?characterName=" + encode(MAIN));
    assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
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
            List.of(),
            null,
            null,
            new CharacterLoadout.Cards(
                List.of(new CharacterLoadout.Card(0, "샨디", "icon", 5, 5, "전설")), List.of()),
            null,
            null);
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }

  private HttpResponse<String> post(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
