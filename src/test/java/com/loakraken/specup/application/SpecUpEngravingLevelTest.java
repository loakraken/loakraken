package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 등급별 각인 최대 레벨: 크라켄 점수 계수표에 있는 칸만 올리는 줄을 만든다(전설 4 계수가 없어 Δ가 음수가 되던 줄, 코드 리뷰 10-25). */
class SpecUpEngravingLevelTest {
  @Test
  void 유물은_4_영웅과_전설은_3까지이고_그_밖은_올리지_않는다() {
    assertThat(SpecUpService.engravingMaxLevel("유물")).isEqualTo(4);
    assertThat(SpecUpService.engravingMaxLevel("전설")).isEqualTo(3);
    assertThat(SpecUpService.engravingMaxLevel("영웅")).isEqualTo(3);
    assertThat(SpecUpService.engravingMaxLevel("고대")).isZero();
    assertThat(SpecUpService.engravingMaxLevel(null)).isZero();
  }
}
