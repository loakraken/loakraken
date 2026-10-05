package com.loakraken.market.application;

import com.loakraken.market.domain.MarketItemHistory;
import java.util.Optional;

public interface MarketItemHistoryPort {
  /** 공식 API가 해당 상품을 모르면 빈 값을 돌려준다. */
  Optional<MarketItemHistory> fetchHistory(long itemId);
}
