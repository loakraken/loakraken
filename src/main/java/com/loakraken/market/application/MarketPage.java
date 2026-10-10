package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.util.List;

public record MarketPage(int pageNo, int pageSize, int totalCount, List<MarketPrice> items) {
  public MarketPage {
    items = List.copyOf(items);
    if (pageNo < 1
        || pageSize < 1
        || totalCount < 0
        || items.size() > pageSize
        || items.size() > totalCount) throw new IllegalArgumentException("잘못된 거래소 페이지");
  }
}
