package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 수집품(내실) 랭킹(#295): 원정대 단위·캐릭터 단위, 진행도 → Σ Point 동률, 공개 제외, 표본 30 미만 참고값. 표본 4개(정상·빈 결과·30 미만·동률)를
 * build/generated에 남긴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.collectibles.enabled=true")
class CollectibleRankingIntegrationTest {
  private static final String SERVER = "수집서버";
  private static final String TIE_SERVER = "동률서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired com.loakraken.character.application.CharacterCardPort cards;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql(
            "DELETE FROM roster_collectible WHERE roster_key IN (SELECT roster_key FROM characters WHERE server_name IN (:s, :t))")
        .param("s", SERVER)
        .param("t", TIE_SERVER)
        .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :t)")
        .param("s", SERVER)
        .param("t", TIE_SERVER)
        .update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '수집%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 원정대_단위는_진행도_순이고_대표는_아이템_레벨이_가장_높은_공개_캐릭터다() throws Exception {
    // 원정대 A: 1480/1480 + 10/15 (99.67%), 대표 후보 수집본캐(1780)는 공개 제외라 수집부캐(1760)가 대표다.
    var a = roster(SERVER, "수집본캐", 1780, "수집부캐", 1760, "수집삼캐", 1700);
    collectible(a, 1, "모코코 씨앗", 1480, 1480);
    collectible(a, 2, "거인의 심장", 10, 15);
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('수집본캐', NULL, 'USER_REQUEST')")
        .update();
    // 원정대 B: 1000/1480 + 15/15 (68.55%)
    var b = roster(SERVER, "수집둘째", 1750);
    collectible(b, 1, "모코코 씨앗", 1000, 1480);
    collectible(b, 2, "거인의 심장", 15, 15);
    // 원정대 C: 하나도 안 모음 → 빠진다.
    var c = roster(SERVER, "수집빈손", 1740);
    collectible(c, 1, "모코코 씨앗", 0, 1480);
    // 원정대 D: 관측이 오래됨(40일 전) → activeDays 30에서 빠진다.
    var d = roster(SERVER, "수집옛날", 1730);
    collectible(d, 1, "모코코 씨앗", 1480, 1480, Instant.now().minusSeconds(40L * 86_400));

    var response = get("/api/v1/rankings/collectibles?server=" + encode(SERVER));
    assertThat(response.statusCode()).isEqualTo(200);
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("unit").asText()).isEqualTo("ROSTER");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(2);
    assertThat(data.at("/meta/populationUnit").asText()).isEqualTo("ROSTER");
    // 30 미만이어도 값·순위는 그대로(참고값).
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    var first = data.at("/rows/0");
    assertThat(first.path("rank").asLong()).isEqualTo(1);
    assertThat(first.path("characterName").asText()).isEqualTo("수집부캐");
    assertThat(first.path("characterCount").asLong()).isEqualTo(2);
    assertThat(first.path("progressPercent").decimalValue()).isEqualByComparingTo("99.67");
    assertThat(first.path("point").asLong()).isEqualTo(1490);
    assertThat(first.path("maxPoint").asLong()).isEqualTo(1495);
    assertThat(first.at("/types/0/type").asText()).isEqualTo("모코코 씨앗");
    assertThat(first.at("/types/1/type").asText()).isEqualTo("거인의 심장");
    assertThat(data.at("/rows/1/characterName").asText()).isEqualTo("수집둘째");
    assertThat(data.at("/rows").size()).isEqualTo(2);
    assertThat(response.body()).doesNotContain("수집본캐", "수집빈손", "수집옛날");
    ApiContractFixture.write("rankings-collectibles-collecting.json", response.body());

    // 캐릭터 단위: 원정대 A의 공개 캐릭터 2명이 같은 값·같은 순위로 나온다.
    var characters =
        mapper
            .readTree(
                get("/api/v1/rankings/collectibles?unit=CHARACTER&server=" + encode(SERVER)).body())
            .path("data");
    assertThat(characters.path("unit").asText()).isEqualTo("CHARACTER");
    assertThat(characters.at("/meta/populationUnit").asText()).isEqualTo("CHARACTER");
    assertThat(characters.at("/rows").size()).isEqualTo(3);
    assertThat(characters.at("/rows/0/rank").asLong()).isEqualTo(1);
    assertThat(characters.at("/rows/1/rank").asLong()).isEqualTo(1);
    assertThat(characters.at("/rows/2/rank").asLong()).isEqualTo(3);
  }

  @Test
  void 진행도가_같으면_포인트가_많은_쪽이_앞이고_둘_다_같으면_같은_순위다() throws Exception {
    var a = roster(TIE_SERVER, "수집동률가", 1750);
    collectible(a, 1, "모코코 씨앗", 740, 1480); // 50.00%, 740
    var b = roster(TIE_SERVER, "수집동률나", 1740);
    collectible(b, 1, "모코코 씨앗", 740, 1480);
    var c = roster(TIE_SERVER, "수집동률다", 1730);
    collectible(c, 1, "모코코 씨앗", 750, 1500); // 50.00%, 750 → 진행도 같고 포인트가 많아 앞
    var response = get("/api/v1/rankings/collectibles?server=" + encode(TIE_SERVER));
    ApiContractFixture.write("rankings-collectibles-tie.json", response.body());
    JsonNode rows = mapper.readTree(response.body()).at("/data/rows");
    assertThat(rows.at("/0/characterName").asText()).isEqualTo("수집동률다");
    assertThat(rows.at("/0/rank").asLong()).isEqualTo(1);
    assertThat(rows.at("/1/rank").asLong()).isEqualTo(2);
    assertThat(rows.at("/2/rank").asLong()).isEqualTo(2);
  }

  @Test
  void 원정대가_30개_이상이면_READY다() throws Exception {
    for (int i = 0; i < 31; i++) {
      var key = roster(TIE_SERVER, "수집표본" + i, 1700 + i);
      collectible(key, 1, "모코코 씨앗", 1000 + i, 1480);
      collectible(key, 2, "거인의 심장", 15, 15);
    }
    var response = get("/api/v1/rankings/collectibles?size=3&server=" + encode(TIE_SERVER));
    ApiContractFixture.write("rankings-collectibles.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(31);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.at("/rows").size()).isEqualTo(3);
    assertThat(data.at("/rows/0/characterName").asText()).isEqualTo("수집표본30");
    // 범위를 넘은 페이지도 전체 수는 그대로다.
    var beyond =
        mapper
            .readTree(
                get("/api/v1/rankings/collectibles?size=50&page=2&server=" + encode(TIE_SERVER))
                    .body())
            .path("data");
    assertThat(beyond.path("rows").size()).isZero();
    assertThat(beyond.at("/meta/population").asLong()).isEqualTo(31);
  }

  @Test
  void armory를_받으면_원정대_수집품을_공식_순서로_덮어쓴다() {
    var key = roster(SERVER, "수집기록", 1700);
    cards.recordCollectibles(
        SERVER,
        "수집기록",
        java.util.List.of(
            new com.loakraken.character.domain.CharacterLoadout.Collectible(
                "모코코 씨앗", null, 952, 1480, java.util.List.of()),
            new com.loakraken.character.domain.CharacterLoadout.Collectible(
                "섬의 마음", null, 100, 104, java.util.List.of())),
        Instant.now());
    var rows =
        jdbc.sql(
                "SELECT type, point, sort_order FROM roster_collectible WHERE roster_key = :k ORDER BY sort_order")
            .param("k", key)
            .query((row, index) -> row.getString(1) + "=" + row.getInt(2) + "@" + row.getInt(3))
            .list();
    assertThat(rows).containsExactly("모코코 씨앗=952@1", "섬의 마음=100@2");
    // 이번 관측에 없는 종류(공식 종류가 바뀌거나 사라짐)는 지워 합계에 섞이지 않게 한다.
    cards.recordCollectibles(
        SERVER,
        "수집기록",
        java.util.List.of(
            new com.loakraken.character.domain.CharacterLoadout.Collectible(
                "모코코 씨앗", null, 960, 1480, java.util.List.of())),
        Instant.now());
    assertThat(
            jdbc.sql("SELECT type || '=' || point FROM roster_collectible WHERE roster_key = :k")
                .param("k", key)
                .query(String.class)
                .list())
        .containsExactly("모코코 씨앗=960");
    // 원정대 키가 없는 캐릭터(원정대를 훑기 전)는 기록하지 않는다.
    cards.recordCollectibles(
        SERVER,
        "수집모름",
        java.util.List.of(
            new com.loakraken.character.domain.CharacterLoadout.Collectible(
                "모코코 씨앗", null, 1, 1480, java.util.List.of())),
        Instant.now());
  }

  @Test
  void 결과가_없으면_빈_목록이다() throws Exception {
    var response = get("/api/v1/rankings/collectibles?server=" + encode("수집없음서버"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("rankings-collectibles-empty.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("rows").size()).isZero();
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(response.body()).doesNotContain(SiteNames.FORBIDDEN);
  }

  @Test
  void 단위_값이_틀리면_400이다() throws Exception {
    assertThat(get("/api/v1/rankings/collectibles?unit=GUILD").statusCode()).isEqualTo(400);
  }

  private UUID roster(String server, Object... nameAndLevel) {
    var key = UUID.randomUUID();
    for (int i = 0; i < nameAndLevel.length; i += 2)
      jdbc.sql(
              """
              INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
                last_synced_at, profile_refreshed_at, roster_key, roster_scanned_at)
              VALUES (:id, :name, :server, '블레이드', :level, :at, :at, :roster, :at)
              """)
          .param("id", UUID.randomUUID())
          .param("name", nameAndLevel[i])
          .param("server", server)
          .param("level", new BigDecimal(String.valueOf(nameAndLevel[i + 1])))
          .param("at", OffsetDateTime.now(ZoneOffset.UTC))
          .param("roster", key)
          .update();
    return key;
  }

  private void collectible(UUID roster, int order, String type, int point, int max) {
    collectible(roster, order, type, point, max, Instant.now());
  }

  private void collectible(UUID roster, int order, String type, int point, int max, Instant at) {
    jdbc.sql(
            """
            INSERT INTO roster_collectible (roster_key, type, sort_order, point, max_point, observed_at)
            VALUES (:roster, :type, :order, :point, :max, :at)
            """)
        .param("roster", roster)
        .param("type", type)
        .param("order", order)
        .param("point", point)
        .param("max", max)
        .param("at", at.atOffset(ZoneOffset.UTC))
        .update();
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
