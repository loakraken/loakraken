package com.loakraken.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** percentile_cont(double) 결과의 반올림. 정확한 값이 x.xx5면 double 잡음과 상관없이 올림이다. */
class PercentileRoundingTest {
  @Test
  void double_잡음으로_x_xx4999가_돼도_정확한_x_xx5로_보고_올린다() {
    // L5 대조에서 나온 값: 정확한 1853.265가 double로 1853.2649999999999.
    assertThat(PostgresStatsRepository.percentile("1853.2649999999999"))
        .isEqualByComparingTo("1853.27");
    assertThat(PostgresStatsRepository.percentile(Double.toString((1853.26 + 1853.27) / 2)))
        .isEqualByComparingTo("1853.27");
    assertThat(PostgresStatsRepository.percentile("1853.2650000000001"))
        .isEqualByComparingTo("1853.27");
    // 판정 예: (1,851.28 + 1,855.25) / 2 = 1,853.265 → 1,853.27.
    assertThat(PostgresStatsRepository.percentile(Double.toString((1851.28 + 1855.25) / 2)))
        .isEqualByComparingTo("1853.27");
    // JDBC가 double을 정확한 이진 전개 BigDecimal로 줘도 같다(요약·서버 중앙값·p90).
    assertThat(
            PostgresStatsRepository.percentile(new java.math.BigDecimal((1851.28 + 1855.25) / 2)))
        .isEqualByComparingTo("1853.27");
    assertThat(PostgresStatsRepository.percentile((java.math.BigDecimal) null)).isNull();
  }

  @Test
  void 잡음이_없는_값은_그대로_소수_둘째_자리다() {
    assertThat(PostgresStatsRepository.percentile("4029.0")).isEqualByComparingTo("4029.00");
    assertThat(PostgresStatsRepository.percentile("3116.8333333333335"))
        .isEqualByComparingTo("3116.83");
    assertThat(PostgresStatsRepository.percentile("1853.2640000001"))
        .isEqualByComparingTo("1853.26");
    assertThat(PostgresStatsRepository.percentile("99999.994999999"))
        .isEqualByComparingTo("99999.99");
  }
}
