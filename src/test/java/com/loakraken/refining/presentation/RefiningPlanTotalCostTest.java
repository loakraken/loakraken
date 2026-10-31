package com.loakraken.refining.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.refining.domain.RefiningPlan;
import java.util.List;
import org.junit.jupiter.api.Test;

/** OPTIMAL 계획 합계: 단계 기대 비용을 올리기 전 값으로 더한 뒤 한 번 올린다(스펙업과 같은 방식, 코드 리뷰 10-25 예시). */
class RefiningPlanTotalCostTest {
  @Test
  void 단계마다_올리지_않고_합을_한_번_올린다() {
    var stages =
        List.of(
            new RefiningPlan.OptimalStage(List.of(), 0, 0, 100.2),
            new RefiningPlan.OptimalStage(List.of(), 0, 0, 200.3));
    // 단계마다 올리면 101 + 201 = 302, 한 번 올리면 ceil(300.5) = 301
    assertThat(RefiningPlanResponse.totalCostGold(stages)).isEqualTo(301);
    assertThat(
            RefiningPlanResponse.totalCostGold(
                List.of(new RefiningPlan.OptimalStage(List.of(), 0, 0, 4_906.01))))
        .isEqualTo(4_907);
  }
}
