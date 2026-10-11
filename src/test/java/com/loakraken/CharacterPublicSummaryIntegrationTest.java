package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/** 캐릭터 공개 요약(#145). 공식 API는 한 번도 부르지 않는다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterPublicSummaryIntegrationTest {
  private static final String SERVER = "공개요약서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '공개요약%'").update();
    var keys = redis.keys("character:v*공개요약*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 캐시도_저장된_관측도_없으면_404이고_공식_호출이_없다() throws Exception {
    var response = get("공개요약없음");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED");
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 저장된_마지막_관측과_장비_요약의_레벨_각성을_준다() throws Exception {
    var id = character("공개요약저장", OffsetDateTime.parse("2026-10-01T03:00:00Z"));
    jdbc.sql(
            """
        INSERT INTO character_equipment_summary (character_id, observed_at, last_confirmed_at, schema_version,
          combat_power, item_level, character_level, class_name, ark_passive_title, features, features_hash)
        VALUES (:id, :at, :at, 'equipment-summary-v1', 4000, 1770, 70, '블레이드', '버스트', '{}', :hash)
        """)
        .param("id", id)
        .param("at", OffsetDateTime.parse("2026-10-01T03:00:00Z"))
        .param("hash", "0".repeat(64))
        .update();
    var data = mapper.readTree(get("공개요약저장").body()).path("data");
    assertThat(data.path("dataSource").asText()).isEqualTo("STORED_OBSERVATION");
    assertThat(data.path("serverName").asText()).isEqualTo(SERVER);
    assertThat(data.path("characterLevel").asInt()).isEqualTo(70);
    assertThat(data.path("arkPassiveTitle").asText()).isEqualTo("버스트");
    assertThat(data.path("itemLevel").decimalValue()).isEqualByComparingTo("1770");
    assertThat(data.path("observedAt").asText()).isEqualTo("2026-10-01T03:00:00Z");
    assertThat(data.path("indexable").asBoolean()).isTrue();
    verifyNoInteractions(detailsPort);
    ApiContractFixture.write("character-public-summary.json", get("공개요약저장").body());
  }

  @Test
  void 최근_검색_캐시가_있으면_LIVE_CACHE를_쓴다() throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> fetched(invocation.getArgument(0)));
    var search =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://localhost:"
                                + port
                                + "/api/v1/characters/search?characterName="
                                + encode("공개요약캐시")))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(search.statusCode()).isEqualTo(200);
    var data = mapper.readTree(get("공개요약캐시").body()).path("data");
    assertThat(data.path("dataSource").asText()).isEqualTo("LIVE_CACHE");
    assertThat(data.path("combatPower").decimalValue()).isEqualByComparingTo("3000");
    verify(detailsPort, times(1)).fetch(anyString());
  }

  @Test
  void 전투력_관측이_없으면_값은_주되_indexable_false다() throws Exception {
    var id = character("공개요약원정대만", OffsetDateTime.now(ZoneOffset.UTC));
    jdbc.sql("UPDATE characters SET combat_power = NULL WHERE character_id = :id")
        .param("id", id)
        .update();
    var data = mapper.readTree(get("공개요약원정대만").body()).path("data");
    assertThat(data.path("indexable").asBoolean()).isFalse();
    assertThat(data.path("serverName").asText()).isEqualTo(SERVER);
    assertThat(data.path("combatPower").isNull()).isTrue();
  }

  @Test
  void 처리정지_목록_캐릭터는_저장된_관측을_쓰지_않고_기록_없는_캐릭터와_같은_404다() throws Exception {
    // #363: 운영자가 기록을 지우기 전 남은 관측이 있어도 쓰지 않는다. 예전 숨김 응답(indexable=false, null 값) 없음.
    character("공개요약제외", OffsetDateTime.now(ZoneOffset.UTC));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('공개요약제외', NULL, 'USER_REQUEST')")
        .update();
    var response = get("공개요약제외");
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(response.body()).contains("CHARACTER_NOT_CACHED").doesNotContain("indexable");
  }

  @Test
  void 기록이_없는_처리정지_목록_이름도_일반_이름과_같은_404다() throws Exception {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('공개요약제외기록없음', '다른서버', 'USER_REQUEST')")
        .update();
    var stopped = get("공개요약제외기록없음");
    var unknown = get("공개요약없는이름");
    assertThat(stopped.statusCode()).isEqualTo(404).isEqualTo(unknown.statusCode());
    assertThat(mapper.readTree(stopped.body()).path("code").asText())
        .isEqualTo(mapper.readTree(unknown.body()).path("code").asText());
    verifyNoInteractions(detailsPort);
  }

  private UUID character(String name, OffsetDateTime at) {
    var id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
                                combat_power, last_synced_at)
        VALUES (:id, :name, :server, '블레이드', 1770, 4000, :at)
        """)
        .param("id", id)
        .param("name", name)
        .param("server", SERVER)
        .param("at", at)
        .update();
    return id;
  }

  private FetchedCharacter fetched(String name) {
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
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null, List.of());
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
  }

  private HttpResponse<String> get(String name) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/characters/public-summary?characterName="
                            + encode(name)))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
