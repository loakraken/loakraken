package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.CharacterProfilePort;
import com.loakraken.character.domain.CharacterProfile;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterSearchIntegrationTest {
  private static final String NAME = "통합테스트캐릭터";
  private static final String KEY = "character:v1:" + NAME + ":profile";
  @LocalServerPort private int port;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JsonMapper mapper;
  @MockitoBean private CharacterProfilePort profilePort;

  @BeforeEach
  @AfterEach
  void 캐시_정리() {
    redis.delete(KEY);
  }

  @Test
  void 검색_결과를_Valkey에_저장하고_재조회에서_같은_수집시각을_반환한다() throws Exception {
    when(profilePort.fetch(NAME)).thenReturn(profile());
    var first = request("/api/v1/characters/search?characterName=" + encode(NAME));
    var second = request("/api/v1/characters/search?characterName=" + encode(NAME));
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.body()).isEqualTo(first.body());
    var result = mapper.readTree(first.body());
    assertThat(result.path("code").asText()).isEqualTo("CHARACTER_SEARCH_SUCCESS");
    assertThat(result.at("/data/characterName").asText()).isEqualTo(NAME);
    assertThat(result.at("/data/itemLevel").decimalValue()).isEqualByComparingTo("1700.5");
    assertThat(result.at("/data/fetchedAt").asText()).isNotBlank();
    assertThat(redis.getExpire(KEY)).isBetween(1L, 300L);
    verify(profilePort, times(1)).fetch(NAME);
  }

  @Test
  void 잘못된_캐릭터명은_외부_호출_전에_400을_반환한다() throws Exception {
    for (String query :
        List.of(
            "",
            "?characterName=",
            "?characterName=" + encode("../test"),
            "?characterName=" + "a".repeat(65))) {
      var response = request("/api/v1/characters/search" + query);
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .isEqualTo("INVALID_REQUEST");
    }
    verifyNoInteractions(profilePort);
  }

  @Test
  void 미존재_캐릭터는_404이고_정상_캐시에_저장하지_않는다() throws Exception {
    when(profilePort.fetch(anyString()))
        .thenThrow(new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    var response = request("/api/v1/characters/search?characterName=" + encode(NAME));
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(response.body()).path("code").asText())
        .isEqualTo("CHARACTER_NOT_FOUND");
    assertThat(redis.hasKey(KEY)).isFalse();
  }

  @Test
  void 손상된_캐시는_다시_조회한_정상_결과로_교체한다() throws Exception {
    redis.opsForValue().set(KEY, "invalid-json");
    when(profilePort.fetch(NAME)).thenReturn(profile());
    assertThat(request("/api/v1/characters/search?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(200);
    assertThat(mapper.readTree(redis.opsForValue().get(KEY)).path("profile").isObject()).isTrue();
    verify(profilePort).fetch(NAME);
  }

  @Test
  void Swagger에_검색_파라미터와_성공_오류_응답이_생성된다() throws Exception {
    var document = mapper.readTree(request("/v3/api-docs").body());
    var operation = document.at("/paths/~1api~1v1~1characters~1search/get");
    assertThat(operation.isMissingNode()).isFalse();
    assertThat(operation.path("parameters").toString()).contains("characterName");
    for (String status : List.of("200", "400", "404", "429", "502", "503", "504")) {
      assertThat(operation.path("responses").has(status)).isTrue();
    }
    assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
        .isNotBlank();
    assertThat(document.toString()).doesNotContain("apiKey", "Authorization");
  }

  private CharacterProfile profile() {
    return new CharacterProfile(
        NAME,
        "루페온",
        "바드",
        70,
        new BigDecimal("1700.5"),
        new BigDecimal("12345"),
        300,
        null,
        null,
        null,
        List.of());
  }

  private String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private HttpResponse<String> request(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
