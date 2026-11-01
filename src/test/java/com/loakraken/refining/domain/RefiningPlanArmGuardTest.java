package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 팔 보호구(완갑) 재련 계획: 단계표 하나, 숨결 두 가지(용암·빙하), 책·성장 지원 없음. */
class RefiningPlanArmGuardTest {
  private static final VambraceRules RULES = vambraceRules();

  @Test
  void 숨결_최대면_두_숨결을_매_시도_최대_개수씩_쓴다() {
    // 15단계: 기본 5% + 용암 0.1%×25 + 빙하 0.1%×25 = 10%, 16단계: 3% + 0.05%×30×2 = 6%.
    var plan = RefiningPlan.calculate(RULES, options(14, 16, true, 0, 0));
    var first = plan.stages().get(0);
    assertThat(first.targetStage()).isEqualTo(15);
    assertThat(first.firstAttemptSuccessRateBp()).isEqualTo(1000);
    assertThat(first.expectedAttempts()).isEqualByComparingTo("7.52");
    assertThat(first.medianAttempts()).isEqualTo(6);
    assertThat(first.p90Attempts()).isEqualTo(17);
    assertThat(first.maxAttempts()).isEqualTo(18);
    assertThat(amount(first.expectedMaterials(), "용암의 숨결")).isEqualByComparingTo("185.92");
    assertThat(amount(first.expectedMaterials(), "빙하의 숨결")).isEqualByComparingTo("185.92");
    // 확정 회차(18번째)에는 숨결을 쓰지 않는다: 25 × 17.
    assertThat(amount(first.worstCaseMaterials(), "용암의 숨결")).isEqualByComparingTo("425");
    assertThat(amount(first.expectedMaterials(), "운명의 파괴석 결정")).isEqualByComparingTo("6996.98");
    assertThat(amount(first.expectedMaterials(), RefiningPlan.SHARD))
        .isEqualByComparingTo("192078.26");
    assertThat(first.growth()).isNull();
    var second = plan.stages().get(1);
    assertThat(second.firstAttemptSuccessRateBp()).isEqualTo(600);
    assertThat(second.expectedAttempts()).isEqualByComparingTo("11.59");
    assertThat(second.maxAttempts()).isEqualTo(27);
    // 성장 지원이 없어 골드는 단계표 그대로(8,930 × 7.52… + 9,280 × 11.59…).
    assertThat(plan.expectedGold()).isEqualByComparingTo("174700.94");
    assertThat(plan.worstCaseGold()).isEqualTo(8_930L * 18 + 9_280L * 27);
    assertThat(amount(plan.expectedMaterials(), "용암의 숨결")).isEqualByComparingTo("530.40");
  }

  @Test
  void 숨결_없음이면_숨결_수량이_없고_기본_확률로_계산한다() {
    var plan = RefiningPlan.calculate(RULES, options(14, 15, false, 0, 0));
    var stage = plan.stages().getFirst();
    assertThat(stage.firstAttemptSuccessRateBp()).isEqualTo(500);
    assertThat(stage.expectedMaterials())
        .extracting(RefiningPlan.Quantity::name)
        .doesNotContain("용암의 숨결", "빙하의 숨결");
  }

  @Test
  void 처음부터_1단계까지_계산하고_범위_밖은_막는다() {
    // 1단계: 15% + 0.375%×20×2 = 30%.
    var plan = RefiningPlan.calculate(RULES, options(0, 1, true, 0, 0));
    assertThat(plan.stages().getFirst().firstAttemptSuccessRateBp()).isEqualTo(3000);
    assertThat(RefiningPlan.calculate(RULES, options(0, 25, false, 0, 0)).stages()).hasSize(25);
    assertThatThrownBy(() -> RefiningPlan.calculate(RULES, options(24, 26, false, 0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 지금_장인의_기운과_실패_횟수는_첫_단계에만_쓴다() {
    // 실패 2회: 5% × 1.2 + 숨결 5% = 11%, 기운 50%에서 시작해 최대 시도가 줄어든다.
    var plan = RefiningPlan.calculate(RULES, options(14, 16, true, 5_000, 2));
    var first = plan.stages().get(0);
    assertThat(first.firstAttemptSuccessRateBp()).isEqualTo(1100);
    assertThat(first.maxAttempts()).isLessThan(18);
    var fresh = RefiningPlan.calculate(RULES, options(15, 16, true, 0, 0)).stages().getFirst();
    assertThat(plan.stages().get(1).expectedAttempts())
        .isEqualByComparingTo(fresh.expectedAttempts());
  }

  @Test
  void OPTIMAL은_스펙업_완갑_비용과_같은_단계_풀이를_쓴다() {
    // 0/0에서 시작한 단계 풀이는 스펙업 완갑 비용(RefiningOptimal.calculate(stage, prices))과 같다: 17단계 677,293.26,
    // 17~21 합
    // 4,335,693.74(계산 검증 10-25 비교 기준 대조와 같은 시세).
    var optimal =
        RefiningPlan.optimal(RULES, options(16, 21, false, 0, 0), prices(), OptimalStageCache.NONE);
    assertThat(optimal).isNotNull();
    assertThat(optimal.stages().getFirst().expectedCostGold())
        .isCloseTo(677_293.26, Offset.offset(0.01));
    double total = 0;
    for (var stage : optimal.stages()) total += stage.expectedCostGold();
    assertThat(total).isCloseTo(4_335_693.74, Offset.offset(0.05));
    var first = optimal.plan().stages().getFirst();
    var solved = RefiningOptimal.calculate(RULES.stage(17), prices());
    assertThat(first.expectedAttempts().doubleValue())
        .isCloseTo(solved.expectedAttempts(), Offset.offset(0.005));
    // 숨결은 이름별로 나뉘고 합은 기대 숨결 수다(빙하 414 < 용암 476이라 빙하부터).
    var glacier = amount(first.expectedMaterials(), "빙하의 숨결");
    var lava = amountOrZero(first.expectedMaterials(), "용암의 숨결");
    assertThat(glacier.add(lava).doubleValue())
        .isCloseTo(solved.expectedBreaths(), Offset.offset(0.01));
    assertThat(glacier.doubleValue())
        .isCloseTo(solved.expectedBreaths() - solved.expectedSecondBreaths(), Offset.offset(0.005));
    assertThat(optimal.stages().getFirst().breathPath()).isNotEmpty();
  }

  @Test
  void OPTIMAL은_첫_단계_시작_상태에서_풀고_시세가_모자라면_null이다() {
    var fresh =
        RefiningPlan.optimal(RULES, options(16, 17, false, 0, 0), prices(), OptimalStageCache.NONE);
    var started =
        RefiningPlan.optimal(
            RULES, options(16, 17, false, 4_000, 3), prices(), OptimalStageCache.NONE);
    assertThat(started.stages().getFirst().expectedCostGold())
        .isLessThan(fresh.stages().getFirst().expectedCostGold());
    assertThat(started.stages().getFirst().expectedCostGold())
        .isCloseTo(
            RefiningOptimal.calculate(RULES.stage(17), prices(), 3, 40_000).expectedGold(),
            Offset.offset(1e-6));
    var noLava = prices();
    noLava.remove("용암의 숨결");
    assertThat(
            RefiningPlan.optimal(
                RULES, options(16, 17, false, 0, 0), noLava, OptimalStageCache.NONE))
        .isNull();
  }

  private static RefiningPlan.Options options(
      int from, int to, boolean breath, int energyBp, int failures) {
    return new RefiningPlan.Options(from, to, breath, false, false, energyBp, failures);
  }

  /** 계산 검증 캡처와 같은 시각의 재료 1개 값(RefiningOptimalTest와 같음). */
  private static Map<String, Number> prices() {
    var prices = new HashMap<String, Number>();
    prices.put("운명의 파괴석 결정", 8.92);
    prices.put("운명의 수호석 결정", 1.77);
    prices.put("위대한 운명의 돌파석", 18);
    prices.put("상급 아비도스 융화 재료", 268);
    prices.put(RefiningPlan.SHARD, 0.091);
    prices.put("빙하의 숨결", 414);
    prices.put("용암의 숨결", 476);
    return prices;
  }

  private static BigDecimal amount(List<RefiningPlan.Quantity> quantities, String name) {
    return quantities.stream()
        .filter(quantity -> quantity.name().equals(name))
        .findFirst()
        .orElseThrow()
        .amount();
  }

  private static BigDecimal amountOrZero(List<RefiningPlan.Quantity> quantities, String name) {
    return quantities.stream()
        .filter(quantity -> quantity.name().equals(name))
        .map(RefiningPlan.Quantity::amount)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  private static VambraceRules vambraceRules() {
    try (var input =
        RefiningPlanArmGuardTest.class.getResourceAsStream("/refining/t4-vambrace-rules.json")) {
      return JsonMapper.builder().build().readValue(input, VambraceRules.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
