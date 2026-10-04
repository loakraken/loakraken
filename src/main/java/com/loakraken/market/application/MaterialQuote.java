package com.loakraken.market.application;

import com.loakraken.market.domain.MaterialPriceBasis;
import com.loakraken.market.domain.MaterialPurchaseCost;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MaterialQuote(
    MaterialPriceBasis priceBasis,
    Instant calculatedAt,
    boolean complete,
    BigDecimal knownCostGold,
    BigDecimal totalCostGold,
    List<Line> items) {
  public static final String RULE_VERSION = "material-purchase-v1";

  public MaterialQuote {
    items = List.copyOf(items);
  }

  public enum Status {
    PRICED,
    COVERED_BY_INVENTORY,
    ITEM_NOT_IN_PAGE,
    AMBIGUOUS_ITEM,
    TRADE_RESTRICTED_ITEM,
    PRICE_UNAVAILABLE,
    ZERO_PRICE
  }

  public record Line(
      MaterialQuoteCommand.Item requirement,
      Status status,
      Instant observedAt,
      String itemName,
      Integer bundleCount,
      BigDecimal pricePerBundle,
      MaterialPurchaseCost purchase) {}
}
