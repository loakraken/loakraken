package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterHistoryPort;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 휘장 장착 분포: 프로필 관측으로 휘장을 기록하고(정규화한 공식 주소만), 캐릭터 단위로 센다. 공식 호출은 없다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.titles.enabled=true")
class EmblemEquipIntegrationTest {
  private static final String SERVER = "휘장서버";
  private static final String CDN = "https://cdn-lostark.game.onstove.com/efui_iconatlas/";
  private static final String A = CDN + "playerinfodecobrooch/playerinfodecobrooch_97.png";
  private static final String B = CDN + "playerinfodecobrooch/playerinfodecobrooch_98.png";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CharacterHistoryPort history;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '휘장%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  private void record(String name, CharacterProfile.Decorations decorations) {
    var inner =
        decorations == null
            ? null
            : new CharacterProfile.Inner(List.of(), decorations, null, null, null, null);
    history.record(
        new CharacterProfile(
            name,
            SERVER,
            "블레이드",
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            null,
            null,
            null,
            null,
            List.of(),
            null,
            inner),
        Instant.now());
  }

  @Test
  void 휘장마다_장착한_캐릭터_수와_비율을_주고_모르는_캐릭터는_세지_않는다() throws Exception {
    // 같은 그림의 쿼리 붙은 주소는 같은 휘장, 공식 서버가 아닌 주소는 버린다.
    record("휘장가", new CharacterProfile.Decorations(null, List.of(A + "?v=2", B)));
    record("휘장나", new CharacterProfile.Decorations(null, List.of(A)));
    record("휘장다", new CharacterProfile.Decorations(null, List.of("https://example.com/x.png")));
    record("휘장없음", new CharacterProfile.Decorations(null, List.of()));
    // 장식 구역을 모르는 관측은 모집단에 넣지 않는다.
    record("휘장모름", null);
    // 처리정지 목록은 뺀다.
    record("휘장제외", new CharacterProfile.Decorations(null, List.of(B)));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('휘장제외', NULL, 'USER_REQUEST')")
        .update();

    var data = data(get("/api/v1/emblems/equipped?server=" + encode(SERVER)));
    assertThat(data.at("/meta/population").asLong()).isEqualTo(4);
    assertThat(data.path("withoutEmblem").asLong()).isEqualTo(2);
    assertThat(data.at("/emblems/0/iconUrl").asText()).isEqualTo(A);
    assertThat(data.at("/emblems/0/characters").asLong()).isEqualTo(2);
    assertThat(data.at("/emblems/0/percent").decimalValue()).isEqualByComparingTo("50.00");
    assertThat(data.at("/emblems/1/iconUrl").asText()).isEqualTo(B);
    assertThat(data.at("/emblems/1/characters").asLong()).isEqualTo(1);
    assertThat(data.path("emblems").size()).isEqualTo(2);

    // 다음 관측이 휘장을 바꾸면 그 집합으로 바뀐다.
    redis.delete(redis.keys("stats:v1:*"));
    record("휘장나", new CharacterProfile.Decorations(null, List.of(B)));
    var again = data(get("/api/v1/emblems/equipped?server=" + encode(SERVER)));
    assertThat(again.at("/emblems/0/iconUrl").asText()).isEqualTo(B);
    assertThat(again.at("/emblems/0/characters").asLong()).isEqualTo(2);
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String pathAndQuery) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
