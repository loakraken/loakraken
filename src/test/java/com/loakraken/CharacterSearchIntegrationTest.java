package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.character.application.CharacterDetailsPort;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.application.FetchedCharacter;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterLoadout;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterSearchIntegrationTest {
  private static final String NAME = "통합테스트캐릭터";
  private static final String KEY = "character:v2:" + NAME + ":summary";
  @LocalServerPort private int port;
  @Autowired private StringRedisTemplate redis;
  @Autowired private JsonMapper mapper;
  @Autowired private JdbcClient jdbc;
  @MockitoBean private CharacterDetailsPort detailsPort;

  @BeforeEach
  @AfterEach
  void 캐시_정리() {
    redis.delete(KEY);
    jdbc.sql(
            "DELETE FROM character_snapshot WHERE character_id IN (SELECT character_id FROM characters WHERE character_name = :name)")
        .param("name", NAME)
        .update();
    jdbc.sql("DELETE FROM characters WHERE character_name = :name").param("name", NAME).update();
  }

  @Test
  void 검색_결과를_Valkey에_저장하고_재조회에서_같은_수집시각을_반환한다() throws Exception {
    when(detailsPort.fetch(NAME))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return profile();
            });
    var first = request("/api/v1/characters/search?characterName=" + encode(NAME));
    var second = request("/api/v1/characters/search?characterName=" + encode(NAME));
    assertThat(first.statusCode()).isEqualTo(200);
    ApiContractFixture.write("character-search.json", first.body());
    assertThat(second.body()).isEqualTo(first.body());
    var result = mapper.readTree(first.body());
    assertThat(result.path("code").asText()).isEqualTo("CHARACTER_SEARCH_SUCCESS");
    assertThat(result.at("/data/characterName").asText()).isEqualTo(NAME);
    assertThat(result.at("/data/itemLevel").decimalValue()).isEqualByComparingTo("1700.5");
    assertThat(result.at("/data/fetchedAt").asText()).isNotBlank();
    assertThat(result.at("/data/loadout/equipment/0/name").asText()).isEqualTo("테스트 무기");
    assertThat(result.at("/data/loadout/gems").isNull()).isTrue();
    assertThat(result.at("/data/siblings").isArray()).isTrue();
    assertThat(result.at("/data/analysis/equipmentCount").intValue()).isEqualTo(1);
    assertThat(result.at("/data/analysis/gems").isNull()).isTrue();
    assertThat(redis.getExpire(KEY)).isBetween(1L, 300L);
    verify(detailsPort, times(1)).fetch(NAME);
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM character_snapshot s JOIN characters c USING (character_id) WHERE c.character_name = :name")
                .param("name", NAME)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(first.body()).doesNotContain("armoryJson", "siblingsJson", "원본툴팁");
    assertThat(redis.opsForValue().get(KEY)).doesNotContain("armoryJson", "siblingsJson", "원본툴팁");
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
      ApiContractFixture.write("invalid-request.json", response.body());
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .isEqualTo("INVALID_REQUEST");
    }
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 미존재_캐릭터는_404이고_정상_캐시에_저장하지_않는다() throws Exception {
    when(detailsPort.fetch(anyString()))
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
    when(detailsPort.fetch(NAME)).thenReturn(profile());
    assertThat(request("/api/v1/characters/search?characterName=" + encode(NAME)).statusCode())
        .isEqualTo(200);
    assertThat(mapper.readTree(redis.opsForValue().get(KEY)).path("details").isObject()).isTrue();
    verify(detailsPort).fetch(NAME);
  }

  @Test
  void 지원하지_않는_응답_형식은_406_공통_오류다() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/characters/search?characterName="
                              + encode(NAME)))
                  .header("Accept", "application/xml")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(406);
      ApiContractFixture.write("not-acceptable.json", response.body());
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .isEqualTo("NOT_ACCEPTABLE");
    }
    verifyNoInteractions(detailsPort);
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
    assertThat(document.at("/components/schemas/CharacterResponse/properties").has("loadout"))
        .isTrue();
    assertThat(document.at("/components/schemas/CharacterResponse/properties").has("siblings"))
        .isTrue();
    assertThat(document.at("/components/schemas/CharacterResponse/properties").has("analysis"))
        .isTrue();
    assertThat(document.at("/components/schemas/CharacterAnalysisResponse/properties").has("gems"))
        .isTrue();
    assertThat(
            document.at("/components/schemas/CharacterLoadoutResponse/properties").has("arkGrid"))
        .isTrue();
  }

  private FetchedCharacter profile() {
    var profile =
        new CharacterProfile(
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
    var loadout =
        new CharacterLoadout(
            List.of(new CharacterLoadout.Equipment("무기", "테스트 무기", "icon", "고대")),
            null,
            null,
            null,
            null,
            null);
    return new FetchedCharacter(
        new CharacterDetails(profile, loadout, List.of()), "{\"Tooltip\":\"원본툴팁\"}", "[]");
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
