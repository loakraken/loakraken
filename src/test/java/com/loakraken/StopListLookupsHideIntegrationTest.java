package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
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

/**
 * 이름 조회 처리 설정 HIDE(character.stop-list.lookups=HIDE): 제외 목록 캐릭터는 이름으로 바로 찾아도 없는 캐릭터와 같고, 원정대 목록에서도
 * 빠진다. 기본 ALLOW(지금 처리방침)는 다른 통합 테스트(제외 목록 경로)가 지금 응답 그대로임을 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "character.stop-list.lookups=HIDE")
class StopListLookupsHideIntegrationTest {
  private static final String SERVER = "숨김설정서버";
  private static final String NORMAL = "숨김설정일반";
  private static final String STOPPED = "숨김설정대상";
  private static final String UNKNOWN = "숨김설정없음";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = new ArrayList<String>();
    for (var pattern : List.of("character:v1:숨김설정*", "character:v11:숨김설정*")) {
      var found = redis.keys(pattern);
      if (found != null) keys.addAll(found);
    }
    if (!keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '숨김설정%'").update();
  }

  @Test
  void 제외_목록_캐릭터는_이름으로_찾아도_없는_캐릭터와_같고_원정대_목록에서_빠진다() throws Exception {
    var roster =
        List.of(
            new CharacterSibling(NORMAL, SERVER, "바드", 70, new BigDecimal("1710")),
            new CharacterSibling(STOPPED, SERVER, "바드", 70, new BigDecimal("1700")));
    when(detailsPort.fetchSiblings(anyString())).thenReturn(roster);
    when(detailsPort.fetchArmory(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
    when(detailsPort.fetch(anyString()))
        .thenAnswer(invocation -> character(invocation.getArgument(0)));
    when(detailsPort.fetchArmory(UNKNOWN))
        .thenThrow(
            new com.loakraken.character.application.CharacterException(
                com.loakraken.character.application.CharacterErrorCode.CHARACTER_NOT_FOUND));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
        .param("n", STOPPED)
        .param("s", SERVER)
        .update();

    // 이름으로 찾기: 없는 캐릭터와 같은 상태·코드.
    var stopped =
        get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(STOPPED));
    var unknown =
        get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(UNKNOWN));
    assertThat(stopped.statusCode()).isEqualTo(unknown.statusCode()).isEqualTo(404);
    assertThat(code(stopped)).isEqualTo(code(unknown));
    assertThat(stopped.body()).doesNotContain(STOPPED);

    // 일반 캐릭터는 그대로, 원정대 목록에서는 제외 캐릭터가 빠진다.
    var normal =
        get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(NORMAL));
    assertThat(normal.statusCode()).isEqualTo(200);
    var siblings = get("/api/v1/characters/siblings?characterName=" + encode(NORMAL));
    assertThat(siblings.statusCode()).as(siblings.body()).isEqualTo(200);
    assertThat(siblings.body()).contains(NORMAL).doesNotContain(STOPPED);
    assertThat(get("/api/v1/characters/siblings?characterName=" + encode(STOPPED)).statusCode())
        .isEqualTo(404);

    // 공개 요약: 실시간 캐시에 있어도 기록 없는 이름과 같은 응답.
    var summaryStopped = get("/api/v1/characters/public-summary?characterName=" + encode(STOPPED));
    var summaryUnknown = get("/api/v1/characters/public-summary?characterName=" + encode(UNKNOWN));
    assertThat(summaryStopped.statusCode()).isEqualTo(summaryUnknown.statusCode());
    assertThat(code(summaryStopped)).isEqualTo(code(summaryUnknown));
  }

  private String code(HttpResponse<String> response) throws Exception {
    return mapper.readTree(response.body()).path("code").asText();
  }

  private static FetchedCharacter character(String name) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "바드",
            70,
            new BigDecimal("1700"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null);
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{}", "[]");
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
