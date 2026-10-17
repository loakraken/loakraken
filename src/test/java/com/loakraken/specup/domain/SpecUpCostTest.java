package com.loakraken.specup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 거래소 판매 수수료 5%의 '1골드 미만 올림' 경계(계산 검증 P3). */
class SpecUpCostTest {
  @Test
  void 판매_수수료는_5퍼센트를_1골드_미만_올림한다() {
    assertThat(SpecUpCost.exchangeSaleFee(400_000)).isEqualTo(20_000);
    assertThat(SpecUpCost.exchangeSaleFee(400_001)).isEqualTo(20_001);
    assertThat(SpecUpCost.exchangeSaleFee(19)).isEqualTo(1);
    assertThat(SpecUpCost.exchangeSaleFee(0)).isZero();
    // 판매 기대가 = 최저가 − 수수료: 400,001 → 380,000.
    assertThat(SpecUpCost.gem(1_000_000, 400_001L, false).amount()).isEqualTo(620_000);
  }
}
