package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.common.lostark.UserKeyContext;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사용자 공식 API 키(BYOK, 기능 켬): web(여기서는 루프백)이 보낸 키는 캐릭터 검색의 공식 호출에만 쓰고, 받은 응답은 공용 캐시·없는 캐릭터 기억·DB에 남기지
 * 않으며, 키 값은 로그·응답 어디에도 나오지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "character.snapshots.enabled=true",
      "lostark.byok.enabled=true",
      "lostark.byok.trusted-peer=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1"
    })
class ByokIntegrationTest {
  private static final String NAME = "사용자키검색캐";
  private static final String SERVER = "사용자키서버";
  private static final String MARKER = "bWFya2Vy.dXNlcmtleQ.U0VDUkVUTUFSS0VSOTk";
  private static final String HEADER = "X-LK-User-Key";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final List<String> seenBearers = new ArrayList<>();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = redis.keys("character:v*" + NAME + "*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
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
    jdbc.sql("DELETE FROM character_view_daily WHERE character_name = :n")
        .param("n", NAME)
        .update();
    jdbc.sql("DELETE FROM character_popular_hourly WHERE server_name = :s")
        .param("s", SERVER)
        .update();
    seenBearers.clear();
  }

  @Test
  void 사용자_키로_받은_응답은_그_요청에만_쓰고_캐시와_DB에_남기지_않는다(CapturedOutput output) throws Exception {
    stubFetch();
    var response = search(MARKER);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains(NAME).doesNotContain(MARKER);
    assertThat(seenBearers).containsExactly(MARKER);
    assertThat(redis.keys("character:v*" + NAME + "*")).isEmpty();
    assertThat(rows()).isZero();
    assertThat(views()).as("조회 기록도 남기지 않는다").isZero();
    assertThat(output.getAll()).doesNotContain(MARKER).doesNotContain("U0VDUkVUTUFSS0VSOTk");
  }

  @Test
  void 키_없는_요청은_지금처럼_서버_키로_받고_캐시와_DB에_남긴다() throws Exception {
    stubFetch();
    var response = search(null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(seenBearers).containsExactly("(server)");
    assertThat(redis.keys("character:v*" + NAME + "*")).isNotEmpty();
    assertThat(rows()).isOne();
    assertThat(views()).isOne();
  }

  private long views() {
    return jdbc.sql("SELECT COUNT(*) FROM character_view_daily WHERE character_name = :n")
        .param("n", NAME)
        .query(Long.class)
        .single();
  }

  @Test
  void 사용자_키로_없는_캐릭터를_받아도_없는_캐릭터로_기억하지_않는다(CapturedOutput output) throws Exception {
    when(detailsPort.fetch(anyString()))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    var response = search(MARKER);
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(redis.keys("character:v*" + NAME + "*")).isEmpty();
    assertThat(output.getAll()).doesNotContain(MARKER);
  }

  @Test
  void 키_형식이_틀리면_400이고_공식_호출을_하지_않는다(CapturedOutput output) throws Exception {
    var bad = "SECRETMARKERnotajwt";
    var response = search(bad);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("USER_KEY_MALFORMED").doesNotContain(bad);
    verify(detailsPort, never()).fetch(anyString());
    assertThat(output.getAll()).doesNotContain(bad);
  }

  private void stubFetch() {
    when(detailsPort.fetch(NAME))
        .thenAnswer(
            invocation -> {
              var bearer = UserKeyContext.bearer();
              seenBearers.add(bearer == null ? "(server)" : bearer);
              return character();
            });
  }

  private long rows() {
    return jdbc.sql("SELECT COUNT(*) FROM characters WHERE server_name = :s")
        .param("s", SERVER)
        .query(Long.class)
        .single();
  }

  private HttpResponse<String> search(String key) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://127.0.0.1:"
                          + port
                          + "/api/v1/characters/search?characterName="
                          + URLEncoder.encode(NAME, StandardCharsets.UTF_8)))
              .GET();
      if (key != null) request.header(HEADER, key);
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private static FetchedCharacter character() {
    var profile =
        new CharacterProfile(
            NAME,
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
    return new FetchedCharacter(
        CharacterTestFixture.details(profile, loadout, List.of()), "{\"Tooltip\":\"원본\"}", "[]");
  }
}
