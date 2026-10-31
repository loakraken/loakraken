package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CharacterSuggestIntegrationTest {
  private static final String SERVER = "자동완성서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM public_exclusion").update();
    for (var table :
        List.of("character_title_observation", "character_observation", "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name = :s)")
          .param("s", SERVER)
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var keys = redis.keys("character:v11:자동*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 이름_앞부분이_같은_캐릭터를_아이템_레벨_순으로_8개까지_주고_처리정지_목록은_뺀다() throws Exception {
    for (int i = 0; i < 10; i++) insert("자동완성" + i, 1700 + i);
    insert("자동_밑줄", 1600);
    insert("자동밑줄", 1600);
    insert("자동정지", 1800);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('자동정지', NULL, 'PRIVACY_STOP')")
        .update();

    var response = get("자동완성");
    assertThat(response.statusCode()).isEqualTo(200);
    // 제외 목록이 바뀌면 바로 반영되게 공유 캐시·브라우저가 다시 쓰지 않는다.
    assertThat(response.headers().firstValue("Cache-Control").orElse(""))
        .contains("no-cache")
        .contains("private")
        .doesNotContain("max-age=60")
        .doesNotContain("public");
    var data = mapper.readTree(response.body()).path("data");
    ApiContractFixture.write("character-suggest.json", response.body());
    assertThat(names(data))
        .containsExactly("자동완성9", "자동완성8", "자동완성7", "자동완성6", "자동완성5", "자동완성4", "자동완성3", "자동완성2");
    assertThat(data.at("/items/0/serverName").asText()).isEqualTo(SERVER);
    assertThat(data.at("/items/0/className").asText()).isEqualTo("바드");
    assertThat(data.at("/items/0/itemLevel").decimalValue()).isEqualByComparingTo("1709");
    assertThat(data.at("/items/0/characterImage").isNull()).isTrue();
    // 처리정지 목록은 맞아도 빠진다(아이템 레벨이 가장 높아도).
    assertThat(names(data(get("자동")))).doesNotContain("자동정지").hasSize(8);
    // LIKE 특수 문자는 글자 그대로 찾는다.
    assertThat(names(data(get("자동_")))).containsExactly("자동_밑줄");
    // 한글 1자는 찾고, 영문 1자·13자 이상은 빈 목록이다.
    assertThat(names(data(get("자")))).hasSize(8);
    assertThat(data(get("a")).path("items").size()).isZero();
    assertThat(data(get("자동완성자동완성자동완성자")).path("items").size()).isZero();
    assertThat(data(get("")).path("items").size()).isZero();
    verifyNoInteractions(detailsPort);
  }

  @Test
  void 검색하면_공식_프로필_초상을_저장해_자동완성에_준다() throws Exception {
    var image = "https://img.lostark.co.kr/armory/0/test.png";
    when(detailsPort.fetch(anyString())).thenReturn(character("자동초상", image));
    try (var client = HttpClient.newHttpClient()) {
      var search =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://localhost:"
                              + port
                              + "/api/v1/characters/search?characterName="
                              + URLEncoder.encode("자동초상", StandardCharsets.UTF_8)))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(search.statusCode()).isEqualTo(200);
    }
    JsonNode item = null;
    for (int i = 0; i < 100 && item == null; i++) {
      var items = data(get("자동초")).path("items");
      if (!items.isEmpty()) item = items.get(0);
      else Thread.sleep(50);
    }
    assertThat(item).isNotNull();
    assertThat(item.path("characterImage").asText()).isEqualTo(image);
  }

  private void insert(String name, int itemLevel) {
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level, last_synced_at)
        VALUES (:id, :n, :s, '바드', :l, now())
        """)
        .param("id", UUID.randomUUID())
        .param("n", name)
        .param("s", SERVER)
        .param("l", itemLevel)
        .update();
  }

  private FetchedCharacter character(String name, String image) {
    var profile =
        new CharacterProfile(
            name,
            SERVER,
            "바드",
            70,
            new BigDecimal("1700"),
            null,
            null,
            image,
            null,
            null,
            List.of());
    var loadout = new CharacterLoadout(List.of(), null, null, null, null, null);
    return new FetchedCharacter(CharacterTestFixture.details(profile, loadout, null), "{}", null);
  }

  private static List<String> names(JsonNode data) {
    var names = new ArrayList<String>();
    data.path("items").forEach(item -> names.add(item.path("characterName").asText()));
    return names;
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String q) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(
                  URI.create(
                      "http://localhost:"
                          + port
                          + "/api/v1/characters/suggest?q="
                          + URLEncoder.encode(q, StandardCharsets.UTF_8)))
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
