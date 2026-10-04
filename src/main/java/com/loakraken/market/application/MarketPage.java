package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.util.List;

public record MarketPage(int pageNo, int pageSize, int totalCount, List<MarketPrice> items) {
  public MarketPage {
    items = List.copyOf(items);
  }
}
