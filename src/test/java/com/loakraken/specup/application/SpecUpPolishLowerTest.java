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
  void 셋째_줄_고정값은_같은_옵션의_등급을_낮추거나_버리면_고르지_않는다() {
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHAM", "POLISH:7:HHAH")).isTrue();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHAH", "POLISH:7:HHAM")).isFalse();
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HH", "POLISH:7:HHAM")).isTrue();
    // 다른 고정값 옵션(공격력 → 무기 공격력)은 등급으로 견주지 않는다.
    assertThat(SpecUpGoalService.lowersPolish("POLISH:7:HHWL", "POLISH:7:HHAH")).isFalse();
  }
}
