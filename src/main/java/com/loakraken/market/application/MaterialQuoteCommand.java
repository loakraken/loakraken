package com.loakraken.market.application;

import com.loakraken.market.domain.MaterialPriceBasis;
import java.util.List;
import java.util.Objects;

public record MaterialQuoteCommand(MaterialPriceBasis priceBasis, List<Item> items) {
  public MaterialQuoteCommand {
    Objects.requireNonNull(priceBasis);
    items = List.copyOf(items);
    if (items.isEmpty()
        || items.size() > 10
        || items.stream().map(Item::itemId).distinct().count() != items.size()) {
      throw new IllegalArgumentException("잘못된 견적 항목");
    }
  }

  public record Item(long itemId, long requiredQuantity, long ownedQuantity, MarketSearch search) {
    public Item {
      Objects.requireNonNull(search);
      if (itemId < 1
          || itemId > 9_007_199_254_740_991L
          || requiredQuantity < 0
          || requiredQuantity > 999_999_999L
          || ownedQuantity < 0
          || ownedQuantity > 999_999_999L) {
        throw new IllegalArgumentException("잘못된 재료 수량");
      }
    }

    public long missingQuantity() {
      return Math.max(0, requiredQuantity - ownedQuantity);
    }
  }
}
