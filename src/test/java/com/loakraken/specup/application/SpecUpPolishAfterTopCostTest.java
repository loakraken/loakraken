package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.specup.domain.SpecUpCost;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** '상·상 뒤' 셋째 줄 후보의 비용을 모르면 더 드는 비용도 모름(null)이고, 공짜(0 이하) 단계로 앞에 오지 않는다(PM 총괄 11-15 #957). */
class SpecUpPolishAfterTopCostTest {
  private static SpecUpGoalService.Option option(SpecUpCost cost) {
    var change =
        new Change(
            Kind.POLISH, null, 9, 2, null, null, null, "공격력", "HIGH", null, null, null, null, null);
    return new SpecUpGoalService.Option(
        "POLISH:9",
        "POLISH:9:HHAH",
        "반지",
        "a",
        "b",
        null,
        null,
        null,
        change,
        cost,
        null,
        null,
        null,
        List.of());
  }

  @Test
  void 비용을_모르면_더_드는_비용도_모르고_공짜가_아니다() {
    var paid = Map.of("POLISH:9", 50_000L);
    var marginal = SpecUpGoalService.marginal(option(SpecUpCost.POLISH_COST_UNCONFIRMED), paid);
    assertThat(marginal).isNull();
    // 비용 모름은 비율이 없다(비용 있는 단계 뒤, PM 총괄 11-19).
    assertThat(SpecUpGoalService.rated(marginal)).isFalse();
    assertThat(SpecUpGoalService.paidCost(option(SpecUpCost.POLISH_COST_UNCONFIRMED), paid))
        .isNull();
  }

  @Test
  void 비용을_알면_앞_매물_비용을_뺀_만큼이다() {
    var paid = Map.of("POLISH:9", 50_000L);
    var marginal = SpecUpGoalService.marginal(option(SpecUpCost.polish(80_000L, true)), paid);
    assertThat(marginal).isEqualTo(30_000L);
    assertThat(SpecUpGoalService.rated(marginal)).isTrue();
    // 추천 순서 단계 비용(PM 총괄 11-19, 계산 검증 11-19): 그 칸의 첫 단계는 목록 비용(처음 장신구와의 차이), 이어 바꾸면 거래 가능한 장신구는 새 매물
    // 가격 −
    // 앞 단계에서 산 매물 가격(0 미만은 0), 거래할 수 없는 장신구는 새 매물 전부.
    var none = Map.<String, Long>of();
    assertThat(SpecUpGoalService.paidCost(option(SpecUpCost.polish(80_000L, true)), none))
        .isEqualTo(80_000L);
    var held = Map.of("POLISH:9", 100_000L);
    assertThat(
            SpecUpGoalService.paidCost(
                option(SpecUpCost.polish(30_000L, true).withItemPrice(130_000L)), held))
        .isEqualTo(30_000L);
    assertThat(
            SpecUpGoalService.paidCost(
                option(SpecUpCost.polish(80_000L, false).withItemPrice(80_000L)), held))
        .isEqualTo(80_000L);
    assertThat(
            SpecUpGoalService.paidCost(
                option(SpecUpCost.polish(0L, true).withItemPrice(90_000L)), held))
        .isZero();
  }
}
