package com.loakraken.market.application;

public record MarketSearch(
    int categoryCode,
    String characterClass,
    Integer itemTier,
    String itemGrade,
    String itemName,
    int pageNo,
    Sort sort,
    SortCondition sortCondition) {
  public enum Sort {
    GRADE,
    YDAY_AVG_PRICE,
    RECENT_PRICE,
    CURRENT_MIN_PRICE
  }

  public enum SortCondition {
    ASC,
    DESC
  }
}
