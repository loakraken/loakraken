package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefiningPlanTest {
  private static final RefiningRules.Breath BREATH = new RefiningRules.Breath("빙하의 숨결", 20, 50);
  private static final RefiningRules.Book BOOK = new RefiningRules.Book("재봉술 : 업화 [11-14]", 1000);

  @Test
  void 장인의_기운은_성공률을_2_15로_나눠_0_01퍼센트포인트에서_버린다() {
    // 실측 규칙(계산 검증 10-25), 게임 화면 캡처와 같음: 3.000% → +1.39%, 3.150% → +1.46%.
    assertThat(RefiningPlan.artisanGainHundredths(300)).isEqualTo(139);
    assertThat(RefiningPlan.artisanGainHundredths(315)).isEqualTo(146);
    // × 0.465 근사와 갈리는 경계(정확히 나누어떨어짐): 0.860% → 0.40%, 6.450% → 3.00%, 8.600% → 4.00%.
    assertThat(RefiningPlan.artisanGainHundredths(86)).isEqualTo(40);
    assertThat(RefiningPlan.artisanGainHundredths(645)).isEqualTo(300);
    assertThat(RefiningPlan.artisanGainHundredths(860)).isEqualTo(400);
    // 부동소수 오차로 경계 바로 밑이 된 성공률도 같은 값(645 = 430 × 1.5).
    assertThat(RefiningPlan.artisanGainHundredths(430 * 1.5)).isEqualTo(300);
    assertThat(RefiningPlan.artisanGainHundredths(644.9999999999999)).isEqualTo(300);
    // 6.3% 실패: +2.93%라 97.07%에서 정확히 100% → 다음 시도 확정 성공(× 0.465면 2.92%로 99.99%).
    assertThat(9_707 + RefiningPlan.artisanGainHundredths(630)).isEqualTo(10_000);
  }

  @Test
  void 실패_보너스와_장인의_기운으로_시도_분포를_계산한다() {
    // 50% → 55% → 60% → 65%, 장인의 기운(÷ 2.15, 0.01%p 버림) 23.25 → 48.83 → 76.73 → 106.96%라 5번째가 확정이다.
    var plan = plan(stage(11, 5000, null, null), options(10, 11, false, false, 0, 0));
    var stage = plan.stages().getFirst();
    assertThat(stage.firstAttemptSuccessRateBp()).isEqualTo(5000);
    assertThat(stage.maxAttempts()).isEqualTo(5);
    assertThat(stage.expectedAttempts()).isEqualByComparingTo("1.85");
    assertThat(stage.medianAttempts()).isEqualTo(1);
    assertThat(stage.p90Attempts()).isEqualTo(3);
    assertThat(amount(stage.expectedMaterials(), "운명의 수호석")).isEqualByComparingTo("184.65");
    assertThat(amount(stage.worstCaseMaterials(), "운명의 수호석")).isEqualByComparingTo("500");
    assertThat(amount(stage.expectedMaterials(), RefiningPlan.SHARD))
        .isEqualByComparingTo("1846.50");
    assertThat(stage.expectedGold()).isEqualByComparingTo("18.47");
    assertThat(stage.worstCaseGold()).isEqualTo(50);
  }

  @Test
  void 숨결과_책은_성공률에_더하고_확정_시도에서는_쓰지_않는다() {
    var plan = plan(stage(11, 1000, BREATH, BOOK), options(10, 11, true, true, 0, 0));
    var stage = plan.stages().getFirst();
    assertThat(stage.firstAttemptSuccessRateBp()).isEqualTo(3000);
    assertThat(amount(stage.worstCaseMaterials(), "빙하의 숨결"))
        .isEqualByComparingTo(BigDecimal.valueOf(20L * (stage.maxAttempts() - 1)));
    assertThat(amount(stage.worstCaseMaterials(), "재봉술 : 업화 [11-14]"))
        .isEqualByComparingTo(BigDecimal.valueOf(stage.maxAttempts() - 1));
    var noAux = plan(stage(11, 1000, BREATH, BOOK), options(10, 11, false, false, 0, 0));
    assertThat(noAux.stages().getFirst().expectedMaterials())
        .extracting(RefiningPlan.Quantity::name)
        .doesNotContain("빙하의 숨결", "재봉술 : 업화 [11-14]");
    assertThat(noAux.stages().getFirst().maxAttempts()).isGreaterThan(stage.maxAttempts());
  }

  @Test
  void 현재_장인의_기운이_가득이면_첫_시도가_확정이다() {
    var plan = plan(stage(11, 1000, BREATH, null), options(10, 11, true, false, 10_000, 0));
    var stage = plan.stages().getFirst();
    assertThat(stage.maxAttempts()).isEqualTo(1);
    assertThat(stage.firstAttemptSuccessRateBp()).isEqualTo(10_000);
    assertThat(stage.expectedAttempts()).isEqualByComparingTo("1");
    assertThat(amount(stage.expectedMaterials(), "빙하의 숨결")).isEqualByComparingTo("0");
  }

  @Test
  void 실패_보너스는_10회까지만_쌓이고_시작_상태는_첫_단계에만_적용한다() {
    var plan =
        plan(
            List.of(stage(11, 100, null, null), stage(12, 100, null, null)),
            options(10, 12, false, false, 0, 10));
    assertThat(plan.stages().get(0).firstAttemptSuccessRateBp()).isEqualTo(200);
    assertThat(plan.stages().get(1).firstAttemptSuccessRateBp()).isEqualTo(100);
    assertThat(plan.stages().get(0).maxAttempts()).isLessThan(plan.stages().get(1).maxAttempts());
  }

  @Test
  void 합계는_단계별_값을_더하고_성장_지원은_업화_18단계_이하_골드만_줄인다() {
    var track = track(List.of(stage(18, 5000, null, null), stage(19, 5000, null, null)));
    var normal =
        RefiningPlan.calculate(
            track, RefiningRules.Grade.UPHWA, options(17, 19, false, false, 0, 0));
    var supported =
        RefiningPlan.calculate(
            track,
            RefiningRules.Grade.UPHWA,
            new RefiningPlan.Options(17, 19, false, false, true, 0, 0));
    var jeonyul =
        RefiningPlan.calculate(
            track,
            RefiningRules.Grade.JEONYUL,
            new RefiningPlan.Options(17, 19, false, false, true, 0, 0));
    assertThat(normal.stages()).hasSize(2);
    assertThat(amount(normal.expectedMaterials(), "운명의 수호석")).isEqualByComparingTo("369.30");
    assertThat(supported.stages().get(0).worstCaseGold()).isEqualTo(40);
    assertThat(supported.stages().get(1).worstCaseGold()).isEqualTo(50);
    assertThat(jeonyul.worstCaseGold()).isEqualTo(normal.worstCaseGold());
    assertThat(normal.expectedGold())
        .isEqualByComparingTo(
            normal.stages().get(0).expectedGold().add(normal.stages().get(1).expectedGold()));
  }

  @Test
  void 데이터_범위_밖_단계나_잘못된_시작_상태는_거절한다() {
    var track = track(List.of(stage(11, 1000, null, null)));
    assertThatThrownBy(
            () ->
                RefiningPlan.calculate(
                    track, RefiningRules.Grade.UPHWA, options(10, 12, false, false, 0, 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> options(11, 11, false, false, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> options(10, 11, false, false, 10_001, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> options(10, 11, false, false, 0, 11))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private RefiningPlan plan(RefiningRules.Stage stage, RefiningPlan.Options options) {
    return plan(List.of(stage), options);
  }

  private RefiningPlan plan(List<RefiningRules.Stage> stages, RefiningPlan.Options options) {
    return RefiningPlan.calculate(track(stages), RefiningRules.Grade.UPHWA, options);
  }

  private RefiningRules.Track track(List<RefiningRules.Stage> stages) {
    return new RefiningRules.Track(
        RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR, stages);
  }

  private RefiningRules.Stage stage(
      int target, int baseRateBp, RefiningRules.Breath breath, RefiningRules.Book book) {
    return new RefiningRules.Stage(
        target,
        baseRateBp,
        List.of(new RefiningRules.Material("운명의 수호석", 100)),
        1000,
        10,
        breath,
        book);
  }

  private RefiningPlan.Options options(
      int from, int to, boolean breath, boolean book, int artisanBp, int failures) {
    return new RefiningPlan.Options(from, to, breath, book, false, artisanBp, failures);
  }

  private BigDecimal amount(List<RefiningPlan.Quantity> quantities, String name) {
    return quantities.stream()
        .filter(quantity -> quantity.name().equals(name))
        .findFirst()
        .orElseThrow()
        .amount();
  }
}
