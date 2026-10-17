package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.CharacterTestFixture;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterProfile;
import com.loakraken.character.domain.KrakenScoreTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class KrakenScoreServiceTest {
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
    var score = new KrakenScoreService(TABLE, false, registry).score(result("블레이드"));
    assertThat(score.status()).isEqualTo(KrakenScoreService.Status.DISABLED);
    assertThat(score.score()).isNull();
    assertThat(score.tier()).isNull();
    assertThat(score.role()).isNull();
    assertThat(score.factors()).isEmpty();
    assertThat(score.ruleVersion()).isEqualTo("kraken-score-v1");
    assertThat(registry.find("loakraken.kraken_score.unsupported").counter()).isNull();
  }

  @Test
  void 켜져_있어도_요소_계산_전이면_UNSUPPORTED이고_이유별로_센다() {
    var registry = new SimpleMeterRegistry();
    var service = new KrakenScoreService(TABLE, true, registry);
    var dealer = service.score(result("블레이드"));
    assertThat(dealer.status()).isEqualTo(KrakenScoreService.Status.UNSUPPORTED);
    assertThat(dealer.role()).isEqualTo(KrakenScoreService.Role.DEALER);
    assertThat(dealer.reason()).isEqualTo(KrakenScoreService.Reason.FACTOR_PENDING);
    assertThat(dealer.score()).isNull();
    // 바드는 깨달음 노드가 없으면 역할을 판정하지 못한다.
    var unknown = service.score(result("바드"));
    assertThat(unknown.role()).isNull();
    assertThat(unknown.reason()).isEqualTo(KrakenScoreService.Reason.ROLE_UNKNOWN);
    assertThat(
            registry
                .get("loakraken.kraken_score.unsupported")
                .tag("reason", "FACTOR_PENDING")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("loakraken.kraken_score.unsupported")
                .tag("reason", "ROLE_UNKNOWN")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 계산한_점수는_소수_둘째_자리로_내고_티어는_반올림_전_값으로_판정한다() {
    var service = new KrakenScoreService(TABLE, true, new SimpleMeterRegistry());
    // 2499.996은 반올림하면 2500.00이지만 티어는 원값으로 판정해 BRONZE다.
    var score =
        service.ok(
            KrakenScoreService.Role.DEALER,
            new BigDecimal("2499.996"),
            List.of(
                new KrakenScoreService.Factor(
                    "ATTACK", new BigDecimal("1.05"), KrakenScoreService.Status.OK, null)));
    assertThat(score.status()).isEqualTo(KrakenScoreService.Status.OK);
    assertThat(score.score()).isEqualByComparingTo("2500.00");
    assertThat(score.tier().code()).isEqualTo("BRONZE");
    assertThat(score.factors()).hasSize(1);
  }
}
