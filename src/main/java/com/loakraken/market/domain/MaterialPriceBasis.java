package com.loakraken.market.domain;

import java.math.BigDecimal;

public enum MaterialPriceBasis {
  CURRENT_MIN_PRICE,
  RECENT_PRICE,
  YDAY_AVG_PRICE;

  public BigDecimal select(MarketPrice price) {
    return switch (this) {
      case CURRENT_MIN_PRICE -> price.currentMinPrice();
      case RECENT_PRICE -> price.recentPrice();
      case YDAY_AVG_PRICE -> price.yDayAvgPrice();
    };
  }
}
