package com.loakraken.specup.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** 카르마·스톤 기대 골드: 비교 기준 B 화면 값(같은 시각 레퍼런스 표, 만 단위 둘째 자리)과 원본 식. */
class SpecUpCostRulesTest {
  @Test
  void 카르마_기대_골드는_단계마다_기대_시도_곱하기_900의_합이다() {
    // B: 29 → 30 41.32만, 28 → 29 16.54만, 28 → 30 57.86만.
    assertThat(SpecUpCostRules.karmaGold(29, 30)).isCloseTo(413_227.7957, within(1e-3));
    assertThat(SpecUpCostRules.karmaGold(28, 29)).isCloseTo(165_390.4796, within(1e-3));
    assertThat(SpecUpCostRules.karmaGold(28, 30))
        .isEqualTo(SpecUpCostRules.karmaGold(28, 29) + SpecUpCostRules.karmaGold(29, 30));
    // 20% · 실패 때 장인의 기운 10%면 천장은 10번 실패 뒤 11번째 시도.
    assertThat(SpecUpCostRules.karmaExpectedAttempts(0.2, 10)).isCloseTo(4.5705, within(1e-4));
  }

  @Test
  void 카르마는_0레벨에서도_0에서_1_단계를_세지_않고_범위_밖은_null이다() {
    assertThat(SpecUpCostRules.karmaGold(0, 2)).isEqualTo(SpecUpCostRules.karmaGold(1, 2));
    assertThat(SpecUpCostRules.karmaGold(0, 1)).isZero();
    assertThat(SpecUpCostRules.karmaGold(30, 31)).isNull();
    assertThat(SpecUpCostRules.karmaGold(5, 5)).isNull();
    assertThat(SpecUpCostRules.karmaGold(-1, 3)).isNull();
  }

  @Test
  void 스톤_기대_골드는_키트_수_곱하기_50이다() {
    // B: 97돌·106돌 735.29개, 107돌 2.62만개, 99돌 30.86만개, 109돌 729.93만개, 1010돌 6.29억개.
    assertThat(SpecUpCostRules.stoneGold("97돌") / 50).isCloseTo(735.29, within(0.01));
    assertThat(SpecUpCostRules.stoneGold("106돌")).isEqualTo(SpecUpCostRules.stoneGold("97돌"));
    assertThat(SpecUpCostRules.stoneGold("107돌") / 50).isCloseTo(26_246.72, within(0.01));
    assertThat(SpecUpCostRules.stoneGold("99돌") / 50).isCloseTo(308_641.98, within(0.01));
    assertThat(SpecUpCostRules.stoneGold("109돌") / 50).isCloseTo(7_299_270.07, within(0.01));
    assertThat(SpecUpCostRules.stoneGold("1010돌") / 50).isCloseTo(628_930_817.61, within(0.01));
    assertThat(SpecUpCostRules.stoneGold("88돌")).isNull();
  }

  @Test
  void 비용은_반올림한_금액과_반올림_전_값을_함께_둔다() {
    var karma = SpecUpCost.karma(413_227.7957);
    assertThat(karma.amount()).isEqualTo(413_228L);
    assertThat(karma.exact()).isEqualTo(413_227.7957);
    assertThat(SpecUpCost.gem(100, null, false).exact()).isEqualTo(100.0);
    assertThat(SpecUpCost.PRICE_PENDING.exact()).isNull();
  }
}
