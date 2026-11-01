package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CharacterCardPort;
import com.loakraken.character.domain.CharacterLoadout.Collectible;
import com.loakraken.character.domain.CharacterLoadout.CollectibleItem;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집품 항목별 보유율(#305): armory 항목 기록(사전 + 원정대 한 행), 분모 = 관측 원정대(공개 캐릭터가 있음), owned·completed, type 선택,
 * 표본 30 미만 참고값. 표본 4개(정상·빈 결과·30 미만·이름 중복)를 build/generated에 남긴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.collectibles.enabled=true")
class CollectibleOwnershipIntegrationTest {
  private static final String SERVER = "보유서버";
  private static final String BIG_SERVER = "보유표본서버";
  private static final String SEED = "보유 씨앗";
  private static final String HEART = "보유 심장";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CharacterCardPort cards;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql(
            "DELETE FROM roster_collectible_item WHERE roster_key IN (SELECT roster_key FROM characters WHERE server_name IN (:s, :b))")
        .param("s", SERVER)
        .param("b", BIG_SERVER)
        .update();
    jdbc.sql(
            "DELETE FROM roster_collectible WHERE roster_key IN (SELECT roster_key FROM characters WHERE server_name IN (:s, :b))")
        .param("s", SERVER)
        .param("b", BIG_SERVER)
        .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :b)")
        .param("s", SERVER)
        .param("b", BIG_SERVER)
        .update();
    // 항목 사전(collectible_item)은 운영에서도 지우지 않고 저장소가 메모리에 캐시하므로 테스트도 남겨 둔다.
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '보유%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void armory_항목으로_원정대별_보유를_기록하고_항목마다_보유_원정대_수를_준다() throws Exception {
    // 원정대 A: 씨앗 지역 하나는 완료(5/5), 하나는 일부(2/3). 심장 1개 가짐.
    roster("보유가", null);
    record("보유가", items(SEED, 5, 5, 2, 3), items(HEART, 1, 1, 0, 1));
    // 원정대 B: 씨앗 첫 지역 일부(1/5). 심장 없음.
    roster("보유나", null);
    record("보유나", items(SEED, 1, 5, 0, 3), items(HEART, 0, 1, 0, 1));
    // 원정대 C: 공개 제외 캐릭터뿐이라 분모에서 빠진다.
    roster("보유제외", null);
    record("보유제외", items(SEED, 5, 5, 3, 3), items(HEART, 1, 1, 1, 1));
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('보유제외', NULL, 'USER_REQUEST')")
        .update();
    // 원정대 D: 관측이 오래돼(40일 전) 빠진다.
    roster("보유옛날", null);
    cards.recordCollectibles(
        SERVER, "보유옛날", List.of(items(SEED, 5, 5, 3, 3)), Instant.now().minus(Duration.ofDays(40)));

    var response = get("/api/v1/collectibles/ownership?server=" + encode(SERVER));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("collectibles-ownership-collecting.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(2);
    assertThat(data.at("/meta/populationUnit").asText()).isEqualTo("ROSTER");
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("type").isNull()).isTrue();
    var seed1 = find(data, SEED, SEED + " 1");
    assertThat(seed1.path("sortOrder").asInt()).isEqualTo(1);
    assertThat(seed1.path("maxPoint").asInt()).isEqualTo(5);
    assertThat(seed1.path("ownedRosters").asLong()).isEqualTo(2);
    assertThat(seed1.path("completedRosters").asLong()).isEqualTo(1);
    assertThat(seed1.path("ownedPercent").decimalValue()).isEqualByComparingTo("100.00");
    assertThat(seed1.path("completedPercent").decimalValue()).isEqualByComparingTo("50.00");
    var seed2 = find(data, SEED, SEED + " 2");
    assertThat(seed2.path("ownedRosters").asLong()).isEqualTo(1);
    assertThat(seed2.path("completedRosters").asLong()).isZero();
    // 아무도 안 가진 항목도 0으로 나온다.
    var heart2 = find(data, HEART, HEART + " 2");
    assertThat(heart2.path("ownedRosters").asLong()).isZero();
    assertThat(heart2.path("ownedPercent").decimalValue()).isEqualByComparingTo("0.00");
    // 종류는 공식 순서(씨앗 → 심장).
    assertThat(indexOf(data, SEED, SEED + " 1")).isLessThan(indexOf(data, HEART, HEART + " 1"));
    assertThat(response.body()).doesNotContain("보유제외");
    SiteNames.assertAbsent(response.body());

    var hearts =
        data(
            get(
                "/api/v1/collectibles/ownership?server="
                    + encode(SERVER)
                    + "&type="
                    + encode(HEART)));
    assertThat(hearts.path("type").asText()).isEqualTo(HEART);
    assertThat(hearts.path("items").size()).isEqualTo(2);
    assertThat(hearts.at("/items/0/name").asText()).isEqualTo(HEART + " 1");

    // 기록: 원정대 한 행에 가진 항목만, 예전 관측은 최신을 덮지 않는다.
    cards.recordCollectibles(
        SERVER, "보유가", List.of(items(SEED, 0, 5, 0, 3)), Instant.now().minus(Duration.ofDays(1)));
    assertThat(
            jdbc.sql(
                    "SELECT cardinality(item_ids) FROM roster_collectible_item r JOIN characters c ON c.roster_key = r.roster_key WHERE c.character_name = '보유가'")
                .query(Integer.class)
                .single())
        .isEqualTo(3);
  }

  @Test
  void 다른_종류의_같은_이름은_다른_항목이다() throws Exception {
    roster("보유중복", null);
    record(
        "보유중복",
        new Collectible("보유 중복가", null, 1, 1, List.of(new CollectibleItem("같은 이름", 1, 1))),
        new Collectible("보유 중복나", null, 0, 1, List.of(new CollectibleItem("같은 이름", 0, 1))));
    var response = get("/api/v1/collectibles/ownership?server=" + encode(SERVER));
    ApiContractFixture.write("collectibles-ownership-duplicate-name.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(find(data, "보유 중복가", "같은 이름").path("ownedRosters").asLong()).isEqualTo(1);
    assertThat(find(data, "보유 중복나", "같은 이름").path("ownedRosters").asLong()).isZero();
  }

  @Test
  void 원정대가_30개_이상이면_READY다() throws Exception {
    for (int i = 0; i < 31; i++) {
      var key = UUID.randomUUID();
      insertCharacter(BIG_SERVER, "보유표본" + i, key);
      cards.recordCollectibles(
          BIG_SERVER,
          "보유표본" + i,
          List.of(items(SEED, i % 2 == 0 ? 5 : 1, 5, 0, 3), items(HEART, 1, 1, 0, 1)),
          Instant.now());
    }
    var response =
        get(
            "/api/v1/collectibles/ownership?server="
                + encode(BIG_SERVER)
                + "&type="
                + encode(SEED));
    ApiContractFixture.write("collectibles-ownership.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(31);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    var first = find(data, SEED, SEED + " 1");
    assertThat(first.path("ownedRosters").asLong()).isEqualTo(31);
    assertThat(first.path("completedRosters").asLong()).isEqualTo(16);
    assertThat(first.path("completedPercent").decimalValue()).isEqualByComparingTo("51.61");
  }

  @Test
  void 관측_원정대가_없으면_분모_0이고_비율은_null이다() throws Exception {
    roster("보유사전", null);
    record("보유사전", items(SEED, 1, 5, 0, 3));
    var response =
        get("/api/v1/collectibles/ownership?server=" + encode("보유없음서버") + "&type=" + encode(SEED));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("collectibles-ownership-empty.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(data.at("/items/0/ownedRosters").asLong()).isZero();
    assertThat(data.at("/items/0/ownedPercent").isNull()).isTrue();
  }

  @Test
  void 같은_종류_안_같은_이름은_한_항목이고_원정대를_한_번만_센다() throws Exception {
    roster("보유겹침", null);
    record(
        "보유겹침",
        new Collectible(
            "보유 겹침",
            null,
            3,
            2,
            List.of(new CollectibleItem("겹친 이름", 1, 1), new CollectibleItem("겹친 이름", 2, 1))));
    var data =
        data(
            get(
                "/api/v1/collectibles/ownership?server="
                    + encode(SERVER)
                    + "&type="
                    + encode("보유 겹침")));
    assertThat(data.path("items").size()).isEqualTo(1);
    assertThat(data.at("/items/0/ownedRosters").asLong()).isEqualTo(1);
  }

  @Test
  void 오래_관측되지_않은_항목은_빠지고_종류는_공식_순서다() throws Exception {
    roster("보유순서", null);
    // 공식 응답이 심장 → 씨앗 순서면 그 순서를 따른다(사전에 먼저 들어온 순서가 아님).
    record("보유순서", items(HEART, 1, 1, 0, 1), items(SEED, 1, 5, 0, 3));
    jdbc.sql(
            """
            INSERT INTO collectible_item (type, name, type_order, sort_order, max_point, last_seen_at)
            VALUES (:type, '사라진 항목', 1, 99, 1, :old) ON CONFLICT (type, name) DO UPDATE SET last_seen_at = :old
            """)
        .param("type", HEART)
        .param("old", OffsetDateTime.now(ZoneOffset.UTC).minusDays(40))
        .update();
    var data = data(get("/api/v1/collectibles/ownership?server=" + encode(SERVER)));
    assertThat(indexOf(data, HEART, HEART + " 1")).isLessThan(indexOf(data, SEED, SEED + " 1"));
    assertThat(indexOf(data, HEART, "사라진 항목")).isEqualTo(-1);
  }

  @Test
  void 아는_항목을_다시_기록해도_item_id_시퀀스를_쓰지_않는다() {
    roster("보유시퀀스", null);
    var first = Instant.now().minus(Duration.ofDays(2));
    cards.recordCollectibles(SERVER, "보유시퀀스", List.of(items(SEED, 1, 5, 0, 3)), first);
    long before = sequence();
    // 날이 바뀌면 메모리 캐시를 지나 DB에 간다(재부팅도 같다).
    cards.recordCollectibles(
        SERVER, "보유시퀀스", List.of(items(SEED, 2, 5, 0, 3)), first.plus(Duration.ofDays(1)));
    cards.recordCollectibles(
        SERVER, "보유시퀀스", List.of(items(SEED, 3, 5, 0, 3)), first.plus(Duration.ofDays(2)));
    assertThat(sequence()).isEqualTo(before);
    assertThat(heldPoints("보유시퀀스")).isEqualTo("{3}");
  }

  @Test
  void 시퀀스가_끝까지_닿아도_아는_항목_기록은_된다() {
    roster("보유끝", null);
    var first = Instant.now().minus(Duration.ofDays(2));
    cards.recordCollectibles(SERVER, "보유끝", List.of(items(HEART, 1, 1, 0, 1)), first);
    var name = "pg_get_serial_sequence('collectible_item', 'item_id')";
    try {
      jdbc.sql("SELECT setval(" + name + ", 32767, true)").query(Long.class).single();
      cards.recordCollectibles(
          SERVER, "보유끝", List.of(items(HEART, 1, 1, 1, 1)), first.plus(Duration.ofDays(1)));
      assertThat(heldPoints("보유끝")).isEqualTo("{1,1}");
    } finally {
      jdbc.sql(
              "SELECT setval(" + name + ", (SELECT MAX(item_id) FROM collectible_item) + 1, false)")
          .query(Long.class)
          .single();
    }
  }

  @Test
  void item_id가_INTEGER면_SMALLINT를_넘는_새_항목도_기록된다() {
    // 운영 시퀀스가 끝에 가까워 item_id를 INTEGER로 넓힌 경우만 본다(SMALLINT 그대로면 건너뜀).
    var columnType =
        jdbc.sql(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'collectible_item' AND column_name = 'item_id'")
            .query(String.class)
            .single();
    org.junit.jupiter.api.Assumptions.assumeTrue("integer".equals(columnType));
    roster("보유넓힘", null);
    var name = "pg_get_serial_sequence('collectible_item', 'item_id')";
    try {
      jdbc.sql("SELECT setval(" + name + ", 40000, true)").query(Long.class).single();
      cards.recordCollectibles(
          SERVER,
          "보유넓힘",
          List.of(
              new Collectible("보유 넓힘", null, 1, 1, List.of(new CollectibleItem("넓힌 항목", 1, 1)))),
          Instant.now());
      assertThat(
              jdbc.sql(
                      "SELECT r.item_ids::text FROM roster_collectible_item r JOIN characters c ON c.roster_key = r.roster_key WHERE c.character_name = '보유넓힘'")
                  .query(String.class)
                  .single())
          .isEqualTo("{40001}");
    } finally {
      jdbc.sql("DELETE FROM collectible_item WHERE type = '보유 넓힘'").update();
      jdbc.sql(
              "SELECT setval(" + name + ", (SELECT MAX(item_id) FROM collectible_item) + 1, false)")
          .query(Long.class)
          .single();
    }
  }

  private long sequence() {
    return jdbc.sql("SELECT last_value FROM collectible_item_item_id_seq")
        .query(Long.class)
        .single();
  }

  private String heldPoints(String name) {
    return jdbc.sql(
            "SELECT r.points::text FROM roster_collectible_item r JOIN characters c ON c.roster_key = r.roster_key WHERE c.character_name = :n")
        .param("n", name)
        .query(String.class)
        .single();
  }

  @Test
  void 종류_값이_틀리면_400이다() throws Exception {
    assertThat(get("/api/v1/collectibles/ownership?type=" + encode("<script>")).statusCode())
        .isEqualTo(400);
  }

  private void record(String name, Collectible... collectibles) {
    cards.recordCollectibles(SERVER, name, List.of(collectibles), Instant.now());
  }

  /** 항목 두 개짜리 종류: (첫 항목 point, max, 둘째 항목 point, max). */
  private static Collectible items(String type, int p1, int m1, int p2, int m2) {
    return new Collectible(
        type,
        null,
        p1 + p2,
        m1 + m2,
        List.of(
            new CollectibleItem(type + " 1", p1, m1), new CollectibleItem(type + " 2", p2, m2)));
  }

  private void roster(String name, UUID key) {
    insertCharacter(SERVER, name, key == null ? UUID.randomUUID() : key);
  }

  private void insertCharacter(String server, String name, UUID roster) {
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              last_synced_at, profile_refreshed_at, roster_key, roster_scanned_at)
            VALUES (:id, :name, :server, '블레이드', :level, :at, :at, :roster, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("level", new BigDecimal("1700"))
        .param("at", OffsetDateTime.now(ZoneOffset.UTC))
        .param("roster", roster)
        .update();
  }

  private static JsonNode find(JsonNode data, String type, String name) {
    for (var item : data.path("items"))
      if (type.equals(item.path("type").asText()) && name.equals(item.path("name").asText()))
        return item;
    throw new AssertionError(type + " / " + name + " 항목이 없습니다");
  }

  private static int indexOf(JsonNode data, String type, String name) {
    int index = 0;
    for (var item : data.path("items")) {
      if (type.equals(item.path("type").asText()) && name.equals(item.path("name").asText()))
        return index;
      index++;
    }
    return -1;
  }

  private JsonNode data(HttpResponse<String> response) throws Exception {
    assertThat(response.statusCode()).isEqualTo(200);
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
