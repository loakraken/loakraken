package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 현재 성공 확률(숨결·책을 올리기 전 게임 화면 값)에서 실패 횟수 정하기(PM 총괄 11-02): 기본 × (1 + 0.1k)와 같은 k(0~10). 장인의 기운만으로는
 * 숨결·책을 쓴 이력에 따라 실패 횟수가 3~6회까지 갈려 쓰지 않는다(계산 검증 11-02).
 */
class RefiningSuccessRateTest {
  @Test
  void 기본_확률에_실패마다_10퍼센트씩_붙은_값에서_실패_횟수를_찾는다() {
    // 업화 →17: 기본 3%.
    assertThat(RefiningPlan.failuresFromRate(300, 300)).isZero();
    assertThat(RefiningPlan.failuresFromRate(300, 330)).isEqualTo(1);
    assertThat(RefiningPlan.failuresFromRate(300, 390)).isEqualTo(3);
    // 10회 이상 실패해도 확률은 기본 × 2에서 멈춘다.
    assertThat(RefiningPlan.failuresFromRate(300, 600)).isEqualTo(10);
    // 완갑 →1: 기본 15%.
    assertThat(RefiningPlan.failuresFromRate(1500, 1650)).isEqualTo(1);
    assertThat(RefiningPlan.failuresFromRate(1500, 3000)).isEqualTo(10);
    // 0.5% →25.
    assertThat(RefiningPlan.failuresFromRate(50, 55)).isEqualTo(1);
    assertThat(RefiningPlan.failuresFromRate(50, 100)).isEqualTo(10);
  }

  @Test
  void 숨결_책을_더한_값이나_다른_단계_값은_찾지_않는다() {
    // 게임 화면은 올린 숨결을 더해 보인다(계산 검증 캡처: 3.000%가 숨결을 올리며 3.720%).
    assertThat(RefiningPlan.failuresFromRate(300, 372)).isEqualTo(-1);
    assertThat(RefiningPlan.failuresFromRate(300, 315)).isEqualTo(-1);
    assertThat(RefiningPlan.failuresFromRate(300, 299)).isEqualTo(-1);
    assertThat(RefiningPlan.failuresFromRate(300, 0)).isEqualTo(-1);
    assertThat(RefiningPlan.failuresFromRate(300, 630)).isEqualTo(-1);
  }

  @Test
  void 모든_단계_기본_확률에서_실패_횟수와_성공_확률이_서로_되돌아온다() {
    // 업화·전율 무기·방어구 60단계와 완갑 25단계: k → 기본 × (1 + 0.1k) → k. 값은 0.01% 단위 정수라 화면 소수 둘째 자리로 그대로 적힌다.
    var bases = new ArrayList<Double>();
    for (var track : refiningRules().tracks())
      for (var stage : track.stages()) bases.add((double) stage.baseRateBp());
    for (var stage : vambraceRules().stages()) bases.add(stage.baseRateBp());
    assertThat(bases).hasSize(85);
    for (double base : bases)
      for (int k = 0; k <= 10; k++) {
        double rate = base * (1 + 0.1 * k);
        long rateBp = Math.round(rate);
        assertThat(Math.abs(rate - rateBp)).as(base + " " + k).isLessThan(1e-6);
        assertThat(RefiningPlan.failuresFromRate(base, (int) rateBp))
            .as(base + " " + k)
            .isEqualTo(k);
      }
  }

  @Test
  void 실패_횟수만큼_쌓일_수_있는_장인의_기운_범위는_숨결_책_없이부터_매_시도_최대까지다() {
    // 업화 방어구 →17: 기본 3%, 숨결 최대 + 책 = +6%. 1회 = 1.39(3% ÷ 2.15) ~ 4.18(9% ÷ 2.15), 2회 2.92 ~ 8.50.
    assertThat(RefiningPlan.energyRange(300, 600, 0)).containsExactly(0, 0);
    assertThat(RefiningPlan.energyRange(300, 600, 1)).containsExactly(139, 418);
    assertThat(RefiningPlan.energyRange(300, 600, 2)).containsExactly(292, 850);
    // 10회면 더 실패했을 수 있어 위는 100%.
    assertThat(RefiningPlan.energyRange(300, 600, 10)).containsExactly(2_020, 10_000);
    // 완갑 →1(기본 15%, 숨결 둘 최대 +15%): 계산 검증 판별 표본. 숨결 없이 8회 = 75.31%(× 0.465 근사면 다른 값), 7회 위는 100%에서
    // 멈춤.
    assertThat(RefiningPlan.energyRange(1500, 1500, 8)).containsExactly(7_531, 10_000);
    assertThat(RefiningPlan.energyRange(1500, 1500, 7)[0]).isEqualTo(6_345);
    assertThat(RefiningPlan.energyRange(1500, 1500, 1)).containsExactly(697, 1_395);
    // 완갑 1~5단계 10회: 숨결 없이도 10회째에 100%를 넘어 100%에서 멈춘다 → 아래도 100%(계산 검증 #830 P1, 101.81%가 아님).
    assertThat(RefiningPlan.energyRange(1500, 1500, 10)).containsExactly(10_000, 10_000);
    // 0.5% →25(숨결 최대 + 책 = +1%): 1회 0.23 ~ 0.69.
    assertThat(RefiningPlan.energyRange(50, 100, 1)).containsExactly(23, 69);
  }

  @Test
  void 같은_성공_확률로_정한_실패_횟수는_요청_값과_같은_계획이다() {
    var track = refiningRules().track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    int failures = RefiningPlan.failuresFromRate(track.stage(17).baseRateBp(), 360);
    assertThat(failures).isEqualTo(2);
    var plan =
        RefiningPlan.calculate(
            track,
            RefiningRules.Grade.UPHWA,
            new RefiningPlan.Options(16, 17, false, false, true, 2_000, failures));
    assertThat(plan.stages().getFirst().firstAttemptSuccessRateBp()).isEqualTo(360);
    List<RefiningPlan.StagePlan> stages = plan.stages();
    assertThat(stages).hasSize(1);
  }

  private static RefiningRules refiningRules() {
    try (var input =
        RefiningSuccessRateTest.class.getResourceAsStream("/refining/t4-refining-rules.json")) {
      return JsonMapper.builder().build().readValue(input, RefiningRules.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static VambraceRules vambraceRules() {
    try (var input =
        RefiningSuccessRateTest.class.getResourceAsStream("/refining/t4-vambrace-rules.json")) {
      return JsonMapper.builder().build().readValue(input, VambraceRules.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
