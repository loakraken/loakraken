package com.loakraken.market.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaterialPurchaseCostTest {
  @ParameterizedTest
  @CsvSource({
    "0,100,0,0,0",
    "1,100,1,100,99",
    "100,100,1,100,0",
    "101,100,2,200,99",
    "999999999,1,999999999,999999999,0",
    "999999999,2147483647,1,2147483647,1147483648"
  })
  void 정수_경계에서도_부족량을_충족하는_최소_묶음을_구한다(
      long missing, int bundle, long count, long quantity, long surplus) {
    var result = MaterialPurchaseCost.calculate(missing, bundle, new BigDecimal("12.3456"));
    assertThat(result.bundleQuantity()).isEqualTo(count);
    assertThat(result.purchaseQuantity()).isEqualTo(quantity);
    assertThat(result.surplusQuantity()).isEqualTo(surplus);
    assertThat(result.costGold())
        .isEqualByComparingTo(new BigDecimal("12.3456").multiply(BigDecimal.valueOf(count)));
  }

  @Test
  void 소수_금액을_중간_반올림하지_않는다() {
    assertThat(MaterialPurchaseCost.calculate(201, 100, new BigDecimal("0.123456789")).costGold())
        .isEqualByComparingTo("0.370370367");
  }

  @Test
  void 음수와_범위초과_수량_잘못된_묶음_미제공과_0원은_계산하지_않는다() {
    for (long missing : new long[] {-1, 1_000_000_000L, Long.MAX_VALUE})
      assertThatIllegalArgumentException()
          .isThrownBy(() -> MaterialPurchaseCost.calculate(missing, 100, BigDecimal.ONE));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> MaterialPurchaseCost.calculate(1, 0, BigDecimal.ONE));
    for (var price : new BigDecimal[] {null, BigDecimal.ZERO, BigDecimal.ONE.negate()})
      assertThatIllegalArgumentException()
          .isThrownBy(() -> MaterialPurchaseCost.calculate(1, 100, price));
  }
}
