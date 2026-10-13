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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 길드 탭 집계(L36·K28·M10). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "ranking.guilds.enabled=true")
class GuildDetailIntegrationTest {
  private static final String SERVER = "탭서버";
  private static final String OTHER = "탭다른서버";
  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired CharacterHistoryPort history;
  private final JsonMapper mapper = new JsonMapper();
  private final Instant now = Instant.now();

  @BeforeEach
  @AfterEach
  void 정리() {
    jdbc.sql("DELETE FROM characters WHERE server_name IN (:s, :o)")
        .param("s", SERVER)
        .param("o", OTHER)
        .update();
    jdbc.sql("DELETE FROM public_exclusion WHERE character_name LIKE '탭%'").update();
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
  }

  /**
   * 새벽길드: 공개 길드원 6명(아이템 레벨 1700·1705·1710·1720·1725·1690, 직업 블레이드 4·바드 2, 전투력 관측 3명), 처리정지 1명, 40일
   * 전에만 관측한 1명. 다른 서버의 같은 이름 길드 1명.
   */
  private void 표본() {
    member(SERVER, "새벽길드", "탭가", "블레이드", 1700, "3000.00", "길드장", now);
    member(SERVER, "새벽길드", "탭나", "블레이드", 1705, "3100.00", "길드원", now);
    member(SERVER, "새벽길드", "탭다", "바드", 1710, null, null, now);
    member(SERVER, "새벽길드", "탭라", "블레이드", 1720, "3250.50", "부길드장", now);
    member(SERVER, "새벽길드", "탭마", "바드", 1725, null, "길드원", now);
    member(SERVER, "새벽길드", "탭바", "블레이드", 1690, null, "길드원", now);
    member(SERVER, "새벽길드", "탭정지", "블레이드", 1800, "9000.00", "길드원", now);
    exclude("탭정지");
    member(SERVER, "새벽길드", "탭오래", "블레이드", 1750, null, null, now.minus(Duration.ofDays(40)));
    member(OTHER, "새벽길드", "탭다른", "블레이드", 1600, null, null, now);
  }

  @Test
  void 관측한_공개_길드원으로_목록과_평균_분포를_내고_처리정지와_기간_밖은_뺀다() throws Exception {
    표본();
    var path = "/api/v1/guilds/" + encode(SERVER) + "/" + encode("새벽길드");
    var response = get(path);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    var body = response.body();
    var data = mapper.readTree(body).path("data");

    assertThat(data.at("/meta/memberCount").asLong()).isEqualTo(6);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(data.at("/meta/minMembers").asInt()).isEqualTo(5);
    assertThat(data.at("/meta/activeDays").asInt()).isEqualTo(30);
    assertThat(data.at("/meta/ruleVersion").asText()).isEqualTo("guild-detail-v1");
    assertThat(data.path("serverName").asText()).isEqualTo(SERVER);
    assertThat(data.path("guildName").asText()).isEqualTo("새벽길드");

    // 아이템 레벨: (1700+1705+1710+1720+1725+1690)/6 = 1708.33, 중앙값 (1705+1710)/2 = 1707.5
    var summary = data.path("summary");
    assertThat(summary.path("itemLevelAvg").decimalValue()).isEqualByComparingTo("1708.33");
    assertThat(summary.path("itemLevelMedian").decimalValue()).isEqualByComparingTo("1707.50");
    assertThat(summary.path("itemLevelMax").decimalValue()).isEqualByComparingTo("1725");
    assertThat(summary.path("itemLevelMin").decimalValue()).isEqualByComparingTo("1690");
    // 전투력: 관측한 3명만. (3000+3100+3250.5)/3 = 3116.83, 중앙값 3100
    assertThat(summary.path("combatPowerAvg").decimalValue()).isEqualByComparingTo("3116.83");
    assertThat(summary.path("combatPowerMedian").decimalValue()).isEqualByComparingTo("3100");
    assertThat(summary.path("combatPowerCount").asLong()).isEqualTo(3);

    var classes = data.path("classes");
    assertThat(classes.at("/0/className").asText()).isEqualTo("블레이드");
    assertThat(classes.at("/0/count").asLong()).isEqualTo(4);
    assertThat(classes.at("/0/share").decimalValue()).isEqualByComparingTo("66.67");
    assertThat(classes.at("/1/className").asText()).isEqualTo("바드");
    assertThat(classes.at("/1/share").decimalValue()).isEqualByComparingTo("33.33");

    // 구간: 1690 1명, 1700 2명(1700·1705), 1710 1명, 1720 2명(1720·1725)
    assertThat(data.path("itemLevelBins").toString())
        .isEqualTo(
            "[{\"from\":1690,\"count\":1},{\"from\":1700,\"count\":2},{\"from\":1710,\"count\":1},{\"from\":1720,\"count\":2}]");
    assertThat(data.path("combatPowerBins").toString())
        .isEqualTo(
            "[{\"from\":3000,\"count\":1},{\"from\":3100,\"count\":1},{\"from\":3200,\"count\":1}]");

    var members = data.path("members");
    assertThat(members.size()).isEqualTo(6);
    assertThat(members.at("/0/characterName").asText()).isEqualTo("탭마");
    assertThat(members.at("/0/guildMemberGrade").asText()).isEqualTo("길드원");
    assertThat(members.at("/0/combatPower").isNull()).isTrue();
    assertThat(members.at("/1/characterName").asText()).isEqualTo("탭라");
    assertThat(members.at("/1/guildMemberGrade").asText()).isEqualTo("부길드장");
    assertThat(members.at("/2/guildMemberGrade").isNull()).isTrue();
    // 관측 시각은 분 단위.
    var observed = Instant.parse(members.at("/0/observedAt").asText());
    assertThat(observed).isEqualTo(now.truncatedTo(ChronoUnit.MINUTES));
    // 처리정지·기간 밖·다른 서버는 없다.
    assertThat(body).doesNotContain("탭정지", "탭오래", "탭다른", "9000");

    // 같은 정의: 길드 랭킹(전체 기간 같은 30일)의 memberCount와 같다.
    var ranking =
        mapper
            .readTree(get("/api/v1/rankings/guilds?server=" + encode(SERVER)).body())
            .at("/data/rows/0");
    assertThat(ranking.path("guildName").asText()).isEqualTo("새벽길드");
    assertThat(ranking.path("memberCount").asLong()).isEqualTo(6);

    // 두 번째 요청은 캐시에서 같은 응답이다.
    assertThat(get(path).body()).isEqualTo(body);
    ApiContractFixture.write("guild-detail.json", body);
  }

  @Test
  void 길드원이_5명_미만이면_SMALL이고_값은_그대로_준다() throws Exception {
    member(SERVER, "작은길드", "탭작가", "바드", 1700, null, null, now);
    member(SERVER, "작은길드", "탭작나", "바드", 1710, null, null, now);
    var data = data("/api/v1/guilds/" + encode(SERVER) + "/" + encode("작은길드"));
    assertThat(data.at("/meta/memberCount").asLong()).isEqualTo(2);
    assertThat(data.at("/meta/sampleStatus").asText()).isEqualTo("SMALL");
    assertThat(data.at("/summary/itemLevelAvg").decimalValue()).isEqualByComparingTo("1705");
    assertThat(data.at("/summary/combatPowerAvg").isNull()).isTrue();
    assertThat(data.at("/summary/combatPowerMedian").isNull()).isTrue();
    assertThat(data.at("/summary/combatPowerCount").asLong()).isZero();
    assertThat(data.path("combatPowerBins").size()).isZero();
  }

  @Test
  void 영문_길드_이름은_대소문자를_무시하고_철자가_여럿이면_길드원이_많은_쪽이다() throws Exception {
    member(SERVER, "AbcGuild", "탭영가", "바드", 1700, null, null, now);
    member(SERVER, "AbcGuild", "탭영나", "바드", 1700, null, null, now);
    member(SERVER, "ABCGUILD", "탭영다", "바드", 1700, null, null, now);
    var data = data("/api/v1/guilds/" + encode(SERVER) + "/abcguild");
    assertThat(data.path("guildName").asText()).isEqualTo("AbcGuild");
    assertThat(data.at("/meta/memberCount").asLong()).isEqualTo(2);
  }

  @Test
  void 처리정지만_있는_길드는_모르는_길드와_완전히_같은_404다() throws Exception {
    member(SERVER, "숨은길드", "탭숨김", "바드", 1700, null, null, now);
    exclude("탭숨김");
    var hidden = get("/api/v1/guilds/" + encode(SERVER) + "/" + encode("숨은길드"));
    var unknown = get("/api/v1/guilds/" + encode(SERVER) + "/" + encode("없는길드"));
    assertThat(hidden.statusCode()).isEqualTo(404);
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(hidden.body()).isEqualTo(unknown.body());
    assertThat(mapper.readTree(hidden.body()).path("code").asText())
        .isEqualTo("STATS_GUILD_NOT_OBSERVED");
  }

  @Test
  void 프로필을_받으면_직위를_길드_이름과_함께_덮어쓰고_길드가_없으면_비운다() {
    history.record(profile("새벽길드", "부길드장"), now);
    assertThat(grade()).isEqualTo("부길드장");
    history.record(profile("새벽길드", "길드원"), now.plusSeconds(1));
    assertThat(grade()).isEqualTo("길드원");
    // 길드를 나가면 공식 값이 비어 직위도 남지 않는다.
    history.record(profile("  ", "길드원"), now.plusSeconds(2));
    assertThat(grade()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/guilds/SERVER/%EA%B0%80%EB%82%98%EB%8B%A4%EB%9D%BC%EB%A7%88%EB%B0%94%EC%82%AC%EC%95%84%EC%9E%90%EC%B0%A8%EC%B9%B4%ED%83%80%ED%8C%8C",
        "/api/v1/guilds/SERVER/%ED%81%B0%20%EA%B8%B8%EB%93%9C",
        "/api/v1/guilds/SERVER/%ED%81%B0%25",
        "/api/v1/guilds/SERVER/a_b",
        "/api/v1/guilds/SERVER/abc?activeDays=5"
      })
  void 잘못된_입력은_400이다(String path) throws Exception {
    assertThat(get(path.replace("SERVER", encode(SERVER))).statusCode()).as(path).isEqualTo(400);
  }

  private CharacterProfile profile(String guildName, String grade) {
    return new CharacterProfile(
        "탭기록",
        SERVER,
        "블레이드",
        70,
        new BigDecimal("1700.00"),
        new BigDecimal("3000.00"),
        null,
        null,
        null,
        guildName,
        List.of(),
        null,
        new CharacterProfile.Inner(List.of(), null, null, null, null, grade));
  }

  private String grade() {
    return jdbc.sql(
            "SELECT guild_member_grade FROM characters WHERE server_name = :s AND character_name = '탭기록'")
        .param("s", SERVER)
        .query(String.class)
        .list()
        .getFirst();
  }

  private void member(
      String server,
      String guild,
      String name,
      String className,
      int itemLevel,
      String combatPower,
      String grade,
      Instant observedAt) {
    jdbc.sql(
            """
            INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
              combat_power, guild_name, guild_member_grade, last_synced_at, profile_refreshed_at)
            VALUES (:id, :name, :server, :className, :level, :cp, :guild, :grade, :at, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("className", className)
        .param("level", BigDecimal.valueOf(itemLevel))
        .param("cp", combatPower == null ? null : new BigDecimal(combatPower))
        .param("guild", guild)
        .param("grade", grade)
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

  private JsonNode data(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
    return mapper.readTree(response.body()).path("data");
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
