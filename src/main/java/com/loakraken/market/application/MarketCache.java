package com.loakraken.market.application;

import java.util.Optional;

public interface MarketCache {
  Optional<MarketResult> findPrices(MarketSource source, MarketSearch search);

  void savePrices(MarketResult result);

  Optional<MarketOptionsResult> findOptions();

  void saveOptions(MarketOptionsResult result);

  Optional<MarketItemHistoryResult> findItemHistory(long itemId);

  void saveItemHistory(MarketItemHistoryResult result);
}
