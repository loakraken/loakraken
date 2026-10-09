package com.loakraken.stats.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatsConditionsTest {
  @Test
  void 범위_값은_소수_둘째_자리로_반올림해_같은_캐시_키가_된다() {
    var first = conditions("1680.834999", "1700.00", "2345.6", null);
    var second = conditions("1680.83", "1700", "2345.60000001", null);
    assertThat(first.cacheKey()).isEqualTo(second.cacheKey());
    assertThat(first.itemLevelMin()).isEqualByComparingTo("1680.83");
    assertThat(first.itemLevelMin().toPlainString()).isEqualTo("1680.83");
    assertThat(first.itemLevelMax().toPlainString()).isEqualTo("1700");
    assertThat(first.combatPowerMin().toPlainString()).isEqualTo("2345.6");
    assertThat(conditions("1680.835", null, null, null).itemLevelMin().toPlainString())
        .isEqualTo("1680.84");
  }

  @Test
  void 값이_없으면_null이고_기본_최소_레벨을_쓴다() {
    var defaults = conditions(null, null, null, null);
    assertThat(defaults.itemLevelMin().toPlainString()).isEqualTo("1640");
    assertThat(defaults.itemLevelMax()).isNull();
    assertThat(StatsConditions.strip(null)).isNull();
  }

  private static StatsConditions conditions(
      String itemMin, String itemMax, String cpMin, String cpMax) {
    return new StatsConditions(
        null,
        null,
        null,
        30,
        decimal(itemMin),
        decimal(itemMax),
        decimal(cpMin),
        decimal(cpMax),
        false);
  }

  private static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }
}
