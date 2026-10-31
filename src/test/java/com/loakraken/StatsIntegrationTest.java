package com.loakraken;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.common.api.EfficiencyRuleVersions;
import com.loakraken.stats.application.StatsPort;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatsIntegrationTest {
  private static final String SERVER = "통계테스트서버";
  private static final String MAIN_CLASS = "통계바드";
  private static final String SMALL_CLASS = "통계소수직업";
  @LocalServerPort int port;
  @Autowired StringRedisTemplate redis;
  @Autowired JdbcClient jdbc;
  @Autowired StatsPort statsPort;
  private final JsonMapper mapper = new JsonMapper();
  private final Instant now = Instant.now();

  @BeforeEach
  @AfterEach
  void 정리() {
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    jdbc.sql("DELETE FROM characters WHERE server_name LIKE :s").param("s", SERVER + "%").update();
  }

  /**
   * MAIN_CLASS 35명: 아이템 레벨 1700~1734(1씩), 전투력 3000~3340(10씩), 짝수 SUPPORT·홀수 DEALER, 보석 8.00.
   * 1700·1701은 같은 원정대(1701이 대표). SMALL_CLASS 5명: 1650, 전투력 2000, 역할 미판정. 오래된 1명(40일 전)과 전투력 없는 1명은
   * 모집단 밖이다.
   */
  private void 표본() {
    var roster = UUID.randomUUID();
    for (int i = 0; i < 35; i++)
      insert(
          "통계캐" + i,
          MAIN_CLASS,
          new BigDecimal(1700 + i),
          new BigDecimal(3000 + 10 * i),
          i % 2 == 0 ? "SUPPORT" : "DEALER",
          new BigDecimal("8.00"),
          i <= 1 ? roster : null,
          now.minus(1, ChronoUnit.DAYS));
    for (int i = 0; i < 5; i++)
      insert(
          "통계소수" + i,
          SMALL_CLASS,
          new BigDecimal("1650"),
          new BigDecimal("2000"),
          null,
          null,
          null,
          now.minus(2, ChronoUnit.DAYS));
    insert(
        "통계오래된캐",
        MAIN_CLASS,
        new BigDecimal("1800"),
        new BigDecimal("9000"),
        "DEALER",
        null,
        null,
        now.minus(40, ChronoUnit.DAYS));
    insert(
        "통계발견만",
        MAIN_CLASS,
        new BigDecimal("1790"),
        null,
        null,
        null,
        roster,
        now.minus(1, ChronoUnit.DAYS));
  }

  @Test
  void 요약_직업_서버_분포는_최근_확인한_관측_캐릭터로_집계하고_표본이_작으면_분위수만_비운다() throws Exception {
    표본();
    var summary = data("/api/v1/stats/summary?server=" + encode(SERVER));
    assertThat(summary.at("/meta/population").asLong()).isEqualTo(40);
    assertThat(summary.at("/meta/sampleStatus").asText()).isEqualTo("READY");
    assertThat(summary.at("/meta/ruleVersion").asText()).isEqualTo("stats-v1");
    assertThat(summary.at("/meta/minSample").asInt()).isEqualTo(30);
    assertThat(summary.at("/meta/conditions/activeDays").asInt()).isEqualTo(30);
    assertThat(summary.at("/meta/conditions/itemLevelMin").decimalValue())
        .isEqualByComparingTo("1640");
    assertThat(summary.at("/meta/coverage/observedCharacters").asLong()).isGreaterThanOrEqualTo(41);
    assertThat(summary.path("dealers").asLong()).isEqualTo(17);
    assertThat(summary.path("supports").asLong()).isEqualTo(18);
    assertThat(summary.path("unknownRole").asLong()).isEqualTo(5);
    assertThat(summary.path("supportShare").decimalValue()).isEqualByComparingTo("51.43");
    assertThat(summary.path("gemMeanLevel").decimalValue()).isEqualByComparingTo("8.00");
    assertThat(summary.path("gemSample").asLong()).isEqualTo(35);
    // 아이템 레벨 중앙값: 1650×5 + 1700..1734 → 40개의 20·21번째 = 1714, 1715
    assertThat(summary.path("itemLevelMedian").decimalValue()).isEqualByComparingTo("1714.5");
    ApiContractFixture.write(
        "stats-summary.json", get("/api/v1/stats/summary?server=" + encode(SERVER)).body());

    var classes = data("/api/v1/stats/classes?server=" + encode(SERVER));
    assertThat(classes.at("/rows/0/className").asText()).isEqualTo(MAIN_CLASS);
    assertThat(classes.at("/rows/0/count").asLong()).isEqualTo(35);
    assertThat(classes.at("/rows/0/share").decimalValue()).isEqualByComparingTo("87.50");
    assertThat(classes.at("/rows/0/sampleStatus").asText()).isEqualTo("READY");
    assertThat(classes.at("/rows/0/combatPower/p50").decimalValue()).isEqualByComparingTo("3170");
    assertThat(classes.at("/rows/0/supportShare").isNull()).isTrue(); // 통계바드는 겸용 직업 목록에 없다.
    assertThat(classes.at("/rows/1/className").asText()).isEqualTo(SMALL_CLASS);
    assertThat(classes.at("/rows/1/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(classes.at("/rows/1/combatPower").isNull()).isTrue();
    // 30명 미만이어도 평균은 참고값으로 준다(#273). 분위수는 30명 기준 그대로 null.
    assertThat(classes.at("/rows/1/itemLevelMean").decimalValue()).isEqualByComparingTo("1650");
    ApiContractFixture.write(
        "stats-classes.json", get("/api/v1/stats/classes?server=" + encode(SERVER)).body());

    var servers = data("/api/v1/stats/servers?server=" + encode(SERVER));
    assertThat(servers.at("/rows/0/serverName").asText()).isEqualTo(SERVER);
    assertThat(servers.at("/rows/0/count").asLong()).isEqualTo(40);
    // 필터와 무관한 전체 캐릭터(오래된 캐릭터·발견만 된 캐릭터 포함)와 원정대 수
    assertThat(servers.at("/rows/0/knownCharacters").asLong()).isEqualTo(42);
    assertThat(servers.at("/rows/0/rosters").asLong()).isEqualTo(1);
    ApiContractFixture.write(
        "stats-servers.json", get("/api/v1/stats/servers?server=" + encode(SERVER)).body());

    var distribution =
        data(
            "/api/v1/stats/distribution?metric=ITEM_LEVEL&width=10&percentileOf=1730&server="
                + encode(SERVER));
    assertThat(distribution.path("width").decimalValue()).isEqualByComparingTo("10");
    assertThat(distribution.at("/bins/0/from").decimalValue()).isEqualByComparingTo("1650");
    assertThat(distribution.at("/bins/0/to").decimalValue()).isEqualByComparingTo("1660");
    assertThat(distribution.at("/bins/0/count").asLong()).isEqualTo(5);
    assertThat(distribution.at("/bins/1/from").decimalValue()).isEqualByComparingTo("1700");
    assertThat(distribution.at("/bins/1/count").asLong()).isEqualTo(10);
    // 1730보다 큰 캐릭터 4명(1731~1734) → 5위 / 40명 = 상위 12.5%
    assertThat(distribution.at("/percentileOf/rank").asLong()).isEqualTo(5);
    assertThat(distribution.at("/percentileOf/topPercent").decimalValue())
        .isEqualByComparingTo("12.5");
    assertThat(distribution.at("/percentileOf/tier").asText()).isEqualTo("TOP25");
    ApiContractFixture.write(
        "stats-distribution.json",
        get("/api/v1/stats/distribution?metric=ITEM_LEVEL&width=10&percentileOf=1730&server="
                + encode(SERVER))
            .body());

    // 대표 캐릭터만: 같은 원정대의 대표는 아이템 레벨이 가장 높은 1790(프로필 없음)이라 1700·1701 둘 다 빠지고 1790도 모집단 밖이다.
    var representative =
        data("/api/v1/stats/summary?representativeOnly=true&server=" + encode(SERVER));
    assertThat(representative.at("/meta/population").asLong()).isEqualTo(38);
    // 기간 90일이면 40일 전 캐릭터가 들어온다.
    assertThat(
            data("/api/v1/stats/summary?activeDays=90&server=" + encode(SERVER))
                .at("/meta/population")
                .asLong())
        .isEqualTo(41);
    // 역할·직업·레벨 필터
    var supportsOnly =
        data("/api/v1/stats/summary?role=SUPPORT&itemLevelMin=1710&server=" + encode(SERVER));
    assertThat(supportsOnly.at("/meta/population").asLong()).isEqualTo(13);
    assertThat(supportsOnly.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
    // 13명이라 분위수(p90)는 비우고 중앙값·평균은 참고값으로 준다(#273).
    assertThat(supportsOnly.path("itemLevelMedian").isNull()).isFalse();
    assertThat(supportsOnly.path("itemLevelMean").isNull()).isFalse();
    assertThat(supportsOnly.path("combatPowerP90").isNull()).isTrue();
  }

  /**
   * 구성 블록(B4): 겸용 직업(바드)은 판정한 역할대로, 아닌 직업(버서커)은 역할을 몰라도 딜러로 센다. 네 값의 합은 표본이고, 겸용 직업 행의 딜러·서포터·미확인과
   * 같다.
   */
  @Test
  void 구성_블록은_겸용_직업을_역할로_나누고_합이_표본이다() throws Exception {
    var at = now.minus(1, ChronoUnit.DAYS);
    String[] bard = {"SUPPORT", "SUPPORT", "SUPPORT", "DEALER", "DEALER", null};
    for (int i = 0; i < bard.length; i++)
      insert(
          "구성바드" + i,
          "바드",
          new BigDecimal("1700"),
          new BigDecimal("3000"),
          bard[i],
          null,
          null,
          at);
    String[] berserker = {"DEALER", "DEALER", "DEALER", "DEALER", null};
    for (int i = 0; i < berserker.length; i++)
      insert(
          "구성버서커" + i,
          "버서커",
          new BigDecimal("1700"),
          new BigDecimal("3000"),
          berserker[i],
          null,
          null,
          at);
    var classes = data("/api/v1/stats/classes?server=" + encode(SERVER));
    var composition = classes.path("composition");
    assertThat(composition.path("dealer").asLong()).isEqualTo(5);
    assertThat(composition.path("supportClassDealer").asLong()).isEqualTo(2);
    assertThat(composition.path("support").asLong()).isEqualTo(3);
    assertThat(composition.path("unknownRole").asLong()).isEqualTo(1);
    long sum = 0;
    for (var field : java.util.List.of("dealer", "supportClassDealer", "support", "unknownRole"))
      sum += composition.path(field).asLong();
    assertThat(sum).isEqualTo(classes.at("/meta/population").asLong()).isEqualTo(11);
    // 겸용 직업 행의 역할 수와 같다(서포터 직업의 딜러·서포터 선택).
    for (var row : classes.path("rows"))
      if (row.path("className").asText().equals("바드")) {
        assertThat(row.path("dealers").asLong()).isEqualTo(2);
        assertThat(row.path("supports").asLong()).isEqualTo(3);
        assertThat(row.path("unknownRole").asLong()).isEqualTo(1);
      }
    ApiContractFixture.write(
        "stats-classes-composition.json",
        get("/api/v1/stats/classes?server=" + encode(SERVER)).body());
  }

  /** 보석 레벨 분포(B2): 5 미만·0.5 단위·10.0 구간, 빈 구간은 0, 합은 보석을 관측한 표본(모집단보다 작음). */
  @Test
  void 보석_레벨_분포는_구간_합이_보석_표본이고_빈_구간도_준다() throws Exception {
    var at = now.minus(1, ChronoUnit.DAYS);
    String[] gems = {null, "4.50", "5.00", "5.25", "7.50", "9.99", "10.00"};
    for (int i = 0; i < gems.length; i++)
      insert(
          "보석분포" + i,
          "버서커",
          new BigDecimal("1700"),
          new BigDecimal("3000"),
          "DEALER",
          gems[i] == null ? null : new BigDecimal(gems[i]),
          null,
          at);
    var data = data("/api/v1/stats/gem-levels?server=" + encode(SERVER));
    assertThat(data.at("/meta/population").asLong()).isEqualTo(7);
    assertThat(data.path("gemSample").asLong()).isEqualTo(6);
    var bins = data.path("bins");
    assertThat(bins.size()).isEqualTo(12); // 5 미만 + 5.0~9.5(10개) + 10.0
    assertThat(bins.get(0).path("from").isNull()).isTrue();
    assertThat(bins.get(0).path("to").decimalValue()).isEqualByComparingTo("5.0");
    assertThat(bins.get(0).path("count").asLong()).isEqualTo(1); // 4.50
    assertThat(bins.get(1).path("from").decimalValue()).isEqualByComparingTo("5.0");
    assertThat(bins.get(1).path("to").decimalValue()).isEqualByComparingTo("5.5");
    assertThat(bins.get(1).path("count").asLong()).isEqualTo(2); // 5.00, 5.25
    assertThat(bins.get(6).path("from").decimalValue()).isEqualByComparingTo("7.5");
    assertThat(bins.get(6).path("count").asLong()).isEqualTo(1);
    assertThat(bins.get(10).path("from").decimalValue()).isEqualByComparingTo("9.5");
    assertThat(bins.get(10).path("count").asLong()).isEqualTo(1); // 9.99
    assertThat(bins.get(11).path("from").decimalValue()).isEqualByComparingTo("10.0");
    assertThat(bins.get(11).path("to").isNull()).isTrue();
    assertThat(bins.get(11).path("count").asLong()).isEqualTo(1);
    long sum = 0;
    for (var bin : bins) sum += bin.path("count").asLong();
    assertThat(sum).isEqualTo(data.path("gemSample").asLong());
    assertThat(bins.get(3).path("count").asLong()).isZero(); // 6.0 빈 구간
    ApiContractFixture.write(
        "stats-gem-levels.json", get("/api/v1/stats/gem-levels?server=" + encode(SERVER)).body());
  }

  /** 레벨별 전투력(B3): 아이템 레벨 5 단위 구간, 30명 이상 구간만 중앙값. 같은 표본(그 구간만 거른 요약)의 전투력 중앙값과 같다. */
  @Test
  void 레벨별_전투력은_5레벨_구간마다_30명_이상만_중앙값이고_요약_중앙값과_같다() throws Exception {
    var at = now.minus(1, ChronoUnit.DAYS);
    for (int i = 0; i < 31; i++)
      insert(
          "레벨전투" + i,
          "버서커",
          new BigDecimal("1721.5"),
          new BigDecimal(3000 + 10 * i),
          "DEALER",
          null,
          null,
          at);
    for (int i = 0; i < 2; i++)
      insert(
          "레벨전투작은" + i,
          "버서커",
          new BigDecimal("1652"),
          new BigDecimal("2000"),
          "DEALER",
          null,
          null,
          at);
    var data = data("/api/v1/stats/level-combat-power?server=" + encode(SERVER));
    var buckets = data.path("buckets");
    assertThat(buckets.size()).isEqualTo(2);
    assertThat(buckets.get(0).path("from").decimalValue()).isEqualByComparingTo("1650");
    assertThat(buckets.get(0).path("to").decimalValue()).isEqualByComparingTo("1655");
    assertThat(buckets.get(0).path("count").asLong()).isEqualTo(2);
    assertThat(buckets.get(0).path("median").isNull()).isTrue(); // 30명 미만
    assertThat(buckets.get(1).path("from").decimalValue()).isEqualByComparingTo("1720");
    assertThat(buckets.get(1).path("count").asLong()).isEqualTo(31);
    // 3000..3300(10씩) 31개의 16번째 = 3150
    assertThat(buckets.get(1).path("median").decimalValue()).isEqualByComparingTo("3150");
    assertThat(data.at("/meta/population").asLong()).isEqualTo(33);
    // 같은 표본(1720~1724.99)의 요약 중앙값과 같다.
    var summary =
        data(
            "/api/v1/stats/summary?server="
                + encode(SERVER)
                + "&itemLevelMin=1720&itemLevelMax=1724.99");
    assertThat(buckets.get(1).path("median").decimalValue())
        .isEqualByComparingTo(summary.path("combatPowerMedian").decimalValue());
    ApiContractFixture.write(
        "stats-level-combat-power.json",
        get("/api/v1/stats/level-combat-power?server=" + encode(SERVER)).body());
  }

  @Test
  void 분포의_상위_N퍼센트_기준값은_내_위치와_맞고_서버_행은_직업_행과_같은_값을_준다() throws Exception {
    표본();
    // 모집단 40명(전투력 3000~3340 10씩 35명 + 2000 5명). K = ⌊N×40/100⌋: 1%는 0이라 빠지고 5·10·25·50·75% =
    // 2·4·10·20·30위.
    var distribution =
        data("/api/v1/stats/distribution?metric=COMBAT_POWER&server=" + encode(SERVER));
    var cutoffs = distribution.path("topPercentCutoffs");
    assertThat(cutoffs.size()).isEqualTo(5);
    String[][] expected = {
      {"5", "2", "3330"},
      {"10", "4", "3310"},
      {"25", "10", "3250"},
      {"50", "20", "3150"},
      {"75", "30", "3050"}
    };
    for (int i = 0; i < expected.length; i++) {
      var cutoff = cutoffs.get(i);
      assertThat(cutoff.path("topPercent").asInt()).isEqualTo(Integer.parseInt(expected[i][0]));
      assertThat(cutoff.path("rank").asLong()).isEqualTo(Long.parseLong(expected[i][1]));
      assertThat(cutoff.path("value").decimalValue()).isEqualByComparingTo(expected[i][2]);
      // 기준값의 '내 위치'는 그 N% 이내다(같은 순위 규칙).
      var standing =
          data(
              "/api/v1/stats/distribution?metric=COMBAT_POWER&percentileOf="
                  + expected[i][2]
                  + "&server="
                  + encode(SERVER));
      assertThat(standing.at("/percentileOf/topPercent").decimalValue())
          .isLessThanOrEqualTo(new BigDecimal(expected[i][0]));
      assertThat(standing.path("topPercentCutoffs").size()).isEqualTo(5);
    }
    // 아이템 레벨 분포와 작은 표본에는 주지 않는다.
    assertThat(
            data("/api/v1/stats/distribution?metric=ITEM_LEVEL&server=" + encode(SERVER))
                .path("topPercentCutoffs")
                .isNull())
        .isTrue();
    assertThat(
            data("/api/v1/stats/distribution?metric=COMBAT_POWER&role=SUPPORT&itemLevelMin=1710&server="
                    + encode(SERVER))
                .path("topPercentCutoffs")
                .isNull())
        .isTrue();

    // 서버 행: 직업 행과 같은 역할 수·평균 레벨·전투력 분위수.
    var server = data("/api/v1/stats/servers?server=" + encode(SERVER)).at("/rows/0");
    assertThat(server.path("dealers").asLong()).isEqualTo(17);
    assertThat(server.path("supports").asLong()).isEqualTo(18);
    assertThat(server.path("unknownRole").asLong()).isEqualTo(5);
    // (1650×5 + 1700..1734 합) / 40 = 1708.625
    assertThat(server.path("itemLevelMean").decimalValue()).isEqualByComparingTo("1708.63");
    assertThat(server.at("/combatPower/p50").decimalValue())
        .isEqualByComparingTo(server.path("combatPowerMedian").decimalValue());
    assertThat(server.at("/combatPower/p90").isNull()).isFalse();
  }

  @Test
  void 상위_N퍼센트_기준값은_표본_30부터이고_동률이_경계에_걸쳐도_내_위치와_맞으며_서버_행은_빈_역할과_작은_서버도_맞다() throws Exception {
    // 30명 중 29명: 전투력 1위 6000, 2~4위 동률 5000, 5위부터 4000 - 10×i(서로 다름). 역할은 모두 미판정, 아이템 레벨 1700 + i.
    for (int i = 0; i < 29; i++) 경계캐(i);
    var small = data("/api/v1/stats/distribution?metric=COMBAT_POWER&server=" + encode(SERVER));
    assertThat(small.path("topPercentCutoffs").isNull()).isTrue();
    var smallRow = data("/api/v1/stats/servers?server=" + encode(SERVER)).at("/rows/0");
    assertThat(smallRow.path("sampleStatus").asText()).isNotEqualTo("READY");
    assertThat(smallRow.path("combatPower").isNull()).isTrue();
    assertThat(smallRow.path("itemLevelMean").decimalValue()).isEqualByComparingTo("1714");

    // 30번째가 들어오면(표본 READY) 기준값이 생긴다. 캐시는 비운다.
    경계캐(29);
    var keys = redis.keys("stats:v1:*");
    if (keys != null && !keys.isEmpty()) redis.delete(keys);
    var cutoffs =
        data("/api/v1/stats/distribution?metric=COMBAT_POWER&server=" + encode(SERVER))
            .path("topPercentCutoffs");
    // K = ⌊N×30/100⌋: 1%는 0이라 빠지고 5·10·25·50·75% = 1·3·7·15·22위. 10%의 3위는 2~4위 동률 안이다.
    String[][] expected = {
      {"5", "1", "6000"},
      {"10", "3", "5000"},
      {"25", "7", "3940"},
      {"50", "15", "3860"},
      {"75", "22", "3790"}
    };
    assertThat(cutoffs.size()).isEqualTo(expected.length);
    for (int i = 0; i < expected.length; i++) {
      assertThat(cutoffs.get(i).path("topPercent").asInt())
          .isEqualTo(Integer.parseInt(expected[i][0]));
      assertThat(cutoffs.get(i).path("rank").asLong()).isEqualTo(Long.parseLong(expected[i][1]));
      assertThat(cutoffs.get(i).path("value").decimalValue()).isEqualByComparingTo(expected[i][2]);
    }
    // 동률 값 5000은 큰 값이 하나라 2위(6.67%)로 10% 이내, 바로 아래 4999.99는 큰 값이 넷이라 5위(16.67%)로 10% 밖이다.
    assertThat(percentileOf("5000")).isLessThanOrEqualTo(new BigDecimal("10"));
    assertThat(percentileOf("4999.99")).isGreaterThan(new BigDecimal("10"));

    // 서버 행: 역할이 모두 미판정이면 딜러·서포터 0, 미판정 30. 분위수는 READY라 있다.
    var row = data("/api/v1/stats/servers?server=" + encode(SERVER)).at("/rows/0");
    assertThat(row.path("sampleStatus").asText()).isEqualTo("READY");
    assertThat(row.path("dealers").asLong()).isZero();
    assertThat(row.path("supports").asLong()).isZero();
    assertThat(row.path("unknownRole").asLong()).isEqualTo(30);
    assertThat(row.path("itemLevelMean").decimalValue()).isEqualByComparingTo("1714.5");
    assertThat(row.at("/combatPower/p50").isNull()).isFalse();

    // 한 명뿐인 서버: 평균·역할 수는 주고 분위수는 null.
    String single = SERVER + "한명";
    insert(
        single,
        "통계한명캐",
        MAIN_CLASS,
        new BigDecimal("1690"),
        new BigDecimal("3000"),
        "DEALER",
        null,
        null,
        now.minus(1, ChronoUnit.DAYS));
    var singleRows = data("/api/v1/stats/servers?server=" + encode(single)).path("rows");
    assertThat(singleRows.size()).isEqualTo(1);
    assertThat(singleRows.at("/0/dealers").asLong()).isEqualTo(1);
    assertThat(singleRows.at("/0/supports").asLong()).isZero();
    assertThat(singleRows.at("/0/unknownRole").asLong()).isZero();
    assertThat(singleRows.at("/0/itemLevelMean").decimalValue()).isEqualByComparingTo("1690");
    assertThat(singleRows.at("/0/combatPower").isNull()).isTrue();
    assertThat(singleRows.at("/0/sampleStatus").asText()).isNotEqualTo("READY");

    // 모집단 밖(40일 전) 캐릭터만 있는 서버도 아는 캐릭터 수를 보여 주는 빈 행이 있다: 표본 0, 역할 수 0, 평균·분위수 null.
    String stale = SERVER + "오래됨";
    insert(
        stale,
        "통계오래됨캐",
        MAIN_CLASS,
        new BigDecimal("1690"),
        new BigDecimal("3000"),
        "DEALER",
        null,
        null,
        now.minus(40, ChronoUnit.DAYS));
    var staleRows = data("/api/v1/stats/servers?server=" + encode(stale)).path("rows");
    assertThat(staleRows.size()).isEqualTo(1);
    var empty = staleRows.get(0);
    assertThat(empty.path("count").asLong()).isZero();
    assertThat(empty.path("knownCharacters").asLong()).isEqualTo(1);
    assertThat(empty.path("share").isNull()).isTrue();
    assertThat(empty.path("dealers").asLong()).isZero();
    assertThat(empty.path("supports").asLong()).isZero();
    assertThat(empty.path("unknownRole").asLong()).isZero();
    assertThat(empty.path("itemLevelMean").isNull()).isTrue();
    assertThat(empty.path("combatPower").isNull()).isTrue();
    assertThat(empty.path("sampleStatus").asText()).isNotEqualTo("READY");
  }

  private void 경계캐(int i) {
    var combatPower = i == 0 ? 6000 : i <= 3 ? 5000 : 4000 - 10 * i;
    insert(
        "통계경계캐" + i,
        MAIN_CLASS,
        new BigDecimal(1700 + i),
        new BigDecimal(combatPower),
        null,
        null,
        null,
        now.minus(1, ChronoUnit.DAYS));
  }

  private BigDecimal percentileOf(String value) throws Exception {
    return data("/api/v1/stats/distribution?metric=COMBAT_POWER&percentileOf="
            + value
            + "&server="
            + encode(SERVER))
        .at("/percentileOf/topPercent")
        .decimalValue();
  }

  @Test
  void 처리정지_목록이_바뀌면_집계_캐시를_비우지_않아도_바로_새로_세고_대표가_목록에_들면_다음_캐릭터가_대표다() throws Exception {
    표본();
    var all = "/api/v1/stats/summary?server=" + encode(SERVER);
    var representative = "/api/v1/stats/summary?representativeOnly=true&server=" + encode(SERVER);
    assertThat(data(all).at("/meta/population").asLong()).isEqualTo(40);
    // 원정대(1700·1701·1790) 대표는 1790(전투력 없어 모집단 밖)이라 1700·1701이 둘 다 빠진다.
    assertThat(data(representative).at("/meta/population").asLong()).isEqualTo(38);
    try {
      // 캐시는 그대로 두고 목록만 바꾼다.
      jdbc.sql(
              "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('통계캐5', :s, 'PRIVACY_STOP')")
          .param("s", SERVER)
          .update();
      assertThat(data(all).at("/meta/population").asLong()).isEqualTo(39);

      // 원정대 대표(1790)가 목록에 들면 다음으로 높은 1701이 대표가 된다: 1700과 목록의 통계캐5만 빠져 40 − 2 = 38
      // (고치기 전이면 1701도 빠져 37).
      jdbc.sql(
              "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('통계발견만', :s, 'PRIVACY_STOP')")
          .param("s", SERVER)
          .update();
      assertThat(data(representative).at("/meta/population").asLong()).isEqualTo(38);
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE server_name = :s").param("s", SERVER).update();
    }
    // 목록에서 빠지면 다시 처음 값(캐시 키가 다시 바뀜).
    assertThat(data(all).at("/meta/population").asLong()).isEqualTo(40);
  }

  @Test
  void 랭킹과_캐릭터_순위는_동률을_같은_순위로_주고_상위_퍼센트로_티어를_정한다() throws Exception {
    표본();
    var ranking = data("/api/v1/rankings?metric=COMBAT_POWER&size=3&server=" + encode(SERVER));
    assertThat(ranking.at("/meta/population").asLong()).isEqualTo(40);
    assertThat(ranking.path("metric").asText()).isEqualTo("COMBAT_POWER");
    assertThat(ranking.at("/rows").size()).isEqualTo(3);
    assertThat(ranking.at("/rows/0/characterName").asText()).isEqualTo("통계캐34");
    assertThat(ranking.at("/rows/0/rank").asLong()).isEqualTo(1);
    assertThat(ranking.at("/rows/0/topPercent").decimalValue()).isEqualByComparingTo("2.5");
    assertThat(ranking.at("/rows/0/tier").asText()).isEqualTo("TOP5");
    ApiContractFixture.write(
        "rankings.json",
        get("/api/v1/rankings?metric=COMBAT_POWER&size=3&server=" + encode(SERVER)).body());

    // 소수 직업 5명은 전투력이 모두 같아 36위 동률이다.
    var tail = data("/api/v1/rankings?metric=COMBAT_POWER&size=10&page=4&server=" + encode(SERVER));
    assertThat(tail.at("/rows/0/rank").asLong()).isEqualTo(31);
    assertThat(tail.at("/rows/5/rank").asLong()).isEqualTo(36);
    assertThat(tail.at("/rows/9/rank").asLong()).isEqualTo(36);
    assertThat(tail.at("/rows/9/tier").asText()).isEqualTo("REST");

    var rank = data("/api/v1/characters/rank?characterName=" + encode("통계캐30"));
    assertThat(rank.path("serverName").asText()).isEqualTo(SERVER);
    assertThat(rank.path("included").asBoolean()).isTrue();
    // 전투력·아이템 레벨 × 전체·서버·직업·서버 내 직업(#170)
    assertThat(rank.path("entries").size()).isEqualTo(8);
    JsonNode serverCombat = null;
    for (var entry : rank.path("entries"))
      if (entry.path("metric").asText().equals("COMBAT_POWER")
          && entry.path("scope").asText().equals("SERVER")) serverCombat = entry;
    assertThat(serverCombat).isNotNull();
    assertThat(serverCombat.path("scopeValue").asText()).isEqualTo(SERVER);
    assertThat(serverCombat.path("rank").asLong()).isEqualTo(5);
    assertThat(serverCombat.path("total").asLong()).isEqualTo(40);
    assertThat(serverCombat.path("topPercent").decimalValue()).isEqualByComparingTo("12.5");
    assertThat(serverCombat.path("tier").asText()).isEqualTo("TOP25");
    var serverClass = entry(rank, "COMBAT_POWER", "SERVER_CLASS");
    assertThat(serverClass.path("scopeValue").asText())
        .isEqualTo(SERVER + "/" + rank.path("className").asText());
    assertThat(serverClass.path("total").asLong())
        .isBetween(1L, serverCombat.path("total").asLong());
    assertThat(serverClass.path("rank").asLong()).isBetween(1L, serverCombat.path("rank").asLong());
    ApiContractFixture.write(
        "character-rank.json",
        get("/api/v1/characters/rank?characterName=" + encode("통계캐30")).body());

    // 기간 밖 캐릭터는 순위 없이 included=false
    var old = data("/api/v1/characters/rank?characterName=" + encode("통계오래된캐"));
    assertThat(old.path("included").asBoolean()).isFalse();
    assertThat(old.path("entries").size()).isZero();
  }

  @Test
  void 공개_제외_목록은_순위와_집계_모집단_모두에서_빠지고_본인은_순위_비공개다() throws Exception {
    표본();
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('통계캐34', :s, 'REQUEST')")
        .param("s", SERVER)
        .update();
    try {
      var ranking = data("/api/v1/rankings?metric=COMBAT_POWER&size=3&server=" + encode(SERVER));
      assertThat(ranking.at("/meta/population").asLong()).isEqualTo(39);
      assertThat(ranking.at("/rows/0/characterName").asText()).isEqualTo("통계캐33");
      assertThat(ranking.at("/rows/0/rank").asLong()).isEqualTo(1);
      // 목록 1위는 그 캐릭터의 본인 순위에서도 1위다(같은 모집단).
      var next = data("/api/v1/characters/rank?characterName=" + encode("통계캐33"));
      assertThat(next.path("rankVisibility").asText()).isEqualTo("VISIBLE");
      var serverCombat = entry(next, "COMBAT_POWER", "SERVER");
      assertThat(serverCombat.path("rank").asLong()).isEqualTo(1);
      assertThat(serverCombat.path("total").asLong()).isEqualTo(39);
      // 처리정지 목록의 본인은 관측이 없는 캐릭터와 같은 404다(#363). 순위·백분위·티어도 값도 없다.
      var hidden = get("/api/v1/characters/rank?characterName=" + encode("통계캐34"));
      var unknown = get("/api/v1/characters/rank?characterName=" + encode("통계없는이름"));
      assertThat(hidden.statusCode()).isEqualTo(404).isEqualTo(unknown.statusCode());
      assertThat(mapper.readTree(hidden.body()).path("code").asText())
          .isEqualTo(mapper.readTree(unknown.body()).path("code").asText());
      assertThat(hidden.body()).doesNotContain("3340", "rankVisibility");
      // 제외된 캐릭터의 값을 percentileOf에 넣어도 제외 반영 모집단 기준이고, 그 캐릭터를 특정할 정보가 없다.
      var percentile =
          get(
              "/api/v1/stats/distribution?metric=COMBAT_POWER&percentileOf=3340&server="
                  + encode(SERVER));
      var of = mapper.readTree(percentile.body()).at("/data/percentileOf");
      assertThat(of.path("rank").asLong()).isEqualTo(1);
      assertThat(of.path("total").asLong()).isEqualTo(39);
      assertThat(percentile.body()).doesNotContain("통계캐34");
      // 집계(요약·직업·서버·분포)에서도 빠진다(B5). 모든 통계가 같은 모집단이라 응답 사이 차이로 처리정지 수를 셀 수 없다.
      var summary = data("/api/v1/stats/summary?server=" + encode(SERVER));
      assertThat(summary.at("/meta/population").asLong()).isEqualTo(39);
      assertThat(summary.path("supports").asLong()).isEqualTo(17);
      var mainClass = data("/api/v1/stats/classes?server=" + encode(SERVER)).path("rows").get(0);
      assertThat(mainClass.path("className").asText()).isEqualTo(MAIN_CLASS);
      assertThat(mainClass.path("count").asLong()).isEqualTo(34);
      var server = data("/api/v1/stats/servers?server=" + encode(SERVER)).path("rows").get(0);
      assertThat(server.path("count").asLong()).isEqualTo(39);
      // 알고 있는 캐릭터 수(필터 무관): 표본 42명 중 처리정지 1명을 뺀 41명.
      assertThat(server.path("knownCharacters").asLong()).isEqualTo(41);
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE character_name = '통계캐34'").update();
    }
  }

  @Test
  void 팔찌_젬_효율_랭킹은_지금_규칙_버전_값만_높은_순으로_세고_처리정지는_뺀다() throws Exception {
    // L38·L39: 저장된 효율 중 지금 규칙 버전(공통 상수) 값만 센다.
    표본();
    var t = Instant.now();
    efficiency("통계캐1", "10.00", "sim-old", null, null, t.minus(Duration.ofHours(2)));
    efficiency(
        "통계캐2",
        "14.42",
        com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM,
        "4.27",
        com.loakraken.common.api.EfficiencyRuleVersions.ARK_GRID,
        t.minus(Duration.ofHours(1)));
    efficiency(
        "통계캐3",
        "12.00",
        com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM,
        "6.10",
        com.loakraken.common.api.EfficiencyRuleVersions.ARK_GRID,
        t.minus(Duration.ofMinutes(50)));
    efficiency(
        "통계캐5",
        "12.00",
        com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM,
        null,
        null,
        t.minus(Duration.ofMinutes(40)));
    efficiency(
        "통계캐34",
        "20.00",
        com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM,
        null,
        null,
        t.minus(Duration.ofMinutes(30)));
    var bracelets = data("/api/v1/rankings/bracelets?size=10&server=" + encode(SERVER));
    assertThat(bracelets.path("kind").asText()).isEqualTo("BRACELET");
    assertThat(bracelets.path("ruleVersion").asText())
        .isEqualTo(com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM);
    assertThat(bracelets.path("populationNote").asText()).isNotBlank();
    // 옛 버전(통계캐1)은 빠지고, 동률(12.00)은 같은 순위다.
    assertThat(bracelets.at("/meta/population").asLong()).isEqualTo(4);
    assertThat(bracelets.at("/rows/0/characterName").asText()).isEqualTo("통계캐34");
    assertThat(bracelets.at("/rows/0/valuePercent").decimalValue()).isEqualByComparingTo("20.00");
    assertThat(bracelets.at("/rows/1/characterName").asText()).isEqualTo("통계캐2");
    assertThat(bracelets.at("/rows/2/rank").asLong()).isEqualTo(3);
    assertThat(bracelets.at("/rows/3/rank").asLong()).isEqualTo(3);
    assertThat(bracelets.at("/rows/0/observedAt").asText()).isNotBlank();
    ApiContractFixture.write(
        "rankings-bracelets.json",
        get("/api/v1/rankings/bracelets?size=10&server=" + encode(SERVER)).body());

    var gems = data("/api/v1/rankings/ark-grid-gems?size=10&server=" + encode(SERVER));
    assertThat(gems.path("kind").asText()).isEqualTo("ARK_GRID_GEM");
    assertThat(gems.path("ruleVersion").asText())
        .isEqualTo(com.loakraken.common.api.EfficiencyRuleVersions.ARK_GRID);
    assertThat(gems.at("/rows").size()).isEqualTo(2);
    assertThat(gems.at("/rows/0/characterName").asText()).isEqualTo("통계캐3");
    assertThat(gems.at("/rows/1/valuePercent").decimalValue()).isEqualByComparingTo("4.27");

    // B5: 처리정지 목록 캐릭터는 빠진다.
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('통계캐34', :s, 'REQUEST')")
        .param("s", SERVER)
        .update();
    try {
      var keys = redis.keys("stats:v1:*");
      if (keys != null && !keys.isEmpty()) redis.delete(keys);
      var excluded = data("/api/v1/rankings/bracelets?size=10&server=" + encode(SERVER));
      assertThat(excluded.at("/meta/population").asLong()).isEqualTo(3);
      assertThat(excluded.at("/rows/0/characterName").asText()).isEqualTo("통계캐2");
      assertThat(excluded.toString()).doesNotContain("통계캐34");
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE character_name = '통계캐34'").update();
    }
  }

  @Test
  void 전투력_랭킹_행은_관측한_최고_전투력과_마지막_장비의_아크_패시브를_준다() throws Exception {
    // L37·K32: 최고 전투력은 characters.best_combat_power, 아크 패시브는 마지막 장비 요약(포인트·직업 각인).
    표본();
    jdbc.sql(
            "UPDATE characters SET best_combat_power = 3500 WHERE character_name = '통계캐34' AND server_name = :s")
        .param("s", SERVER)
        .update();
    var id =
        jdbc.sql(
                "SELECT character_id FROM characters WHERE character_name = '통계캐34' AND server_name = :s")
            .param("s", SERVER)
            .query(UUID.class)
            .single();
    for (var row : new Object[][] {{"버스트", 120, 90, 60, 2, 8, 2}, {"버스트", 140, 101, 70, 1, 10, 3}})
      jdbc.sql(
              """
          INSERT INTO character_equipment_summary (character_id, observed_at, last_confirmed_at,
            schema_version, combat_power, item_level, class_name, ark_passive_title, features, features_hash)
          VALUES (:id, :at, :at, 'equipment-summary-v1', 3330, 1734, '버서커', :title, CAST(:features AS JSONB), :hash)
          """)
          .param("id", id)
          .param("at", now.minus((int) row[4], ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
          .param("title", row[0])
          .param(
              "features",
              "{\"arkPassive\":{\"points\":{\"evolution\":"
                  + row[1]
                  + ",\"enlightenment\":"
                  + row[2]
                  + ",\"leap\":"
                  + row[3]
                  + "}},\"weapon\":{\"esther\":{\"refineLevel\":"
                  + row[5]
                  + ",\"ellaStage\":"
                  + row[6]
                  + "}}}")
          .param("hash", "h".repeat(63) + row[4])
          .update();
    var ranking = data("/api/v1/rankings?metric=COMBAT_POWER&size=2&server=" + encode(SERVER));
    var top = ranking.at("/rows/0");
    assertThat(top.path("characterName").asText()).isEqualTo("통계캐34");
    assertThat(top.path("bestCombatPower").decimalValue()).isEqualByComparingTo("3500");
    // 마지막(1시간 전) 장비 요약 값이다.
    assertThat(top.at("/arkPassivePoints/evolution").asInt()).isEqualTo(140);
    assertThat(top.at("/arkPassivePoints/enlightenment").asInt()).isEqualTo(101);
    assertThat(top.at("/arkPassivePoints/leap").asInt()).isEqualTo(70);
    assertThat(top.path("classEngraving").asText()).isEqualTo("버스트");
    assertThat(top.at("/estherWeapon/refineLevel").asInt()).isEqualTo(10);
    assertThat(top.at("/estherWeapon/ellaStage").asInt()).isEqualTo(3);
    // 장비 요약·최고 전투력이 없는 캐릭터는 null이다.
    var second = ranking.at("/rows/1");
    assertThat(second.path("bestCombatPower").isNull()).isTrue();
    assertThat(second.path("arkPassivePoints").isNull()).isTrue();
    assertThat(second.path("classEngraving").isNull()).isTrue();
    assertThat(second.path("estherWeapon").isNull()).isTrue();
    // 칭호가 빈 문자열인 요약은 직업 각인 null, 포인트는 그대로다.
    var secondId =
        jdbc.sql(
                "SELECT character_id FROM characters WHERE character_name = :n AND server_name = :s")
            .param("n", second.path("characterName").asText())
            .param("s", SERVER)
            .query(UUID.class)
            .single();
    jdbc.sql(
            """
        INSERT INTO character_equipment_summary (character_id, observed_at, last_confirmed_at,
          schema_version, combat_power, item_level, class_name, ark_passive_title, features, features_hash)
        VALUES (:id, :at, :at, 'equipment-summary-v1', 3000, 1720, '버서커', '',
          CAST('{"arkPassive":{"points":{"evolution":120}}}' AS JSONB), :hash)
        """)
        .param("id", secondId)
        .param("at", now.minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
        .param("hash", "e".repeat(64))
        .update();
    var again =
        // 캐시 키가 다르도록 size를 바꿔 다시 읽는다.
        data("/api/v1/rankings?metric=COMBAT_POWER&size=3&server=" + encode(SERVER)).at("/rows/1");
    assertThat(again.at("/arkPassivePoints/evolution").asInt()).isEqualTo(120);
    assertThat(again.path("classEngraving").isNull()).isTrue();
  }

  @Test
  void 직업_각인_필터는_마지막_장비의_직업_각인으로_모집단을_좁히고_모르는_수를_준다() throws Exception {
    // #618: 블레이드 5명 — 마지막 요약 기준 버스트 2(한 명은 예전 요약이 잔재된 기운), 잔재된 기운 1, 요약 없음 1, 빈 칭호 1.
    var titles = new String[] {"버스트", "잔재된 기운", "버스트", null, ""};
    for (int i = 0; i < titles.length; i++) {
      insert(
          "각인캐" + i,
          "블레이드",
          new BigDecimal(1720 - i),
          new BigDecimal(5000 - 1000 * i),
          "DEALER",
          null,
          null,
          now.minus(1, ChronoUnit.DAYS));
      if (titles[i] == null) continue;
      var id =
          jdbc.sql(
                  "SELECT character_id FROM characters WHERE character_name = :n AND server_name = :s")
              .param("n", "각인캐" + i)
              .param("s", SERVER)
              .query(UUID.class)
              .single();
      summary(id, i == 0 ? "잔재된 기운" : titles[i], 5, "a" + i);
      if (i == 0) summary(id, titles[i], 1, "b" + i);
    }
    var base = "/api/v1/rankings?className=" + encode("블레이드") + "&server=" + encode(SERVER);

    var all = data(base);
    assertThat(all.at("/meta/population").asLong()).isEqualTo(5);
    assertThat(all.path("classEngraving").isNull()).isTrue();
    assertThat(all.path("classEngravingUnknown").isNull()).isTrue();

    // 공백은 비교하지 않고, 응답은 목록의 표기다.
    var burst = data(base + "&classEngraving=" + encode("버 스트"));
    assertThat(burst.at("/meta/population").asLong()).isEqualTo(2);
    assertThat(burst.path("classEngraving").asText()).isEqualTo("버스트");
    assertThat(burst.path("classEngravingUnknown").asLong()).isEqualTo(2);
    assertThat(burst.at("/rows").size()).isEqualTo(2);
    assertThat(burst.at("/rows/0/characterName").asText()).isEqualTo("각인캐0");
    assertThat(burst.at("/rows/0/rank").asLong()).isEqualTo(1);
    assertThat(burst.at("/rows/1/characterName").asText()).isEqualTo("각인캐2");
    assertThat(burst.at("/rows/1/rank").asLong()).isEqualTo(2);
    assertThat(burst.at("/rows/1/classEngraving").asText()).isEqualTo("버스트");

    var choices =
        data(
            "/api/v1/rankings/class-engravings?className="
                + encode("블레이드")
                + "&server="
                + encode(SERVER));
    assertThat(choices.path("className").asText()).isEqualTo("블레이드");
    var counts = new java.util.HashMap<String, Long>();
    choices
        .path("classEngravings")
        .forEach(row -> counts.put(row.path("name").asText(), row.path("count").asLong()));
    assertThat(counts).containsEntry("버스트", 2L).containsEntry("잔재된 기운", 1L).hasSize(2);
    assertThat(choices.path("unknown").asLong()).isEqualTo(2);

    // 직업 없이, 다른 직업의 직업 각인, 모르는 직업은 기존 400 INVALID_REQUEST다.
    for (var path :
        new String[] {
          "/api/v1/rankings?classEngraving=" + encode("버스트"),
          base + "&classEngraving=" + encode("광기"),
          base + "&classEngraving=",
          "/api/v1/rankings?classEngraving=%20",
          "/api/v1/rankings/class-engravings",
          "/api/v1/rankings/class-engravings?className=" + encode(MAIN_CLASS)
        }) {
      var response = get(path);
      assertThat(response.statusCode()).as(path).isEqualTo(400);
      assertThat(mapper.readTree(response.body()).path("code").asText())
          .as(path)
          .isEqualTo("INVALID_REQUEST");
    }
  }

  private void summary(UUID id, String title, int hoursAgo, String hash) {
    jdbc.sql(
            """
        INSERT INTO character_equipment_summary (character_id, observed_at, last_confirmed_at,
          schema_version, combat_power, item_level, class_name, ark_passive_title, features, features_hash)
        VALUES (:id, :at, :at, 'equipment-summary-v1', 3000, 1720, '블레이드', :title,
          CAST('{}' AS JSONB), :hash)
        """)
        .param("id", id)
        .param("at", now.minus(hoursAgo, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
        .param("title", title)
        .param("hash", hash + "0".repeat(64 - hash.length()))
        .update();
  }

  @Test
  void 랭킹_행은_저장된_캐릭터_이미지_주소를_그대로_준다() throws Exception {
    표본();
    String url = "https://img.lostark.co.kr/armory/test/ranking.png";
    jdbc.sql(
            "UPDATE characters SET image_url = :url WHERE server_name = :s AND character_name = '통계캐34'")
        .param("url", url)
        .param("s", SERVER)
        .update();
    var ranking = data("/api/v1/rankings?size=2&server=" + encode(SERVER));
    assertThat(ranking.at("/rows/0/characterName").asText()).isEqualTo("통계캐34");
    assertThat(ranking.at("/rows/0/imageUrl").asText()).isEqualTo(url);
    // 받은 적이 없으면 null이다.
    assertThat(ranking.at("/rows/1/imageUrl").isNull()).isTrue();
  }

  @Test
  void 직업_각인별_통계는_직업_표와_같은_값을_직업_각인으로_나누고_모르는_캐릭터는_미확인_수로_준다() throws Exception {
    표본();
    // MAIN_CLASS 35명 중 3명은 버스트(1명은 옛 요약이 다른 칭호 → 마지막 요약 기준), 1명은 잔재된 기운, 나머지 31명은 요약 없음.
    var ids = new java.util.HashMap<String, UUID>();
    jdbc.sql("SELECT character_name, character_id FROM characters WHERE server_name = :s")
        .param("s", SERVER)
        .query(
            (java.sql.ResultSet row) -> {
              ids.put(row.getString(1), row.getObject(2, UUID.class));
            });
    summary(ids.get("통계캐1"), "버스트", 2, "a1");
    summary(ids.get("통계캐3"), "버스트", 2, "a3");
    summary(ids.get("통계캐5"), "잔재된 기운", 5, "a5");
    summary(ids.get("통계캐5"), "버스트", 1, "a6");
    summary(ids.get("통계캐7"), "잔재된 기운", 2, "a7");

    var table = data("/api/v1/stats/class-engravings?server=" + encode(SERVER));
    var classes = data("/api/v1/stats/classes?server=" + encode(SERVER));
    assertThat(table.at("/meta/population").asLong())
        .isEqualTo(classes.at("/meta/population").asLong());
    assertThat(table.at("/rows/0/classEngraving").asText()).isEqualTo("버스트");
    assertThat(table.at("/rows/0/stats/className").asText()).isEqualTo(MAIN_CLASS);
    assertThat(table.at("/rows/0/stats/count").asLong()).isEqualTo(3);
    // 1·3·5번은 홀수라 딜러(표본 규칙).
    assertThat(table.at("/rows/0/stats/dealers").asLong()).isEqualTo(3);
    assertThat(table.at("/rows/0/stats/sampleStatus").asText()).isEqualTo("COLLECTING");
    assertThat(table.at("/rows/0/stats/combatPower").isNull()).isTrue();
    assertThat(table.at("/rows/1/classEngraving").asText()).isEqualTo("잔재된 기운");
    assertThat(table.at("/rows/1/stats/count").asLong()).isEqualTo(1);
    var unknown = new java.util.HashMap<String, Long>();
    table
        .path("unknownByClass")
        .forEach(row -> unknown.put(row.path("className").asText(), row.path("count").asLong()));
    assertThat(unknown).containsEntry(MAIN_CLASS, 31L).containsEntry(SMALL_CLASS, 5L);
  }

  @Test
  void 저장된_효율이_없으면_빈_랭킹이다() throws Exception {
    표본();
    var empty = data("/api/v1/rankings/ark-grid-gems?server=" + encode(SERVER));
    assertThat(empty.path("ruleVersion").asText())
        .isEqualTo(com.loakraken.common.api.EfficiencyRuleVersions.ARK_GRID);
    assertThat(empty.at("/rows").size()).isZero();
  }

  @Test
  void 실_효율_열은_지금_규칙_버전의_딜러_값만_높은_순으로_세고_원본_자릿수를_지킨다() {
    // B3: 실 효율 열(설정이 켜지면 쓰는 기준). 홀수 = 딜러, 짝수 = 서포터.
    표본();
    var t = Instant.now();
    realEfficiency(
        "통계캐1", "17.88", EfficiencyRuleVersions.BRACELET_REAL, "11.21", t.minusSeconds(60));
    realEfficiency(
        "통계캐3", "11.3", EfficiencyRuleVersions.BRACELET_REAL, "9.07", t.minusSeconds(50));
    realEfficiency("통계캐5", "99.99", "bracelet-real-v0", null, t.minusSeconds(40));
    realEfficiency(
        "통계캐2",
        "18.107142857142858",
        EfficiencyRuleVersions.BRACELET_REAL,
        "30.00",
        t.minusSeconds(30));
    var conditions =
        new com.loakraken.stats.domain.StatsConditions(
            SERVER,
            null,
            null,
            com.loakraken.stats.domain.StatsRules.DEFAULT_ACTIVE_DAYS,
            null,
            null,
            null,
            null,
            false);
    var since = conditions.since(Instant.now());

    var bracelets =
        statsPort.efficiencyRanking(
            conditions, since, StatsPort.EfficiencyKind.BRACELET.columns(true), 0, 10);
    assertThat(bracelets.ruleVersion()).isEqualTo(EfficiencyRuleVersions.BRACELET_REAL);
    // 옛 버전(통계캐5)과 서포터(통계캐2)는 빠진다.
    assertThat(bracelets.total()).isEqualTo(2);
    assertThat(bracelets.rows().get(0).characterName()).isEqualTo("통계캐1");
    assertThat(bracelets.rows().get(0).value()).isEqualByComparingTo("17.88");
    assertThat(bracelets.rows().get(1).characterName()).isEqualTo("통계캐3");

    var gems =
        statsPort.efficiencyRanking(
            conditions, since, StatsPort.EfficiencyKind.ARK_GRID_GEM.columns(true), 0, 10);
    assertThat(gems.ruleVersion()).isEqualTo(EfficiencyRuleVersions.ARK_GRID_REAL);
    assertThat(gems.rows())
        .extracting(StatsPort.EfficiencyRow::characterName)
        .containsExactly("통계캐1", "통계캐3");

    // 서포터 원본 값은 자릿수를 자르지 않고 저장된다(캐릭터 화면 값과 같게).
    var supporter =
        jdbc.sql(
                "SELECT bracelet_real_pct FROM characters WHERE server_name = :s AND character_name = '통계캐2'")
            .param("s", SERVER)
            .query(BigDecimal.class)
            .single();
    assertThat(supporter).isEqualTo(new BigDecimal("18.107142857142858"));

    // 전투력 기여 모델 열은 따로다(실 효율만 저장된 캐릭터는 들어가지 않음).
    assertThat(
            statsPort
                .efficiencyRanking(
                    conditions, since, StatsPort.EfficiencyKind.BRACELET.columns(false), 0, 10)
                .total())
        .isZero();
  }

  private void realEfficiency(
      String name, String bracelet, String braceletVersion, String gem, Instant observedAt) {
    jdbc.sql(
            """
        UPDATE characters SET bracelet_real_pct = CAST(:bracelet AS NUMERIC),
          bracelet_real_rule_version = :bv, ark_grid_real_pct = CAST(:gem AS NUMERIC),
          ark_grid_real_rule_version = :gv, efficiency_observed_at = :at
        WHERE character_name = :name AND server_name = :server
        """)
        .param("bracelet", new BigDecimal(bracelet))
        .param("bv", braceletVersion)
        .param("gem", gem == null ? null : new BigDecimal(gem))
        .param("gv", gem == null ? null : EfficiencyRuleVersions.ARK_GRID_REAL)
        .param("at", observedAt.atOffset(ZoneOffset.UTC))
        .param("name", name)
        .param("server", SERVER)
        .update();
  }

  private void efficiency(
      String name,
      String bracelet,
      String braceletVersion,
      String gem,
      String gemVersion,
      Instant observedAt) {
    jdbc.sql(
            """
        UPDATE characters SET bracelet_efficiency_pct = CAST(:bracelet AS NUMERIC),
          bracelet_rule_version = :bv, ark_grid_gem_pct = CAST(:gem AS NUMERIC),
          ark_grid_rule_version = :gv, efficiency_observed_at = :at
        WHERE character_name = :name AND server_name = :server
        """)
        .param("bracelet", bracelet == null ? null : new BigDecimal(bracelet))
        .param("bv", braceletVersion)
        .param("gem", gem == null ? null : new BigDecimal(gem))
        .param("gv", gemVersion)
        .param("at", observedAt.atOffset(ZoneOffset.UTC))
        .param("name", name)
        .param("server", SERVER)
        .update();
  }

  private static JsonNode entry(JsonNode rank, String metric, String scope) {
    for (var entry : rank.path("entries"))
      if (entry.path("metric").asText().equals(metric)
          && entry.path("scope").asText().equals(scope)) return entry;
    throw new AssertionError(metric + " " + scope + " 항목이 없습니다.");
  }

  @Test
  void 캐릭터_순위는_다음_티어_경계값과_남은_차이를_준다() throws Exception {
    표본();
    // 통계캐30(전투력 3300)은 서버 40명 중 5위(12.5%) TOP25. 다음 TOP10의 K = ⌊10×40/100⌋ = 4, 4번째로 높은 값 3310.
    var rank = data("/api/v1/characters/rank?characterName=" + encode("통계캐30"));
    var server = entry(rank, "COMBAT_POWER", "SERVER");
    assertThat(server.path("tier").asText()).isEqualTo("TOP25");
    assertThat(server.path("nextTier").asText()).isEqualTo("TOP10");
    assertThat(server.path("nextTierValue").decimalValue()).isEqualByComparingTo("3310");
    assertThat(server.path("nextTierGap").decimalValue()).isEqualByComparingTo("10");
    // 경계값에 닿으면 실제로 다음 티어다: 3310은 3위보다 큰 값이 셋이라 4위(10%) TOP10, 3309.99면 5위(12.5%) TOP25.
    var at =
        data(
            "/api/v1/stats/distribution?metric=COMBAT_POWER&percentileOf=3310&server="
                + encode(SERVER));
    assertThat(at.at("/percentileOf/tier").asText()).isEqualTo("TOP10");
    var below =
        data(
            "/api/v1/stats/distribution?metric=COMBAT_POWER&percentileOf=3309.99&server="
                + encode(SERVER));
    assertThat(below.at("/percentileOf/tier").asText()).isEqualTo("TOP25");

    // 통계캐34(3340)는 1위(2.5%) TOP5. 다음 TOP1의 K = ⌊1×40/100⌋ = 0이라 아무도 닿을 수 없어 null.
    var top =
        entry(
            data("/api/v1/characters/rank?characterName=" + encode("통계캐34")),
            "COMBAT_POWER",
            "SERVER");
    assertThat(top.path("tier").asText()).isEqualTo("TOP5");
    assertThat(top.path("nextTier").isNull()).isTrue();
    assertThat(top.path("nextTierValue").isNull()).isTrue();
    assertThat(top.path("nextTierGap").isNull()).isTrue();

    // 표본 30 미만(서버 내 직업 SMALL 5명)은 tier가 null이라 다음 티어도 null.
    var small =
        entry(
            data("/api/v1/characters/rank?characterName=" + encode("통계소수0")),
            "COMBAT_POWER",
            "SERVER_CLASS");
    assertThat(small.path("tier").isNull()).isTrue();
    assertThat(small.path("nextTier").isNull()).isTrue();

    // 아이템 레벨도 같은 정의: 통계캐30(1730)은 서버 5위 TOP25, 4번째로 높은 1731이 경계.
    var level = entry(rank, "ITEM_LEVEL", "SERVER");
    assertThat(level.path("nextTier").asText()).isEqualTo("TOP10");
    assertThat(level.path("nextTierValue").decimalValue()).isEqualByComparingTo("1731");
    assertThat(level.path("nextTierGap").decimalValue()).isEqualByComparingTo("1");
  }

  @Test
  void 처리정지_캐릭터는_다음_티어_경계값_후보에서_빠진다() throws Exception {
    표본();
    // 통계캐33(3330)을 빼면 서버 39명. 통계캐30(3300)은 큰 값이 3310·3320·3340 셋이라 4위(10.26%) TOP25.
    // 다음 TOP10의 K = ⌊10×39/100⌋ = 3, 3번째로 높은 값은 3310(처리정지 3330을 넣었다면 3320).
    jdbc.sql(
            "INSERT INTO public_exclusion (character_name, server_name, reason_code) VALUES ('통계캐33', :s, 'REQUEST')")
        .param("s", SERVER)
        .update();
    try {
      var server =
          entry(
              data("/api/v1/characters/rank?characterName=" + encode("통계캐30")),
              "COMBAT_POWER",
              "SERVER");
      assertThat(server.path("total").asLong()).isEqualTo(39);
      assertThat(server.path("tier").asText()).isEqualTo("TOP25");
      assertThat(server.path("nextTier").asText()).isEqualTo("TOP10");
      assertThat(server.path("nextTierValue").decimalValue()).isEqualByComparingTo("3310");
      assertThat(server.path("nextTierGap").decimalValue()).isEqualByComparingTo("10");
    } finally {
      jdbc.sql("DELETE FROM public_exclusion WHERE character_name = '통계캐33'").update();
    }
  }

  @Test
  void 같은_조건은_10분_캐시를_쓴다() throws Exception {
    표본();
    var first = data("/api/v1/stats/summary?server=" + encode(SERVER));
    jdbc.sql("DELETE FROM characters WHERE server_name = :s").param("s", SERVER).update();
    var second = data("/api/v1/stats/summary?server=" + encode(SERVER));
    assertThat(second.at("/meta/population").asLong()).isEqualTo(40);
    assertThat(second.at("/meta/aggregatedAt").asText())
        .isEqualTo(first.at("/meta/aggregatedAt").asText());
    var other = data("/api/v1/stats/summary?activeDays=7&server=" + encode(SERVER));
    assertThat(other.at("/meta/population").asLong()).isZero();
    assertThat(other.at("/meta/sampleStatus").asText()).isEqualTo("COLLECTING");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/stats/summary?activeDays=15",
        "/api/v1/stats/summary?role=TANK",
        "/api/v1/stats/summary?server=a-b",
        "/api/v1/stats/summary?itemLevelMin=2001",
        "/api/v1/stats/distribution",
        "/api/v1/stats/distribution?metric=ITEM_LEVEL&width=0",
        "/api/v1/rankings?size=101",
        "/api/v1/rankings?page=0",
        "/api/v1/characters/rank"
      })
  void 잘못된_조건은_400이다(String path) throws Exception {
    var response = get(path);
    assertThat(response.statusCode()).as(path).isEqualTo(400);
  }

  @Test
  void 범위_역전_구간_과다_미기록_캐릭터는_업무_오류다() throws Exception {
    표본();
    var range = get("/api/v1/stats/summary?itemLevelMin=1700&itemLevelMax=1650");
    assertThat(range.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(range.body()).path("code").asText())
        .isEqualTo("STATS_INVALID_RANGE");
    var bins =
        get("/api/v1/stats/distribution?metric=COMBAT_POWER&width=0.01&server=" + encode(SERVER));
    assertThat(bins.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(bins.body()).path("code").asText()).isEqualTo("STATS_TOO_MANY_BINS");
    var missing = get("/api/v1/characters/rank?characterName=" + encode("없는통계캐릭"));
    assertThat(missing.statusCode()).isEqualTo(404);
    assertThat(mapper.readTree(missing.body()).path("code").asText())
        .isEqualTo("STATS_CHARACTER_NOT_OBSERVED");
    var snapshots = data("/api/v1/stats/snapshots");
    assertThat(snapshots.at("/snapshots/0/snapshot").asText()).isEqualTo("latest");
  }

  private void insert(
      String name,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String position,
      BigDecimal gem,
      UUID roster,
      Instant refreshed) {
    insert(SERVER, name, className, itemLevel, combatPower, position, gem, roster, refreshed);
  }

  private void insert(
      String server,
      String name,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String position,
      BigDecimal gem,
      UUID roster,
      Instant refreshed) {
    jdbc.sql(
            """
        INSERT INTO characters (character_id, character_name, server_name, class_name, item_level,
          combat_power, last_synced_at, profile_refreshed_at, position, gem_mean_level, roster_key)
        VALUES (:id, :name, :server, :className, :itemLevel, :combatPower, :at, :at, :position, :gem, :roster)
        """)
        .param("id", UUID.randomUUID())
        .param("name", name)
        .param("server", server)
        .param("className", className)
        .param("itemLevel", itemLevel)
        .param("combatPower", combatPower)
        .param("at", refreshed.atOffset(ZoneOffset.UTC))
        .param("position", position)
        .param("gem", gem)
        .param("roster", roster)
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
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
