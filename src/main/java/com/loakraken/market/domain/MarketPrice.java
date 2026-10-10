package com.loakraken.market.domain;

import java.math.BigDecimal;

public record MarketPrice(
    long id,
    String name,
    String grade,
    String icon,
    int bundleCount,
    Integer tradeRemainCount,
    BigDecimal yDayAvgPrice,
    BigDecimal recentPrice,
    BigDecimal currentMinPrice) {
  public MarketPrice {
    if (id < 1
        || name == null
        || name.isBlank()
        || bundleCount < 1
        || (tradeRemainCount != null && tradeRemainCount < 0)
        || negative(yDayAvgPrice)
        || negative(recentPrice)
        || negative(currentMinPrice)) throw new IllegalArgumentException("잘못된 거래소 가격");
  }

  private static boolean negative(BigDecimal value) {
    return value != null && value.signum() < 0;
  }
}
