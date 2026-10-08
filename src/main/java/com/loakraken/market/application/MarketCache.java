package com.loakraken.market.application;

import com.loakraken.market.domain.MarketTab;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public interface MarketCache {
  Optional<MarketResult> findPrices(MarketSource source, MarketSearch search);

  void savePrices(MarketResult result);

  Optional<MarketOptionsResult> findOptions();

  void saveOptions(MarketOptionsResult result);

  Optional<MarketItemHistoryResult> findItemHistory(long itemId);

  void saveItemHistory(MarketItemHistoryResult result);

  /** 여러 상품의 이력 캐시를 한 번에 읽는다. 없거나 만료·손상된 항목은 결과에서 빠진다. */
  default Map<Long, MarketItemHistoryResult> findItemHistories(Collection<Long> itemIds) {
    var result = new LinkedHashMap<Long, MarketItemHistoryResult>();
    for (var itemId : itemIds)
      findItemHistory(itemId).ifPresent(value -> result.put(itemId, value));
    return result;
  }

  Optional<MarketBoardScan> findBoardScan(MarketTab tab, Integer tier);

  void saveBoardScan(MarketBoardScan scan);
}
