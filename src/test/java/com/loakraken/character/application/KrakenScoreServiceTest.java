package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreEngine;
import com.loakraken.character.domain.KrakenScoreInputMapper;
import com.loakraken.character.domain.KrakenScoreTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class KrakenScoreServiceTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final KrakenScoreEngine.Tables ENGINE = engineTables();

  private static KrakenScoreEngine.Tables engineTables() {
    try {
      var doc =
          MAPPER.readTree(
              new ClassPathResource("kraken-score/reference-tables.json")
                  .getContentAsString(StandardCharsets.UTF_8));
      return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static final KrakenScoreTier.Table TABLE =
      new KrakenScoreTier.Table(
          "kraken-score-v1",
          "테스트",
          List.of(
              new KrakenScoreTier.Cut("BRONZE", BigDecimal.ZERO),
              new KrakenScoreTier.Cut("SILVER", new BigDecimal("2500")),
              new KrakenScoreTier.Cut("ESTHER", new BigDecimal("8800"))));

  private static CharacterResult result(String className) {
    return result(className, null);
  }

  /** enlightenment: 깨달음 노드 이름(서포터 가능 직업의 딜러·서포터 판정 근거). null이면 아크 패시브 없음. */
  private static CharacterResult result(String className, String enlightenment) {
    var profile =
        new CharacterProfile(
            "점수표본",
            "루페온",
            className,
            70,
            new BigDecimal("1700"),
            new BigDecimal("3000"),
            300,
            null,
            null,
            null,
            List.of(),
            null);
    return new CharacterResult(
        CharacterTestFixture.details(
            profile,
            new CharacterLoadout(
                List.of(),
                null,
                null,
                null,
                enlightenment == null
                    ? null
                    : new CharacterLoadout.ArkPassive(
                        enlightenment,
                        true,
                        List.of(),
                        List.of(
                            new CharacterLoadout.PassiveEffect(
                                "깨달음", "깨달음 1티어 " + enlightenment + " Lv.1", null))),
                null),
            List.of()),
        Instant.EPOCH);
  }

  @Test
  void 꺼져_있으면_DISABLED이고_값과_요소가_없으며_지표를_세지_않는다() {
    var registry = new SimpleMeterRegistry();
    var score = new KrakenScoreService(TABLE, ENGINE, false, registry).score(result("블레이드"));
    assertThat(score.status()).isEqualTo(KrakenScoreService.Status.DISABLED);
    assertThat(score.score()).isNull();
    assertThat(score.tier()).isNull();
    assertThat(score.role()).isNull();
    assertThat(score.factors()).isEmpty();
    assertThat(score.ruleVersion()).isEqualTo("kraken-score-v1");
    assertThat(registry.find("loakraken.kraken_score.unsupported").counter()).isNull();
  }

  @Test
  void 켜진_상태에서_엔진_밖_예외가_나면_꺼짐이_아니라_CALC_FAILED다() {
    // 캐릭터 정보가 없는 결과: 입력 확인은 지나고 역할 판정에서 예외 — 전에는 컨트롤러가 null을 받아 DISABLED로 보였다.
    var broken =
        new CharacterResult(null, java.time.Instant.EPOCH, null, MAPPER.createObjectNode());
    var registry = new SimpleMeterRegistry();
    var score = new KrakenScoreService(TABLE, ENGINE, true, registry).score(broken);
    assertThat(score.status()).isEqualTo(KrakenScoreService.Status.UNSUPPORTED);
    assertThat(score.reason()).isEqualTo(KrakenScoreService.Reason.CALC_FAILED);
    assertThat(score.score()).isNull();
    assertThat(score.tier()).isNull();
    // 꺼져 있으면 같은 결과도 지금처럼 DISABLED(계산하지 않음).
    assertThat(
            new KrakenScoreService(TABLE, ENGINE, false, new SimpleMeterRegistry())
                .score(broken)
                .status())
        .isEqualTo(KrakenScoreService.Status.DISABLED);
    // 컨트롤러의 마지막 안전망도 같은 값.
    assertThat(new KrakenScoreService(TABLE, ENGINE, true, registry).failed().reason())
        .isEqualTo(KrakenScoreService.Reason.CALC_FAILED);
  }

  private static CharacterResult withInput(JsonNode input) {
    return withInput(input, "블레이드", null);
  }

  private static CharacterResult withInput(JsonNode input, String className, String enlightenment) {
    var base = result(className, enlightenment);
    return new CharacterResult(base.details(), base.fetchedAt(), null, input);
  }

  @Test
  void 발키리는_해방자면_서포터_빛의_기사면_딜러이고_무기나_아크_패시브가_비면_계산하지_않는다() throws IOException {
    var service = new KrakenScoreService(TABLE, ENGINE, true, new SimpleMeterRegistry());
    var input = fixtureInput("l01");
    assertThat(service.score(withInput(input, "발키리", "해방자")).role())
        .isEqualTo(KrakenScoreService.Role.SUPPORT);
    assertThat(service.score(withInput(input, "발키리", "빛의 기사")).role())
        .isEqualTo(KrakenScoreService.Role.DEALER);
    // 직업만 있고 장비·아크 패시브가 빈 원문 → 0점(최하 티어) 대신 계산하지 않음.
    var empty =
        service.score(
            withInput(
                KrakenScoreInputMapper.from(
                    MAPPER.readTree("{\"ArmoryProfile\":{\"CharacterClassName\":\"블레이드\"}}")),
                "블레이드",
                null));
    assertThat(empty.status()).isEqualTo(KrakenScoreService.Status.UNSUPPORTED);
    assertThat(empty.reason()).isEqualTo(KrakenScoreService.Reason.SCORE_UNAVAILABLE);
    assertThat(empty.score()).isNull();
  }

  private static JsonNode fixtureInput(String id) throws IOException {
    var doc =
        MAPPER.readTree(
            new ClassPathResource("kraken-score/" + id + ".json")
                .getContentAsString(StandardCharsets.UTF_8));
    return KrakenScoreInputMapper.from(doc.path("raw"));
  }

  @Test
  void 켜져_있어도_점수_입력이_없는_이전_결과면_UNSUPPORTED이고_이유별로_센다() {
    var registry = new SimpleMeterRegistry();
    var service = new KrakenScoreService(TABLE, ENGINE, true, registry);
    // 옛 캐시(입력 없음)와 캐시를 다시 읽어 JSON null이 된 경우 모두 다시 조회하면 계산된다.
    for (var input :
        new JsonNode[] {null, tools.jackson.databind.node.JsonNodeFactory.instance.nullNode()}) {
      var score = service.score(withInput(input));
      assertThat(score.status()).isEqualTo(KrakenScoreService.Status.UNSUPPORTED);
      assertThat(score.reason()).isEqualTo(KrakenScoreService.Reason.INPUT_PENDING);
      assertThat(score.score()).isNull();
    }
    // 서포터 가능 직업인데 깨달음 노드가 없으면 역할을 판정하지 못한다.
    var unknown = service.score(withInput(MAPPER.readTree("{\"profile\":{}}"), "바드", null));
    assertThat(unknown.role()).isNull();
    assertThat(unknown.reason()).isEqualTo(KrakenScoreService.Reason.ROLE_UNKNOWN);
    assertThat(
            registry
                .get("loakraken.kraken_score.unsupported")
                .tag("reason", "INPUT_PENDING")
                .counter()
                .count())
        .isEqualTo(2);
    assertThat(
            registry
                .get("loakraken.kraken_score.unsupported")
                .tag("reason", "ROLE_UNKNOWN")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 공식_원문에서_만든_입력으로_딜러와_서폿_점수를_내고_티어를_정한다() throws IOException {
    var tiers =
        MAPPER.readValue(
            new ClassPathResource("kraken-score/tiers.json")
                .getContentAsString(StandardCharsets.UTF_8),
            KrakenScoreTier.Table.class);
    var registry = new SimpleMeterRegistry();
    var service = new KrakenScoreService(tiers, ENGINE, true, registry);
    var dealer = service.score(withInput(fixtureInput("l01")));
    assertThat(dealer.status()).isEqualTo(KrakenScoreService.Status.OK);
    assertThat(dealer.role()).isEqualTo(KrakenScoreService.Role.DEALER);
    assertThat(dealer.score()).isEqualByComparingTo("3977.02");
    assertThat(dealer.tier().code()).isEqualTo("GOLD");
    var support = service.score(withInput(fixtureInput("l04"), "도화가", "만개"));
    assertThat(support.role()).isEqualTo(KrakenScoreService.Role.SUPPORT);
    assertThat(support.score()).isEqualByComparingTo("5286.48");
    assertThat(support.tier().code()).isEqualTo("DIAMOND");
    assertThat(registry.get("loakraken.kraken_score.duration").timer().count()).isEqualTo(2);
  }

  @Test
  void 계산한_점수는_소수_둘째_자리로_내고_티어는_반올림_전_값으로_판정한다() {
    var service = new KrakenScoreService(TABLE, ENGINE, true, new SimpleMeterRegistry());
    // 2499.996은 반올림하면 2500.00이지만 티어는 원값으로 판정해 BRONZE다.
    var score =
        service.ok(
            KrakenScoreService.Role.DEALER,
            2499.996,
            List.of(
                new KrakenScoreService.Factor(
                    "ATTACK", new BigDecimal("1.05"), KrakenScoreService.Status.OK, null)));
    assertThat(score.status()).isEqualTo(KrakenScoreService.Status.OK);
    assertThat(score.score()).isEqualByComparingTo("2500.00");
    assertThat(score.tier().code()).isEqualTo("BRONZE");
    assertThat(score.factors()).hasSize(1);
    // 표시 점수도 원본 toFixed(2)와 같다: 1.005(double은 1.00499…)는 1.00.
    assertThat(service.ok(KrakenScoreService.Role.DEALER, 1.005, List.of()).score())
        .isEqualByComparingTo("1.00");
  }

  @Test
  void 음수_NaN_무한대_점수는_OK도_티어도_아니다() {
    var registry = new SimpleMeterRegistry();
    var service = new KrakenScoreService(TABLE, ENGINE, true, registry);
    for (double bad : new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY}) {
      var score = service.ok(KrakenScoreService.Role.DEALER, bad, List.of());
      assertThat(score.status()).isEqualTo(KrakenScoreService.Status.UNSUPPORTED);
      assertThat(score.reason()).isEqualTo(KrakenScoreService.Reason.SCORE_UNAVAILABLE);
      assertThat(score.score()).isNull();
      assertThat(score.tier()).isNull();
      assertThat(score.role()).isEqualTo(KrakenScoreService.Role.DEALER);
    }
    assertThat(
            registry
                .get("loakraken.kraken_score.unsupported")
                .tag("reason", "SCORE_UNAVAILABLE")
                .counter()
                .count())
        .isEqualTo(3);
  }
}
