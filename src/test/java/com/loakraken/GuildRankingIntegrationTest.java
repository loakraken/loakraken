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
 * 길드 랭킹(#301): 같은 서버·길드 이름의 관측 공개 캐릭터, 5명 미만 제외, 평균·합 정렬, 표본 30 미만 참고값. 표본 4개(정상·빈 결과·30 미만·동률)를
 * build/generated에 남긴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.guilds.enabled=true")
class GuildRankingIntegrationTest {
  private static final String SERVER = "길드서버";
  private static final String TIE_SERVER = "길드동률서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CharacterHistoryPort history;
  private final JsonMapper mapper = new JsonMapper();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :t)")
        .param("s", SERVER)
        .param("t", TIE_SERVER)
        .update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '길드%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test
  void 기본은_확인한_길드원_아이템_레벨_평균이고_5명_미만_길드는_뺀다() throws Exception {
    // 큰 길드: 여섯 명 중 공개 제외 한 명은 빠져 다섯 명(평균 1680), 하나는 전투력 관측.
    guild(SERVER, "큰길드", "길드큰", 1700, 1690, 1680, 1670, 1660);
    member(SERVER, "큰길드", "길드제외", 1800, null, Instant.now());
    exclude("길드제외");
    member(SERVER, "큰길드", "길드전투", 1680, "3000.00", Instant.now());
    // 높은 길드: 다섯 명 평균 1710.
    guild(SERVER, "높은길드", "길드높", 1720, 1715, 1710, 1705, 1700);
    // 작은 길드: 네 명이라 빠진다.
    guild(SERVER, "작은길드", "길드작", 1750, 1750, 1750, 1750);
    // 오래전에만 관측한 길드원은 빠진다(남은 넷이라 이 길드도 빠짐).
    guild(SERVER, "오래길드", "길드오래", 1700, 1700, 1700, 1700);
    member(SERVER, "오래길드", "길드오래끝", 1700, null, Instant.now().minus(Duration.ofDays(40)));

    // 검색 스냅숏만 최근이고 프로필(길드 이름) 관측이 오래된 행은 길드 이름이 낡았을 수 있어 뺀다.
    guild(SERVER, "스냅숏길드", "길드스냅", 1700, 1700, 1700, 1700, 1700);
    jdbc.sql("UPDATE characters SET profile_refreshed_at = :old WHERE guild_name = '스냅숏길드'")
        .param("old", Instant.now().minus(Duration.ofDays(40)).atOffset(ZoneOffset.UTC))
        .update();

    var response = get("/api/v1/rankings/guilds?server=" + encode(SERVER));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("스냅숏길드");
    ApiContractFixture.write("rankings-guilds-collecting.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("sort").asText()).isEqualTo("AVG");
    assertThat(data.path("minMembers").asInt()).isEqualTo(5);
    assertThat(data.path("excludedGuilds").asLong()).isEqualTo(2);
    assertThat(data.at("/meta/populationUnit").asText()).isEqualTo("GUILD");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(2);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    var rows = data.path("rows");
    assertThat(rows.at("/0/guildName").asText()).isEqualTo("높은길드");
    assertThat(rows.at("/0/itemLevelAvg").decimalValue()).isEqualByComparingTo("1710.00");
    assertThat(rows.at("/0/combatPowerAvg").isNull()).isTrue();
    assertThat(rows.at("/1/guildName").asText()).isEqualTo("큰길드");
    assertThat(rows.at("/1/memberCount").asLong()).isEqualTo(6);
    assertThat(rows.at("/1/itemLevelAvg").decimalValue()).isEqualByComparingTo("1680.00");
    assertThat(rows.at("/1/itemLevelSum").decimalValue()).isEqualByComparingTo("10080.00");
    assertThat(rows.at("/1/combatPowerAvg").decimalValue()).isEqualByComparingTo("3000.00");
    assertThat(rows.at("/1/combatPowerCount").asLong()).isEqualTo(1);
    assertThat(response.body()).doesNotContain("길드제외");
    SiteNames.assertAbsent(response.body());

    var sum = data(get("/api/v1/rankings/guilds?sort=SUM&server=" + encode(SERVER)));
    assertThat(sum.path("sort").asText()).isEqualTo("SUM");
    assertThat(sum.at("/rows/0/guildName").asText()).isEqualTo("큰길드");
  }

  @Test
  void 값이_같으면_같은_순위다() throws Exception {
    guild(TIE_SERVER, "동률가", "길드가", 1700, 1700, 1700, 1700, 1700);
    guild(TIE_SERVER, "동률나", "길드나", 1700, 1700, 1700, 1700, 1700);
    guild(TIE_SERVER, "동률다", "길드다", 1690, 1690, 1690, 1690, 1690);
    var response = get("/api/v1/rankings/guilds?server=" + encode(TIE_SERVER));
    ApiContractFixture.write("rankings-guilds-tie.json", response.body());
    var rows = mapper.readTree(response.body()).at("/data/rows");
    assertThat(rows.at("/0/rank").asLong()).isEqualTo(1);
    assertThat(rows.at("/1/rank").asLong()).isEqualTo(1);
    assertThat(rows.at("/2/rank").asLong()).isEqualTo(3);
  }

  @Test
  void 길드_이름_일부로_찾으면_전체_순위를_그대로_준다() throws Exception {
    // M21: 평균 순위 높은길드 1710(1위) · 큰길드 1680(2위) · AbcGuild 1650(3위), 작은길드는 네 명이라 빠진다.
    guild(SERVER, "높은길드", "길드높", 1720, 1715, 1710, 1705, 1700);
    guild(SERVER, "큰길드", "길드큰", 1700, 1690, 1680, 1670, 1660);
    guild(SERVER, "AbcGuild", "길드에이", 1650, 1650, 1650, 1650, 1650);
    guild(SERVER, "작은길드", "길드작", 1750, 1750, 1750, 1750);

    var big =
        data(get("/api/v1/rankings/guilds?server=" + encode(SERVER) + "&guild=" + encode("큰")));
    assertThat(big.path("rows").size()).isEqualTo(1);
    assertThat(big.at("/rows/0/guildName").asText()).isEqualTo("큰길드");
    // 다시 매기지 않는다: 전체 순위 2위 그대로.
    assertThat(big.at("/rows/0/rank").asLong()).isEqualTo(2);
    assertThat(big.path("excludedMatches").asLong()).isZero();
    assertThat(big.path("matchCount").asLong()).isEqualTo(1);
    // 전체 값(모집단·제외 수)은 이름 조건과 무관하다.
    assertThat(big.at("/meta/population").asLong()).isEqualTo(3);
    assertThat(big.path("excludedGuilds").asLong()).isEqualTo(1);

    // 대소문자 무시.
    var ascii = data(get("/api/v1/rankings/guilds?server=" + encode(SERVER) + "&guild=abc"));
    assertThat(ascii.at("/rows/0/guildName").asText()).isEqualTo("AbcGuild");
    assertThat(ascii.at("/rows/0/rank").asLong()).isEqualTo(3);

    // 여러 줄이 맞으면 원래 순서대로, 이름은 맞지만 5명 미만이라 빠진 길드는 excludedMatches.
    var many =
        data(get("/api/v1/rankings/guilds?server=" + encode(SERVER) + "&guild=" + encode("길드")));
    assertThat(many.path("rows").size()).isEqualTo(2);
    assertThat(many.at("/rows/0/guildName").asText()).isEqualTo("높은길드");
    assertThat(many.at("/rows/0/rank").asLong()).isEqualTo(1);
    assertThat(many.at("/rows/1/guildName").asText()).isEqualTo("큰길드");
    assertThat(many.at("/rows/1/rank").asLong()).isEqualTo(2);
    assertThat(many.path("excludedMatches").asLong()).isEqualTo(1);
    // 쪽 수는 matchCount(이름에 맞고 순위에 든 길드 수)로 센다. 전체 길드 수(meta.population)가 아니다.
    assertThat(many.path("matchCount").asLong()).isEqualTo(2);
    var page2 =
        data(
            get(
                "/api/v1/rankings/guilds?size=1&page=2&server="
                    + encode(SERVER)
                    + "&guild="
                    + encode("길드")));
    assertThat(page2.at("/rows/0/guildName").asText()).isEqualTo("큰길드");
    assertThat(page2.path("matchCount").asLong()).isEqualTo(2);
    // 범위를 넘은 쪽(빈 목록)에서도 matchCount를 따로 센다.
    var page3 =
        data(
            get(
                "/api/v1/rankings/guilds?size=1&page=3&server="
                    + encode(SERVER)
                    + "&guild="
                    + encode("길드")));
    assertThat(page3.path("rows").size()).isZero();
    assertThat(page3.path("matchCount").asLong()).isEqualTo(2);
    assertThat(page3.path("excludedMatches").asLong()).isEqualTo(1);

    // 맞는 줄이 없으면 200·빈 목록.
    var small = get("/api/v1/rankings/guilds?server=" + encode(SERVER) + "&guild=" + encode("작은"));
    assertThat(small.statusCode()).isEqualTo(200);
    assertThat(data(small).path("rows").size()).isZero();
    assertThat(data(small).path("excludedMatches").asLong()).isEqualTo(1);
    assertThat(data(small).path("matchCount").asLong()).isZero();

    // 다른 조건과 함께: 합계 순위(높은길드 8550 · 큰길드 8400 · AbcGuild 8250)에서도 큰길드는 2위.
    var sum =
        data(
            get(
                "/api/v1/rankings/guilds?sort=SUM&server="
                    + encode(SERVER)
                    + "&guild="
                    + encode("큰")));
    assertThat(sum.at("/rows/0/rank").asLong()).isEqualTo(2);

    // guild가 없으면 excludedMatches·matchCount는 null.
    var all = data(get("/api/v1/rankings/guilds?server=" + encode(SERVER)));
    assertThat(all.path("excludedMatches").isNull()).isTrue();
    assertThat(all.path("matchCount").isNull()).isTrue();

    // 입력 검증: 13자, 공백, 기호는 400.
    for (var bad : List.of("가나다라마바사아자차카타파", "큰 길드", "큰%", "a_b"))
      assertThat(get("/api/v1/rankings/guilds?guild=" + encode(bad)).statusCode())
          .as(bad)
          .isEqualTo(400);
  }

  @Test
  void 길드가_30개_이상이면_READY다() throws Exception {
    for (int i = 0; i < 31; i++)
      guild(TIE_SERVER, "표본길드" + i, "길드표본" + i + "_", 1650 + i, 1650, 1650, 1650, 1650);
    var response = get("/api/v1/rankings/guilds?size=3&server=" + encode(TIE_SERVER));
    ApiContractFixture.write("rankings-guilds.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(31);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.path("rows").size()).isEqualTo(3);
    assertThat(data.at("/rows/0/guildName").asText()).isEqualTo("표본길드30");
    var beyond = data(get("/api/v1/rankings/guilds?size=50&page=2&server=" + encode(TIE_SERVER)));
    assertThat(beyond.path("rows").size()).isZero();
    assertThat(beyond.at("/meta/population").asLong()).isEqualTo(31);
  }

  @Test
  void 결과가_없으면_빈_목록이다() throws Exception {
    var response = get("/api/v1/rankings/guilds?server=" + encode("길드없음서버"));
    assertThat(response.statusCode()).isEqualTo(200);
    ApiContractFixture.write("rankings-guilds-empty.json", response.body());
    var data = mapper.readTree(response.body()).path("data");
    assertThat(data.path("rows").size()).isZero();
    assertThat(data.at("/meta/population").asLong()).isZero();
    assertThat(data.path("excludedGuilds").asLong()).isZero();
  }

  @Test
  void 정렬_값이_틀리면_400이다() throws Exception {
    assertThat(get("/api/v1/rankings/guilds?sort=MAX").statusCode()).isEqualTo(400);
  }

  @Test
  void 프로필을_받으면_길드_이름을_덮어쓰고_길드가_없으면_비운다() {
    history.record(profile("새벽길드"), Instant.now());
    assertThat(guildName()).isEqualTo("새벽길드");
    history.record(profile("  "), Instant.now().plusSeconds(1));
    assertThat(guildName()).isNull();
  }

  private CharacterProfile profile(String guildName) {
    return new CharacterProfile(
        "길드기록",
        SERVER,
        "블레이드",
        70,
        new BigDecimal("1700.00"),
        new BigDecimal("3000.00"),
        null,
        null,
        null,
        guildName,
        List.of());
  }

  private String guildName() {
    return jdbc.sql(
            "SELECT guild_name FROM characters WHERE server_name = :s AND character_name = '길드기록'")
        .param("s", SERVER)
        .query(String.class)
        .list()
        .getFirst();
  }

  private void guild(String server, String guild, String prefix, int... itemLevels) {
    for (int i = 0; i < itemLevels.length; i++)
      member(server, guild, prefix + i, itemLevels[i], null, Instant.now());
  }

  private void member(
      String server,
      String guild,
      String name,
      int itemLevel,
      String combatPower,
      Instant observedAt) {
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              combat_power, guild_name, last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, '블레이드', :level, :cp, :guild, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("level", BigDecimal.valueOf(itemLevel))
        .param("cp", combatPower == null ? null : new BigDecimal(combatPower))
        .param("guild", guild)
        .param("at", observedAt.atOffset(ZoneOffset.UTC))
        .update();
  }

  private void exclude(String name) {
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES (:n, :s, 'USER_REQUEST')")
        .param("n", name)
        .param("s", SERVER)
        .update();
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
