package com.loakraken.refining.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.RefiningMaterialPriceService.Listing;
import com.loakraken.market.application.RefiningMaterialPriceService.Listings;
import com.loakraken.refining.domain.RefiningPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 여러 부위 합계(#764)는 화면 합계와 같은 규칙이다: 재료마다 모든 부위·단계 기대 수량(소수 둘째 자리 단계 값)을 더해 한 번 올리고, 가진 수량을 뺀 나머지를 통째
 * 매물로 산다. 재련 골드는 모든 단계 골드를 더해 한 번 올린다. 가진 재료는 계획을 바꾸지 않는다(PM 총괄 11-01 (a)).
 */
class RefiningPlanTotalTest {
  private static final Instant AT = Instant.parse("2026-11-01T02:00:00Z");

  @Test
  void 재료는_부위를_더해_한_번_올리고_가진_수량을_뺀_나머지를_통째_매물로_산다() {
    // 부위 A: 단계 둘, 부위 B: 단계 하나. 수호석 0.40 + 0.35 + 0.30 = 1.05 → 필요 2(한 번 올림, 단계마다 올리면 3)
    var a =
        solved(
            stage("12.30", q("운명의 수호석", "0.40"), q("운명의 파편", "1500.50")),
            stage("10.25", q("운명의 수호석", "0.35"), q("빙하의 숨결", "3.20")));
    var b = solved(stage("7.10", q("운명의 수호석", "0.30"), q("재봉술 : 업화 [11-14]", "0.60")));
    var listings =
        new Listings(
            AT,
            Map.of(
                "운명의 수호석", new Listing("운명의 수호석", 100, 1, new BigDecimal("32")),
                "운명의 파편", new Listing("운명의 파편", 1, 1000, new BigDecimal("92")),
                "빙하의 숨결", new Listing("빙하의 숨결", 1, 1, new BigDecimal("430"))));
    var owned =
        List.of(
            new RefiningPlanTotalRequest.Owned("운명의 파편", 600L),
            new RefiningPlanTotalRequest.Owned("빙하의 숨결", 10L),
            new RefiningPlanTotalRequest.Owned("운명의 수호석", 1L));

    var total =
        RefiningPlanTotalResponse.of(
            RefiningPlanRequest.Breath.MAX, List.of(a, b), owned, listings);

    var lines = total.materials();
    assertThat(lines)
        .extracting(RefiningPlanTotalResponse.MaterialLine::name)
        .containsExactly("운명의 수호석", "운명의 파편", "빙하의 숨결", "재봉술 : 업화 [11-14]");
    var stone = lines.get(0);
    assertThat(stone.expected()).isEqualByComparingTo("1.05");
    assertThat(stone.amount()).isEqualTo(2);
    assertThat(stone.used()).isEqualTo(1);
    assertThat(stone.missing()).isEqualTo(1);
    assertThat(stone.listings()).isEqualTo(1); // 묶음 100개 하나
    assertThat(stone.purchaseGold()).isEqualTo(32);
    var shard = lines.get(1);
    assertThat(shard.amount()).isEqualTo(1501);
    assertThat(shard.missing()).isEqualTo(901);
    assertThat(shard.listings()).isEqualTo(1); // 주머니 하나 = 1,000개
    assertThat(shard.listing().contentsPerItem()).isEqualTo(1000);
    var breath = lines.get(2);
    assertThat(breath.amount()).isEqualTo(4);
    assertThat(breath.used()).isEqualTo(4);
    assertThat(breath.missing()).isZero();
    assertThat(breath.listings()).isZero();
    assertThat(breath.purchaseGold()).isZero();
    var book = lines.get(3);
    assertThat(book.listing()).isNull();
    assertThat(book.purchaseGold()).isNull();
    assertThat(total.unpricedMaterials()).containsExactly("재봉술 : 업화 [11-14]");

    // 재련 골드 = ⌈12.30 + 10.25 + 7.10⌉ = ⌈29.65⌉ = 30(단계마다 올리면 13 + 11 + 8 = 32)
    assertThat(total.refineGold()).isEqualTo(30);
    assertThat(total.purchaseGold()).isEqualTo(32 + 92);
    assertThat(total.totalGold()).isEqualTo(30 + 32 + 92);
    // 가진 재료까지 시세 = 반올림(29.65 + 1.05 × 0.32 + 1500.50 × 0.092 + 3.20 × 430) = 반올림(1544.032) =
    // 1544(책은 시세 없음)
    assertThat(total.marketValueGold()).isEqualTo(1544);
    assertThat(total.expectedCostGold()).isNull();
    assertThat(total.pricesObservedAt()).isEqualTo(AT);
    assertThat(total.parts()).hasSize(2);
    assertThat(total.assumptions()).contains("보유 재료는 계획을 바꾸지 않고 구매량에서만 뺍니다.");
  }

  @Test
  void 시세_캐시가_없으면_수량은_주고_골드_칸은_null이다() {
    var a = solved(stage("12.30", q("운명의 수호석", "0.40")));
    var total =
        RefiningPlanTotalResponse.of(
            RefiningPlanRequest.Breath.NONE,
            List.of(a),
            List.of(new RefiningPlanTotalRequest.Owned("운명의 수호석", 5L)),
            null);
    var stone = total.materials().getFirst();
    assertThat(stone.amount()).isEqualTo(1);
    assertThat(stone.used()).isEqualTo(1);
    assertThat(stone.missing()).isZero();
    assertThat(stone.listing()).isNull();
    assertThat(total.refineGold()).isEqualTo(13);
    assertThat(total.purchaseGold()).isNull();
    assertThat(total.totalGold()).isNull();
    assertThat(total.marketValueGold()).isNull();
    assertThat(total.unpricedMaterials()).isEmpty();
    assertThat(total.pricesObservedAt()).isNull();
  }

  @Test
  void OPTIMAL_계획_기대_비용은_모든_부위_단계를_더해_한_번_올린다() {
    var a = optimal(solved(stage("1", q("운명의 수호석", "0.40"))), 100.2, 200.2);
    var b = optimal(solved(stage("1", q("운명의 수호석", "0.40"))), 300.2);
    var total =
        RefiningPlanTotalResponse.of(RefiningPlanRequest.Breath.OPTIMAL, List.of(a, b), null, null);
    // ⌈600.6⌉ = 601(부위마다 올리면 ⌈300.4⌉ + ⌈300.2⌉ = 602)
    assertThat(total.expectedCostGold()).isEqualTo(601);
  }

  @Test
  void 수량이_정수면_올리지_않는다() {
    assertThat(RefiningPlanTotalResponse.ceil(new BigDecimal("3.00"))).isEqualTo(3);
    assertThat(RefiningPlanTotalResponse.ceil(new BigDecimal("3.01"))).isEqualTo(4);
    assertThat(RefiningPlanTotalResponse.ceil(BigDecimal.ZERO)).isZero();
  }

  private static RefiningPlan.Quantity q(String name, String amount) {
    return new RefiningPlan.Quantity(name, new BigDecimal(amount));
  }

  private static RefiningPlan.StagePlan stage(String gold, RefiningPlan.Quantity... materials) {
    return new RefiningPlan.StagePlan(
        11,
        1000,
        BigDecimal.ONE,
        1,
        1,
        1,
        List.of(materials),
        List.of(materials),
        new BigDecimal(gold),
        0,
        null);
  }

  private static RefiningPlanTotalResponse.Solved solved(RefiningPlan.StagePlan... stages) {
    var plan = new RefiningPlan(List.of(stages), List.of(), List.of(), BigDecimal.ZERO, 0);
    return new RefiningPlanTotalResponse.Solved(null, plan, null);
  }

  private static RefiningPlanTotalResponse.Solved optimal(
      RefiningPlanTotalResponse.Solved solved, double... costs) {
    var stages = new java.util.ArrayList<RefiningPlan.OptimalStage>();
    for (var cost : costs) stages.add(new RefiningPlan.OptimalStage(List.of(), 0, 0, cost));
    return new RefiningPlanTotalResponse.Solved(null, solved.plan(), stages);
  }
}
