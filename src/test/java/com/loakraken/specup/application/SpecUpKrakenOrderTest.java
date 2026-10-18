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
  void 비용_0골드_후보는_비용_있는_후보_뒤이고_비용_모름보다_앞이다() {
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
            "free-big",
            "free-small",
            "unknown-big",
            "unknown-small",
            "none");
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
