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
    BigDecimal currentMinPrice) {}
