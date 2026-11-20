package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 일별 시세 저장 가격(보안 11-16 (b)): 자리 수와 scale을 반올림 전에 압축된 값으로 재서, 지수 표기 값(1e100000000·1e-100000000)을
 * setScale로 펼치지 않고 바로 건너뛴다. 보통 값은 소수 넷째 자리로 반올림한다.
 */
class PostgresMarketDailyStorePriceTest {
  @Test
  @Timeout(5)
  void 상한_밖_가격은_펼치지_않고_건너뛴다() {
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("1e100000000"))).isNull();
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("1e-100000000"))).isNull();
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("1234567890123"))).isNull();
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("-1"))).isNull();
    assertThat(PostgresMarketDailyStore.price(null)).isNull();
  }

  @Test
  void 보통_가격은_소수_넷째_자리로_반올림한다() {
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("175.5")))
        .isEqualByComparingTo("175.5");
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("123.45678")))
        .isEqualByComparingTo("123.4568");
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("1E+3"))).isEqualByComparingTo("1000");
    // 반올림으로 정수 13자리가 되면 건너뛴다.
    assertThat(PostgresMarketDailyStore.price(new BigDecimal("999999999999.99995"))).isNull();
  }
}
