package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.specup.domain.SpecUpCost;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * KRAKEN 정렬 묶음: ① 10만 골드당 → ② Δ > 0·0골드 → ③ Δ > 0·비용 모름 → ④ 나머지(비교 기준 B와 같음, PM 판정 10-18 15:18).
 */
class SpecUpKrakenOrderTest {
  @Test
  void 비용_0골드와_비용_모름은_비용_있는_후보_뒤에서_함께_Δ_큰_순이다() {
    var candidates =
        new ArrayList<>(
            List.of(
                candidate("unknown-big", "50", SpecUpCost.REFINE_PRICE_PENDING),
                candidate("priced-low", "10", SpecUpCost.refineExpected(100_000)),
                candidate("free-small", "1", SpecUpCost.refineExpected(0)),
                candidate("priced-high", "10", SpecUpCost.refineExpected(10_000)),
                candidate("free-big", "5", SpecUpCost.refineExpected(0)),
                candidate("none", null, SpecUpCost.refineExpected(10_000)),
                candidate("unknown-small", "2", SpecUpCost.REFINE_COST_UNCONFIRMED)));
    candidates.sort(SpecUpService.order(SpecUpService.Sort.KRAKEN));
    assertThat(candidates)
        .extracting(SpecUpService.Candidate::id)
        .containsExactly(
            "priced-high",
            "priced-low",
            "unknown-big",
            "free-big",
            "unknown-small",
            "free-small",
            "none");
  }

  @Test
  void 소수_둘째_자리는_화면_표기처럼_가장_짧은_십진_표기에서_반올림한다() {
    // 비교 기준 B는 Intl.NumberFormat(소수 둘째 자리)으로 보인다: 1.005 → 1.01, 0.125 → 0.13, 1.6249999999999998 →
    // 1.62.
    assertThat(SpecUpKrakenScore.round2(1.005)).isEqualByComparingTo("1.01");
    assertThat(SpecUpKrakenScore.round2(0.125)).isEqualByComparingTo("0.13");
    assertThat(SpecUpKrakenScore.round2(1.625 / 1e5 * 1e5)).isEqualByComparingTo("1.62");
    // 10만 골드당도 같은 반올림: Δ 1.005 ÷ 10만 골드 × 10만 = 1.005 → 1.01.
    var tie =
        candidate("tie", "1.01", SpecUpCost.refineExpected(100_000))
            .withKraken(new BigDecimal("1.01"), 1.005, SpecUpCost.refineExpected(100_000));
    assertThat(tie.krakenScorePer100kGold()).isEqualByComparingTo("1.01");
  }

  private static SpecUpService.Candidate candidate(String id, String delta, SpecUpCost cost) {
    return new SpecUpService.Candidate(
            id,
            "EQUIPMENT_REFINE",
            id,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "UNSUPPORTED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null)
        .withKraken(delta == null ? null : new BigDecimal(delta), cost);
  }
}
