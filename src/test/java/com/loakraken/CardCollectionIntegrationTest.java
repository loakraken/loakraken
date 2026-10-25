package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardCollectionIntegrationTest {
  private static final String SERVER = "카드테스트서버";
  private static final String OTHER_SERVER = "카드다른서버";
  private static final String DEALER = "카드딜러";
  private static final String SUPPORT = "카드서폿";
  private static final String ALT = "카드부캐";
  private static final String EMPTY = "카드신규";
  private static final String TEACHER = "카드학습캐";
  private static final List<String> NAMES = List.of(DEALER, SUPPORT, ALT, EMPTY, TEACHER);
  private static final List<String> LIGHT = List.of("샨디", "아제나&이난나", "카단", "바훈투르", "실리안", "웨이");
  private static final List<String> LEGION = List.of("발탄", "비아키스", "쿠크세이튼", "아브렐슈드", "일리아칸", "카멘");

  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @MockitoBean CharacterDetailsPort detailsPort;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    for (var name : NAMES) {
      for (var key : List.of("summary", "armory"))
        redis.delete("character:v11:" + name + ":" + key);
      redis.delete("character:v1:" + name + ":siblings");
      jdbc.sql("DELETE FROM character_view_daily WHERE character_name = :name")
          .param("name", name)
          .update();
    }
    for (var table :
        List.of(
            "character_card",
            "character_title_observation",
            "character_observation",
            "character_snapshot"))
      jdbc.sql(
              "DELETE FROM "
                  + table
                  + " WHERE character_id IN (SELECT character_id FROM characters WHERE server_name IN (:servers))")
          .param("servers", List.of(SERVER, OTHER_SERVER))
          .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:servers)")
        .param("servers", List.of(SERVER, OTHER_SERVER))
        .update();
    jdbc.sql("DELETE FROM card_set_member WHERE set_name IN ('세상을 구하는 빛', '카제로스의 군단장')").update();
    jdbc.sql("DELETE FROM card_set_effect WHERE set_name IN ('세상을 구하는 빛', '카제로스의 군단장')").update();
  }

  @Test
  void 원정대_캐릭터를_검색할_때마다_카드를_기록하고_원정대_단위로_합친다() throws Exception {
    var roster =
        List.of(
            sibling(DEALER, SERVER, "블레이드"),
            sibling(SUPPORT, SERVER, "바드"),
            sibling(ALT, SERVER, "소서리스"),
            sibling(EMPTY, SERVER, "워로드"),
            sibling(TEACHER, OTHER_SERVER, "버서커"));
    for (var name : NAMES) when(detailsPort.fetchSiblings(name)).thenReturn(roster);

    // 다른 서버 캐릭터가 군단장 6장을 장착해 세트 구성원을 알려 준다. 원정대 기록에는 들어가지 않는다.
    when(detailsPort.fetchArmory(TEACHER))
        .thenReturn(
            character(
                TEACHER,
                OTHER_SERVER,
                "버서커",
                cards(LEGION, new int[] {5, 5, 5, 5, 5, 5}),
                legionSet()));
    // 본캐는 세구빛 6장 30각성.
    when(detailsPort.fetchArmory(DEALER))
        .thenReturn(
            character(
                DEALER, SERVER, "블레이드", cards(LIGHT, new int[] {5, 5, 5, 5, 5, 5}), lightSet()));
    // 부캐와 서포터는 군단장 카드를 나눠 장착했다(세트 효과 없음). 합치면 6장 28각성이다.
    when(detailsPort.fetchArmory(ALT))
        .thenReturn(
            character(
                ALT, SERVER, "소서리스", cards(LEGION.subList(0, 3), new int[] {4, 5, 5}), List.of()));
    when(detailsPort.fetchArmory(SUPPORT))
        .thenReturn(
            character(
                SUPPORT,
                SERVER,
                "바드",
                cards(LEGION.subList(3, 6), new int[] {4, 5, 5}),
                List.of()));

    for (var name : List.of(TEACHER, DEALER, ALT, SUPPORT))
      assertThat(
              get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(name))
                  .statusCode())
          .as(name)
          .isEqualTo(200);
    // 카드 기록은 비동기로 쓴다.
    awaitCards(TEACHER, 6);
    awaitCards(SUPPORT, 3);
    jdbc.sql(
            "UPDATE characters SET position = 'DEALER' WHERE server_name = :s AND character_name = :n")
        .param("s", SERVER)
        .param("n", DEALER)
        .update();
    jdbc.sql(
            "UPDATE characters SET position = 'SUPPORT' WHERE server_name = :s AND character_name = :n")
        .param("s", SERVER)
        .param("n", SUPPORT)
        .update();

    var dealer = get("/api/v1/characters/card-collection?characterName=" + encode(DEALER));
    assertThat(dealer.statusCode()).isEqualTo(200);
    var json = mapper.readTree(dealer.body());
    assertThat(json.path("code").asText()).isEqualTo("CHARACTER_CARD_COLLECTION_SUCCESS");
    var data = json.path("data");
    assertThat(data.path("serverName").asText()).isEqualTo(SERVER);
    assertThat(data.path("position").asText()).isEqualTo("DEALER");
    assertThat(data.path("rosterCharacters").asInt()).isEqualTo(4);
    assertThat(data.path("observedCharacters").asInt()).isEqualTo(3);
    assertThat(data.path("ruleVersion").asText()).isEqualTo("card-record-v1");
    var light = data.at("/elementRecords/dealer/0");
    assertThat(light.path("element").asText()).isEqualTo("LIGHT");
    assertThat(light.at("/record/cardCount").asInt()).isEqualTo(6);
    assertThat(light.at("/record/awakening").asInt()).isEqualTo(30);
    assertThat(light.at("/record/activeAwakening").asInt()).isEqualTo(30);
    assertThat(light.at("/record/recordedBy").asText()).isEqualTo(DEALER);
    var dark = data.at("/elementRecords/dealer/1");
    assertThat(dark.path("element").asText()).isEqualTo("DARK");
    assertThat(dark.at("/record/awakening").asInt()).isEqualTo(28);
    assertThat(dark.at("/record/activeAwakening").asInt()).isEqualTo(24);
    assertThat(data.at("/elementRecords/dealer/2/record").isNull()).isTrue();
    assertThat(data.path("cards").size()).isEqualTo(12);
    assertThat(data.path("sets").size()).isEqualTo(2);
    assertThat(data.at("/sets/0/setName").asText()).isEqualTo("세상을 구하는 빛");
    assertThat(data.at("/sets/0/effects/0/active").asBoolean()).isTrue();
    ApiContractFixture.write("card-collection-dealer.json", dealer.body());

    var support = get("/api/v1/characters/card-collection?characterName=" + encode(SUPPORT));
    assertThat(mapper.readTree(support.body()).at("/data/position").asText()).isEqualTo("SUPPORT");
    ApiContractFixture.write("card-collection-supporter.json", support.body());

    // 같은 카드의 각성이 낮게 다시 관측돼도 최고 기록을 유지한다.
    redis.delete("character:v11:" + ALT + ":armory");
    when(detailsPort.fetchArmory(ALT))
        .thenReturn(character(ALT, SERVER, "소서리스", cards(List.of("발탄"), new int[] {1}), List.of()));
    get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(ALT));
    // 작업자는 하나라 뒤이은 기록이 반영되면 앞선 부캐 기록도 끝난 것이다.
    redis.delete("character:v11:" + TEACHER + ":armory");
    when(detailsPort.fetchArmory(TEACHER))
        .thenReturn(
            character(
                TEACHER, OTHER_SERVER, "버서커", cards(List.of("니나브"), new int[] {5}), List.of()));
    get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(TEACHER));
    awaitCards(TEACHER, 7);
    assertThat(
            jdbc.sql(
                    "SELECT awake_count FROM character_card cc JOIN characters c USING (character_id) WHERE c.character_name = :n AND cc.card_name = '발탄'")
                .param("n", ALT)
                .query(Integer.class)
                .single())
        .isEqualTo(4);
  }

  @Test
  void 제외_목록에_든_원정대_동료의_저장된_카드는_보이지_않는다() throws Exception {
    var roster = List.of(sibling(DEALER, SERVER, "블레이드"), sibling(ALT, SERVER, "소서리스"));
    for (var name : List.of(DEALER, ALT)) when(detailsPort.fetchSiblings(name)).thenReturn(roster);
    when(detailsPort.fetchArmory(DEALER))
        .thenReturn(
            character(
                DEALER, SERVER, "블레이드", cards(LIGHT, new int[] {5, 5, 5, 5, 5, 5}), lightSet()));
    when(detailsPort.fetchArmory(ALT))
        .thenReturn(
            character(
                ALT, SERVER, "소서리스", cards(LEGION.subList(0, 3), new int[] {4, 5, 5}), List.of()));
    for (var name : List.of(DEALER, ALT))
      get("/api/v1/characters/search?includeSiblings=false&characterName=" + encode(name));
    awaitCards(DEALER, 6);
    awaitCards(ALT, 3);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'PRIVACY_STOP')")
        .param("n", ALT)
        .param("s", SERVER)
        .update();
    try {
      var data =
          mapper
              .readTree(
                  get("/api/v1/characters/card-collection?characterName=" + encode(DEALER)).body())
              .path("data");
      assertThat(data.path("observedCharacters").asInt()).isEqualTo(1);
      assertThat(data.path("cards").size()).isEqualTo(6);
      assertThat(data.toString()).doesNotContain(ALT).doesNotContain("발탄");
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE character_name = :n").param("n", ALT).update();
    }
  }

  @Test
  void 기록이_없는_원정대도_200이고_칸은_비어_있다() throws Exception {
    when(detailsPort.fetchSiblings(EMPTY)).thenReturn(List.of(sibling(EMPTY, SERVER, "워로드")));
    var response = get("/api/v1/characters/card-collection?characterName=" + encode(EMPTY));
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("position").isNull()).isTrue();
    assertThat(data.path("rosterCharacters").asInt()).isEqualTo(1);
    assertThat(data.path("observedCharacters").asInt()).isZero();
    assertThat(data.at("/elementRecords/dealer").size()).isEqualTo(6);
    assertThat(data.at("/elementRecords/supporter/5/element").asText()).isEqualTo("FIRE");
    assertThat(data.at("/elementRecords/supporter/5/record").isNull()).isTrue();
    assertThat(data.path("cards").size()).isZero();
    assertThat(data.path("sets").size()).isZero();
    ApiContractFixture.write("card-collection-empty.json", response.body());
    verify(detailsPort, never()).fetchArmory(anyString());
  }

  @Test
  void 잘못된_이름은_400이다() throws Exception {
    assertThat(get("/api/v1/characters/card-collection?characterName=a-b").statusCode())
        .isEqualTo(400);
    assertThat(get("/api/v1/characters/card-collection").statusCode()).isEqualTo(400);
  }

  private void awaitCards(String name, int expected) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      long count =
          jdbc.sql(
                  "SELECT COUNT(*) FROM character_card cc JOIN characters c USING (character_id) WHERE c.character_name = :n")
              .param("n", name)
              .query(Long.class)
              .single();
      if (count >= expected) return;
      Thread.sleep(50);
    }
    throw new AssertionError(name + " 카드 기록이 " + expected + "장에 도달하지 않았습니다.");
  }

  private static List<CharacterLoadout.Card> cards(List<String> names, int[] awake) {
    var cards = new ArrayList<CharacterLoadout.Card>();
    for (int i = 0; i < names.size(); i++)
      cards.add(
          new CharacterLoadout.Card(
              i, names.get(i), "https://cdn.example/card" + i + ".png", awake[i], 5, "전설"));
    return cards;
  }

  private static List<CharacterLoadout.CardSet> lightSet() {
    return List.of(
        new CharacterLoadout.CardSet(
            0,
            List.of(0, 1, 2, 3, 4, 5),
            List.of(
                new CharacterLoadout.Effect("세상을 구하는 빛 2세트", "암속성 피해 감소 +10.00%"),
                new CharacterLoadout.Effect("세상을 구하는 빛 6세트 (12각성합계)", "공격 속성을 성속성으로 변환"),
                new CharacterLoadout.Effect("세상을 구하는 빛 6세트 (30각성합계)", "성속성 피해 +4.00%"))));
  }

  private static List<CharacterLoadout.CardSet> legionSet() {
    return List.of(
        new CharacterLoadout.CardSet(
            0,
            List.of(0, 1, 2, 3, 4, 5),
            List.of(
                new CharacterLoadout.Effect("카제로스의 군단장 2세트", "성속성 피해 감소 +10.00%"),
                new CharacterLoadout.Effect("카제로스의 군단장 6세트 (24각성합계)", "암속성 피해 +4.00%"),
                new CharacterLoadout.Effect("카제로스의 군단장 6세트 (30각성합계)", "암속성 피해 +4.00%"))));
  }

  private static CharacterSibling sibling(String name, String server, String className) {
    return new CharacterSibling(name, server, className, 70, new BigDecimal("1700"));
  }

  private static FetchedCharacter character(
      String name,
      String server,
      String className,
      List<CharacterLoadout.Card> cards,
      List<CharacterLoadout.CardSet> sets) {
    var profile =
        new CharacterProfile(
            name,
            server,
            className,
            70,
            new BigDecimal("1700"),
            null,
            null,
            null,
            null,
            null,
            List.of());
    var loadout =
        new CharacterLoadout(
            List.of(), null, null, new CharacterLoadout.Cards(cards, sets), null, null);
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
