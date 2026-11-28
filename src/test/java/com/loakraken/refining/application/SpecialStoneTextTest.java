package com.loakraken.refining.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 화면에 그대로 나가는 이유 문장에는 '출처'를 쓰지 않는다(웹 규칙 A13, 프론트2 11-22). */
class SpecialStoneTextTest {
  @Test
  void 출처_없는_단계_이유_문장에_출처라는_말이_없다() {
    assertThat(SpecialStoneValueService.UNSOURCED_REASON).doesNotContain("출처");
    assertThat(SpecialStoneValueService.PRICE_MISSING_REASON).doesNotContain("출처");
  }
}
