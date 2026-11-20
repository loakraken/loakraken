package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.specup.domain.SpecUpCost;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 추천 순서 레벨 점프(계산 검증 11-20 22:21, 비교 기준과 같음): 칸마다 목표 단계들과 여러 단계를 한 번에 올리는 비용(단계 비용의 합). */
class SpecUpPathJumpTest {
  @Test
  void 점프면_끝까지_모든_레벨이고_아니면_다음_한_단계다() {
    assertThat(SpecUpGoalService.targets(23, 25, true)).containsExactly(24, 25);
    assertThat(SpecUpGoalService.targets(1, 10, true)).hasSize(9).startsWith(2).endsWith(10);
    assertThat(SpecUpGoalService.targets(23, 25, false)).containsExactly(24);
    assertThat(SpecUpGoalService.targets(25, 25, true)).isEmpty();
    assertThat(SpecUpGoalService.targets(25, 25, false)).isEmpty();
  }

  /** 계산 검증 11-21 P3: 같은 칸의 목표끼리는 낮은 목표가 먼저(문자열로는 ':10'이 ':9' 앞). */
  @Test
  void 같은_칸의_목표는_낮은_목표가_먼저다() {
    assertThat(SpecUpGoalService.candidateOrder("GEM_LEVEL:0:9", "GEM_LEVEL:0:10")).isNegative();
    assertThat(SpecUpGoalService.candidateOrder("EQUIPMENT_REFINE:3:9", "EQUIPMENT_REFINE:3:25"))
        .isNegative();
    assertThat(SpecUpGoalService.candidateOrder("KARMA_LEVEL:9", "KARMA_LEVEL:10")).isNegative();
    // 칸이 다르면 칸 순서 그대로.
    assertThat(SpecUpGoalService.candidateOrder("GEM_LEVEL:1:2", "GEM_LEVEL:0:10")).isPositive();
  }

  /**
   * 계산 시간(계산 검증 11-21): 한 칸에서 비용을 모르는 목표는 가장 높은 것만 잰다. 비용 모름 줄은 오름폭 순이라 높은 목표가 이긴다. 비용을 아는 목표와 점프가
   * 없는 종류는 줄이지 않는다.
   */
  @Test
  void 비용_모름_점프는_칸마다_가장_높은_목표만_잰다() {
    var known = java.util.Set.of("GEM_LEVEL:0:5", "GEM_LEVEL:0:6");
    var options =
        List.of(
            option("GEM_LEVEL:0", "GEM_LEVEL:0:5"),
            option("GEM_LEVEL:0", "GEM_LEVEL:0:6"),
            option("GEM_LEVEL:0", "GEM_LEVEL:0:7"),
            option("GEM_LEVEL:0", "GEM_LEVEL:0:10"),
            option("EQUIPMENT_REFINE:2", "EQUIPMENT_REFINE:2:20"),
            option("EQUIPMENT_REFINE:2", "EQUIPMENT_REFINE:2:25"),
            option("KARMA_LEVEL", "KARMA_LEVEL:20"),
            option("KARMA_LEVEL", "KARMA_LEVEL:30"));
    var top =
        SpecUpGoalService.topUnknownTargets(
            options, option -> known.contains(option.id()) ? 1_000L : null);
    assertThat(top)
        .containsEntry("GEM_LEVEL:0", 10)
        .containsEntry("EQUIPMENT_REFINE:2", 25)
        .doesNotContainKey("KARMA_LEVEL");
    assertThat(options.stream().filter(option -> SpecUpGoalService.belowTopUnknown(option, top)))
        .extracting(SpecUpGoalService.Option::id)
        // 비용 모름 7(10보다 낮음)·20(25보다 낮음)만 뺀다. 비용 있는 5·6은 belowTopUnknown이 참이어도 루프에서 비용이 있어 재므로
        // 여기서는 목표 비교만 본다.
        .contains("GEM_LEVEL:0:7", "EQUIPMENT_REFINE:2:20")
        .doesNotContain("GEM_LEVEL:0:10", "EQUIPMENT_REFINE:2:25", "KARMA_LEVEL:20");
  }

  private static SpecUpGoalService.Option option(String slot, String id) {
    return new SpecUpGoalService.Option(
        slot,
        id,
        "대상",
        null,
        null,
        null,
        null,
        null,
        null,
        SpecUpCost.PRICE_PENDING,
        null,
        null,
        null);
  }

  @Test
  void 상급_재련은_점프면_10의_배수만이다() {
    assertThat(SpecUpGoalService.advancedTargets(13, true)).containsExactly(20, 30, 40);
    assertThat(SpecUpGoalService.advancedTargets(0, true)).containsExactly(10, 20, 30, 40);
    assertThat(SpecUpGoalService.advancedTargets(30, true)).containsExactly(40);
    assertThat(SpecUpGoalService.advancedTargets(40, true)).isEmpty();
    assertThat(SpecUpGoalService.advancedTargets(13, false)).containsExactly(14);
  }

  @Test
  void 점프_비용은_단계_비용의_합이고_모르는_단계가_있으면_모른다() {
    var early = Instant.parse("2026-11-20T10:00:00Z");
    var late = Instant.parse("2026-11-20T11:00:00Z");
    var one = SpecUpCost.refineExpected(100_000).withPrice(late, false);
    var two = SpecUpCost.refineExpected(250_000).withPrice(early, true);
    var sum = SpecUpGoalService.rangeCost(List.of(one, two));
    assertThat(sum.amount()).isEqualTo(350_000L);
    assertThat(sum.exact()).isEqualTo(350_000d);
    assertThat(sum.code()).isEqualTo("REFINE_EXPECTED");
    assertThat(sum.priceObservedAt()).isEqualTo(early);
    assertThat(sum.stale()).isTrue();
    // 한 단계면 그 비용 그대로(점프 전과 같은 값).
    assertThat(SpecUpGoalService.rangeCost(List.of(one))).isSameAs(one);
    // 모르는 단계가 있으면 그 단계의 비용(모름)을 준다.
    assertThat(SpecUpGoalService.rangeCost(List.of(one, SpecUpCost.REFINE_PRICE_PENDING, two)))
        .isSameAs(SpecUpCost.REFINE_PRICE_PENDING);
    // 각인: 한 레벨 비용을 레벨 수만큼(각인서 5권 × 레벨 수, 같은 code).
    var book = SpecUpCost.engravingBooks(2_000);
    var three = SpecUpGoalService.rangeCost(List.of(book, book, book));
    assertThat(three.amount()).isEqualTo(SpecUpCost.engravingBooks(2_000, 3).amount());
    assertThat(three.code()).isEqualTo(book.code());
  }
}
