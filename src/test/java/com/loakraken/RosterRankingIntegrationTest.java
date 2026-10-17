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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 원정대 랭킹(#297): 기준(아이템 레벨·전투력·원정대 레벨) × 합산(상위 6명 평균·합·전체 평균), 공개 제외, 6명 미만 n/6, 전투력 관측 누락 원정대 제외,
 * 표본 30 미만 참고값. 표본 4개(정상·빈 결과·30 미만·동률)를 build/generated에 남긴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.rosters.enabled=true")
class RosterRankingIntegrationTest {
  private static final String SERVER = "원정서버";
  private static final String TIE_SERVER = "원정동률서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CharacterHistoryPort history;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql(
            "DELETE FROM character_observation WHERE character_id IN (SELECT character_id FROM characters WHERE server_name IN (:s, :t))")
        .param("s", SERVER)
        .param("t", TIE_SERVER)
        .update();
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :t)")
        .param("s", SERVER)
        .param("t", TIE_SERVER)
        .update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '원정%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 기본은_아이템_레벨_상위_6명_평균이고_대표와_구성에서_공개_제외를_뺀다() throws Exception {
    // 일곱 명 원정대: 공개 제외된 1750은 빠지고, 남은 여섯(1700~1650 중 상위 6명)의 평균.
    var big = UUID.randomUUID();
    member(SERVER, big, "원정제외", 1750);
    member(SERVER, big, "원정가", 1700);
    member(SERVER, big, "원정나", 1690);
    member(SERVER, big, "원정다", 1680);
    member(SERVER, big, "원정라", 1670);
    member(SERVER, big, "원정마", 1660);
    member(SERVER, big, "원정바", 1650);
    member(SERVER, big, "원정사", 1600);
    exclude("원정제외");
    // 두 명 원정대: 6명보다 적어도 6으로 나누고(식스맨 평균 규칙) usedCharacters=2.
    var small = UUID.randomUUID();
    member(SERVER, small, "원정작은", 1720);
    member(SERVER, small, "원정작은둘", 1700);
    // 오래전에만 관측한 캐릭터는 activeDays 밖이라 빠진다.
    var stale = UUID.randomUUID();
    member(SERVER, stale, "원정오래", 1800, null, null, Instant.now().minus(Duration.ofDays(40)));

    var response = get("/api/v1/rankings/rosters?server=" + encode(SERVER));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("rankings-rosters-collecting.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("basis").asText()).isEqualTo("ITEM_LEVEL");
    assertThat(data.path("aggregate").asText()).isEqualTo("TOP6_AVG");
    assertThat(data.at("/meta/populationUnit").asText()).isEqualTo("ROSTER");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(2);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(data.path("excludedRosters").asLong()).isZero();
    var rows = data.path("rows");
    assertThat(rows.size()).isEqualTo(2);
    // 일곱 명: 상위 6명 합 10050 ÷ 6 = 1675. 두 명: (1720 + 1700) ÷ 6 = 570.
    assertThat(rows.at("/0/characterName").asText()).isEqualTo("원정가");
    assertThat(rows.at("/0/value").decimalValue()).isEqualByComparingTo("1675.00");
    assertThat(rows.at("/0/usedCharacters").asLong()).isEqualTo(6);
    assertThat(rows.at("/0/characterCount").asLong()).isEqualTo(7);
    assertThat(names(rows.at("/0/composition")))
        .containsExactly("원정가", "원정나", "원정다", "원정라", "원정마", "원정바");
    assertThat(rows.at("/1/characterName").asText()).isEqualTo("원정작은");
    assertThat(rows.at("/1/value").decimalValue()).isEqualByComparingTo("570.00");
    assertThat(rows.at("/1/usedCharacters").asLong()).isEqualTo(2);
    assertThat(rows.at("/1/characterCount").asLong()).isEqualTo(2);
    assertThat(response.body()).doesNotContain("원정제외").doesNotContain("원정오래");
    SiteNames.assertAbsent(response.body());

    var sum = data(get("/api/v1/rankings/rosters?aggregate=TOP6_SUM&server=" + encode(SERVER)));
    assertThat(sum.at("/rows/0/characterName").asText()).isEqualTo("원정가");
    assertThat(sum.at("/rows/0/value").decimalValue()).isEqualByComparingTo("10050.00");
    var all = data(get("/api/v1/rankings/rosters?aggregate=ALL_AVG&server=" + encode(SERVER)));
    assertThat(all.at("/rows/1/characterName").asText()).isEqualTo("원정가");
    // (1700+1690+1680+1670+1660+1650+1600)/7 = 1664.2857 → 1664.29
    assertThat(all.at("/rows/1/value").decimalValue()).isEqualByComparingTo("1664.29");
    assertThat(all.at("/rows/1/usedCharacters").asLong()).isEqualTo(7);
  }

  @Test
  void 전투력_기준은_1640_이상_관측_누락이_있는_원정대를_빼고_수를_준다() throws Exception {
    var full = UUID.randomUUID();
    member(SERVER, full, "원정전투가", 1700, "3000.50", null, Instant.now());
    member(SERVER, full, "원정전투나", 1660, "2500.00", null, Instant.now());
    // 1640 미만은 전투력이 없어도 괜찮다(수집 대상이 아님).
    member(SERVER, full, "원정전투낮음", 1600, null, null, Instant.now());
    var missing = UUID.randomUUID();
    member(SERVER, missing, "원정누락가", 1720, "4000.00", null, Instant.now());
    member(SERVER, missing, "원정누락나", 1650, null, null, Instant.now());
    // 검색 스냅숏 경로(전투력·last_synced_at만 갱신)로 들어온 전투력도 관측으로 친다.
    var snapshot = UUID.randomUUID();
    member(SERVER, snapshot, "원정스냅숏", 1640, "1000.00", null, Instant.now());
    jdbc.sql("UPDATE characters SET profile_refreshed_at = NULL WHERE character_name = '원정스냅숏'")
        .update();

    var data = data(get("/api/v1/rankings/rosters?basis=COMBAT_POWER&server=" + encode(SERVER)));
    assertThat(data.path("basis").asText()).isEqualTo("COMBAT_POWER");
    assertThat(data.path("excludedRosters").asLong()).isEqualTo(1);
    assertThat(data.at("/meta/population").asLong()).isEqualTo(2);
    assertThat(data.at("/rows/1/characterName").asText()).isEqualTo("원정스냅숏");
    assertThat(data.at("/rows/0/characterName").asText()).isEqualTo("원정전투가");
    assertThat(data.at("/rows/0/combatPower").decimalValue()).isEqualByComparingTo("3000.50");
    // 아이템 레벨 상위 6명(1700·1660·1600) 중 전투력 있는 둘의 합 5500.50 ÷ 6 = 916.75
    assertThat(data.at("/rows/0/value").decimalValue()).isEqualByComparingTo("916.75");
    assertThat(data.at("/rows/0/usedCharacters").asLong()).isEqualTo(2);
    assertThat(names(data.at("/rows/0/composition"))).containsExactly("원정전투가", "원정전투나");
  }

  @Test
  void 서버_조건이_없으면_대표_캐릭터_서버의_상위_6명만_식스맨이다() throws Exception {
    // 한 원정대가 두 서버에 걸쳐 있다: 대표(1950)는 SERVER, 더 많은 고레벨 캐릭터는 TIE_SERVER.
    var key = UUID.randomUUID();
    member(SERVER, key, "원정섞임대표", 1950, null, null, Instant.now());
    member(SERVER, key, "원정섞임가", 1940, null, null, Instant.now());
    member(SERVER, key, "원정섞임나", 1930, null, null, Instant.now());
    for (int i = 0; i < 4; i++)
      member(TIE_SERVER, key, "원정섞임다른" + i, 1945 - i, null, null, Instant.now());

    // 서버 조건 없음: 대표 서버(SERVER) 3명만 — (1950 + 1940 + 1930) ÷ 6 = 970. 예전처럼 서버를 섞으면 1944였다.
    var all = data(get("/api/v1/rankings/rosters?basis=ITEM_LEVEL&aggregate=TOP6_AVG&size=100"));
    var row = rowOf(all, "원정섞임대표");
    assertThat(row.path("value").decimalValue()).isEqualByComparingTo("970.00");
    assertThat(row.path("usedCharacters").asLong()).isEqualTo(3);
    assertThat(row.path("composition").toString()).doesNotContain("원정섞임다른");

    // 서버 조건이 있으면 그 서버 캐릭터만: TIE_SERVER 4명 (1945 + 1944 + 1943 + 1942) ÷ 6 = 1295.67.
    var tie =
        data(
            get(
                "/api/v1/rankings/rosters?basis=ITEM_LEVEL&aggregate=TOP6_AVG&server="
                    + encode(TIE_SERVER)));
    var tieRow = rowOf(tie, "원정섞임다른0");
    assertThat(tieRow.path("value").decimalValue()).isEqualByComparingTo("1295.67");
    assertThat(tieRow.path("usedCharacters").asLong()).isEqualTo(4);
  }

  private static tools.jackson.databind.JsonNode rowOf(
      tools.jackson.databind.JsonNode data, String representative) {
    for (var row : data.path("rows"))
      if (row.path("characterName").asText().equals(representative)) return row;
    throw new AssertionError("대표 캐릭터 행이 없습니다: " + representative);
  }

  @Test
  void 식스맨은_값이_아니라_아이템_레벨_상위_6명으로_고른다() throws Exception {
    // 전투력 기준: 전투력이 가장 높은 1600 캐릭터는 아이템 레벨 7위라 식스맨에 들지 않는다.
    var key = UUID.randomUUID();
    for (int i = 0; i < 6; i++)
      member(SERVER, key, "원정상위" + i, 1700 - i, "2000.00", null, Instant.now());
    member(SERVER, key, "원정강한부캐", 1600, "9000.00", null, Instant.now());
    var data =
        data(
            get(
                "/api/v1/rankings/rosters?basis=COMBAT_POWER&aggregate=TOP6_SUM&server="
                    + encode(SERVER)));
    assertThat(data.at("/rows/0/value").decimalValue()).isEqualByComparingTo("12000.00");
    assertThat(data.at("/rows/0/usedCharacters").asLong()).isEqualTo(6);
    assertThat(data.at("/rows/0/composition").toString()).doesNotContain("원정강한부캐");
  }

  @Test
  void 원정대_레벨_기준은_원정대마다_값_하나다() throws Exception {
    var high = UUID.randomUUID();
    member(SERVER, high, "원정레벨가", 1650, null, 312, Instant.now());
    member(SERVER, high, "원정레벨나", 1700, null, null, Instant.now());
    var low = UUID.randomUUID();
    member(SERVER, low, "원정레벨다", 1750, null, 280, Instant.now());

    var data = data(get("/api/v1/rankings/rosters?basis=ROSTER_LEVEL&server=" + encode(SERVER)));
    assertThat(data.at("/rows/0/value").decimalValue()).isEqualByComparingTo("312.00");
    // 대표는 아이템 레벨이 가장 높은 캐릭터, 구성은 아이템 레벨 순.
    assertThat(data.at("/rows/0/characterName").asText()).isEqualTo("원정레벨나");
    assertThat(names(data.at("/rows/0/composition"))).containsExactly("원정레벨나", "원정레벨가");
    assertThat(data.at("/rows/0/usedCharacters").asLong()).isEqualTo(1);
    assertThat(data.at("/rows/1/value").decimalValue()).isEqualByComparingTo("280.00");
  }

  @Test
  void 값이_같으면_같은_순위다() throws Exception {
    var a = UUID.randomUUID();
    member(TIE_SERVER, a, "원정동률가", 1700);
    var b = UUID.randomUUID();
    member(TIE_SERVER, b, "원정동률나", 1700);
    var c = UUID.randomUUID();
    member(TIE_SERVER, c, "원정동률다", 1690);
    var response = get("/api/v1/rankings/rosters?server=" + encode(TIE_SERVER));
    ApiContractFixture.write("rankings-rosters-tie.json", response.body());
    var rows = mapper.readTree(response.body()).at("/data/rows");
    assertThat(rows.at("/0/rank").asLong()).isEqualTo(1);
    assertThat(rows.at("/1/rank").asLong()).isEqualTo(1);
    assertThat(rows.at("/2/rank").asLong()).isEqualTo(3);
  }

  @Test
  void 원정대가_30개_이상이면_READY다() throws Exception {
    for (int i = 0; i < 31; i++) {
      var key = UUID.randomUUID();
      member(TIE_SERVER, key, "원정표본" + i, 1680 + i);
      member(TIE_SERVER, key, "원정표본부" + i, 1650 + i);
    }
    var response = get("/api/v1/rankings/rosters?size=3&server=" + encode(TIE_SERVER));
    ApiContractFixture.write("rankings-rosters.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(31);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.path("rows").size()).isEqualTo(3);
    assertThat(data.at("/rows/0/characterName").asText()).isEqualTo("원정표본30");
    var beyond = data(get("/api/v1/rankings/rosters?size=50&page=2&server=" + encode(TIE_SERVER)));
    assertThat(beyond.path("rows").size()).isZero();
    assertThat(beyond.at("/meta/population").asLong()).isEqualTo(31);
  }

  @Test
  void 결과가_없으면_빈_목록이다() throws Exception {
    var response = get("/api/v1/rankings/rosters?server=" + encode("원정없음서버"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("rankings-rosters-empty.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("rows").size()).isZero();
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(data.path("excludedRosters").asLong()).isZero();
  }

  @Test
  void 기준이나_합산_값이_틀리면_400이다() throws Exception {
    assertThat(get("/api/v1/rankings/rosters?basis=GOLD").statusCode()).isEqualTo(400);
    assertThat(get("/api/v1/rankings/rosters?aggregate=MAX").statusCode()).isEqualTo(400);
  }

  @Test
  void 프로필을_받으면_원정대_레벨을_저장하고_값이_없으면_이전_값을_둔다() {
    history.record(profile(287), Instant.now());
    assertThat(expeditionLevel()).isEqualTo(287);
    history.record(profile(null), Instant.now().plusSeconds(1));
    assertThat(expeditionLevel()).isEqualTo(287);
  }

  private CharacterProfile profile(Integer expeditionLevel) {
    return new CharacterProfile(
        "원정레벨기록",
        SERVER,
        "블레이드",
        70,
        new BigDecimal("1700.00"),
        new BigDecimal("3000.00"),
        expeditionLevel,
        null,
        null,
        null,
        List.of());
  }

  private Integer expeditionLevel() {
    return jdbc.sql(
            "SELECT expedition_level FROM characters WHERE server_name = :s AND character_name = '원정레벨기록'")
        .param("s", SERVER)
        .query(Integer.class)
        .single();
  }

  private void member(String server, UUID roster, String name, int itemLevel) {
    member(server, roster, name, itemLevel, null, null, Instant.now());
  }

  private void member(
      String server,
      UUID roster,
      String name,
      int itemLevel,
      String combatPower,
      Integer expeditionLevel,
      Instant observedAt) {
    var at = observedAt.atOffset(ZoneOffset.UTC);
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              combat_power, expedition_level, last_synced_at, profile_refreshed_at, roster_key, roster_scanned_at)
            VALUES (:id, :name, :server, '블레이드', :level, :cp, :exp, :at, :refreshed, :roster, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("level", BigDecimal.valueOf(itemLevel))
        .param("cp", combatPower == null ? null : new BigDecimal(combatPower))
        .param("exp", expeditionLevel)
        .param("at", at)
        .param("refreshed", combatPower == null ? null : at)
        .param("roster", roster)
        .update();
  }

  private void exclude(String name) {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'USER_REQUEST')")
        .param("n", name)
        .param("s", SERVER)
        .update();
  }

  private static List<String> names(JsonNode composition) {
    var names = new ArrayList<String>();
    composition.forEach(member -> names.add(member.path("characterName").asText()));
    return names;
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
