package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 같은 장신구를 다시 바꾸는 연마 단계는 앞 단계보다 등급을 낮추지 않는다(비교 기준은 지금 등급 이상만 냄, 계산 검증 11-15 #951 HOLD: MM 다음 LH가 골라져
 * 무공%가 중에서 하로 내려갔다).
 */
class SpecUpPolishLowerTest {
  @Test
  void 주옵션_하나라도_낮추면_고르지_않는다() {
    assertThat(SpecUpGoalService.lowersPolish("POLISH:8:LH", "POLISH:8:MM")).isTrue();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:8:MH", "POLISH:8:MM")).isFalse();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:8:HH", "POLISH:8:MM")).isFalse();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:8:NH", "POLISH:8:LM")).isTrue();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:8:LM", null)).isFalse();
  }

  @Test
  void 셋째_줄_고정값이_있으면_그보다_높은_등급의_고정값만_다음_후보다() {
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHAM", "POLISH:7:HHAH")).isTrue();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHAH", "POLISH:7:HHAM")).isFalse();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HH", "POLISH:7:HHAM")).isTrue();
    // 옵션이 달라도 등급으로 견준다(비교 기준은 두 옵션 모두 지금보다 높은 등급만 냄, 코드 리뷰 #951 P3).
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHWL", "POLISH:7:HHAH")).isTrue();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHWM", "POLISH:7:HHAM")).isTrue();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHWH", "POLISH:7:HHAM")).isFalse();
    // 상·상(셋째 줄 없음) 다음 셋째 줄 상은 낮춤이 아니다.
    assertThat(SpecUpGoalService.lowersPolish("POLISH:9:HHAH", "POLISH:9:HH")).isFalse();
  }
}
