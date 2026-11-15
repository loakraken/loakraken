package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 연마 단계 후보가 없으면(스위치 끔) 캐시 키에 아무것도 붙이지 않는다: 배포 뒤 기존 키가 그대로라 캐시가 한꺼번에 비지 않는다(코드 리뷰 #951 P2). */
class SpecUpPolishSignatureTest {
  @Test
  void 연마_후보가_없으면_키에_붙는_값이_없다() {
    assertThat(SpecUpGoalService.polishSignature(List.of())).isEmpty();
  }

  @Test
  void 연마_후보가_있으면_구분자와_함께_붙는다() {
    var step =
        new SpecUpService.PolishStep(
            "POLISH:7", "POLISH:7:MM", "귀걸이", "a", "b", null, null, null, List.of(), List.of());
    assertThat(SpecUpGoalService.polishSignature(List.of(step))).startsWith(":pp");
  }
}
