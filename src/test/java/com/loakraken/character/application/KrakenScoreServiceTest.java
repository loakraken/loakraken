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
            profile, new CharacterLoadout(List.of(), null, null, null, null, null), List.of()),
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

  private static CharacterResult withInput(JsonNode input) {
    var base = result("블레이드");
    return new CharacterResult(base.details(), base.fetchedAt(), null, input);
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
    for (var input : new JsonNode[] {null, tools.jackson.databind.node.JsonNodeFactory.instance.nullNode()}) {
      var score = service.score(withInput(input));
      assertThat(score.status()).isEqualTo(KrakenScoreService.Status.UNSUPPORTED);
      assertThat(score.reason()).isEqualTo(KrakenScoreService.Reason.INPUT_PENDING);
      assertThat(score.score()).isNull();
    }
    var unknown = service.score(withInput(MAPPER.readTree("{\"profile\":{}}")));
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
            new ClassPathResource("kraken-score/tiers.json").getContentAsString(StandardCharsets.UTF_8),
            KrakenScoreTier.Table.class);
    var registry = new SimpleMeterRegistry();
    var service = new KrakenScoreService(tiers, ENGINE, true, registry);
    var dealer = service.score(withInput(fixtureInput("l01")));
    assertThat(dealer.status()).isEqualTo(KrakenScoreService.Status.OK);
    assertThat(dealer.role()).isEqualTo(KrakenScoreService.Role.DEALER);
    assertThat(dealer.score()).isEqualByComparingTo("3977.02");
    assertThat(dealer.tier().code()).isEqualTo("GOLD");
    var support = service.score(withInput(fixtureInput("l04")));
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
