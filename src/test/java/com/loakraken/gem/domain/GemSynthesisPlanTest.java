package com.loakraken.gem.domain;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GemSynthesisPlanTest {
  @Test
  void 높은_레벨_재고부터_배정하고_부족분만_합성한다() {
    var plan =
        GemSynthesisPlan.calculate(
            7,
            10,
            1,
            List.of(
                new GemSynthesisPlan.OwnedGem(7, 1),
                new GemSynthesisPlan.OwnedGem(8, 2),
                new GemSynthesisPlan.OwnedGem(9, 2)));
    assertThat(plan.additionalBaseQuantity()).isEqualTo(2);
    assertThat(plan.synthesisCount()).isEqualTo(3);
    assertThat(plan.steps())
        .extracting(GemSynthesisPlan.Step::outputLevel)
        .containsExactly(8, 9, 10);
    assertThat(plan.steps())
        .allSatisfy(
            step -> {
              assertThat(step.inputQuantity()).isEqualTo(3);
              assertThat(step.outputQuantity()).isEqualTo(1);
            });
    assertThat(plan.inventory())
        .extracting(GemSynthesisPlan.InventoryUsage::usedQuantity)
        .containsExactly(1L, 2L, 2L, 0L);
  }

  @Test
  void 목표_보석이_충분하면_하위_재고는_소모하지_않는다() {
    var plan =
        GemSynthesisPlan.calculate(
            7,
            10,
            2,
            List.of(new GemSynthesisPlan.OwnedGem(7, 100), new GemSynthesisPlan.OwnedGem(10, 3)));
    assertThat(plan.ownedTargetUsed()).isEqualTo(2);
    assertThat(plan.newTargetQuantity()).isZero();
    assertThat(plan.additionalBaseQuantity()).isZero();
    assertThat(plan.synthesisCount()).isZero();
    assertThat(plan.steps()).isEmpty();
    assertThat(plan.inventory())
        .extracting(GemSynthesisPlan.InventoryUsage::remainingQuantity)
        .containsExactly(100L, 0L, 0L, 1L);
  }

  @Test
  void 최대_레벨차와_목표수량도_개별_보석_반복_없이_계산한다() {
    var plan = GemSynthesisPlan.calculate(1, 10, 1000, List.of());
    assertThat(plan.additionalBaseQuantity()).isEqualTo(19_683_000);
    assertThat(plan.synthesisCount()).isEqualTo(9_841_000);
    assertThat(plan.steps()).hasSize(9);
    assertThat(plan.inventory()).hasSize(10);
  }

  @Test
  void 작은_입력_전수에서_정방향_합성_탐색과_최소_추가량이_일치하고_수량을_보존한다() {
    for (int first = 0; first <= 5; first++) {
      for (int second = 0; second <= 5; second++) {
        for (int third = 0; third <= 2; third++) {
          for (int target = 1; target <= 3; target++) {
            var plan =
                GemSynthesisPlan.calculate(
                    1,
                    3,
                    target,
                    List.of(
                        new GemSynthesisPlan.OwnedGem(1, first),
                        new GemSynthesisPlan.OwnedGem(2, second),
                        new GemSynthesisPlan.OwnedGem(3, third)));
            int extra = 0;
            while (third + (second + (first + extra) / 3) / 3 < target) extra++;
            assertThat(plan.additionalBaseQuantity())
                .as("inventory=%s,%s,%s target=%s", first, second, third, target)
                .isEqualTo(extra);
            long[] available = new long[] {0, first + plan.additionalBaseQuantity(), second, third};
            for (var step : plan.steps()) {
              assertThat(available[step.inputLevel()]).isGreaterThanOrEqualTo(step.inputQuantity());
              available[step.inputLevel()] -= step.inputQuantity();
              available[step.outputLevel()] += step.outputQuantity();
            }
            available[3] -= target;
            for (var usage : plan.inventory()) {
              assertThat(available[usage.level()]).isEqualTo(usage.remainingQuantity());
              assertThat(usage.usedQuantity() + usage.remainingQuantity())
                  .isEqualTo(usage.providedQuantity());
            }
          }
        }
      }
    }
  }

  @Test
  void 범위_밖이나_중복_레벨과_잘못된_수량을_거절한다() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GemSynthesisPlan.calculate(7, 7, 1, List.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GemSynthesisPlan.calculate(0, 10, 1, List.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GemSynthesisPlan.calculate(7, 11, 1, List.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GemSynthesisPlan.calculate(7, 10, 0, List.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> GemSynthesisPlan.calculate(7, 10, 1001, List.of()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                GemSynthesisPlan.calculate(7, 10, 1, List.of(new GemSynthesisPlan.OwnedGem(6, 1))));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                GemSynthesisPlan.calculate(
                    7,
                    10,
                    1,
                    List.of(
                        new GemSynthesisPlan.OwnedGem(7, 1), new GemSynthesisPlan.OwnedGem(7, 2))));
    assertThatIllegalArgumentException().isThrownBy(() -> new GemSynthesisPlan.OwnedGem(7, -1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new GemSynthesisPlan.OwnedGem(7, 1_000_000_000L));
  }
}
