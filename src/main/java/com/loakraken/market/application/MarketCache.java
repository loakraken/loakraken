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

  /** 유물 각인서 시세 스캔(L30). 키 TTL이 지났으면 없다. */
  default Optional<RelicBookScan> findRelicBooks() {
    return Optional.empty();
  }

  default void saveRelicBooks(RelicBookScan scan, java.time.Duration ttl) {}

  /** T4 재련 재료 시세 스캔(L30 후속). 키 TTL이 지났으면 없다. */
  default Optional<RefiningMaterialScan> findRefiningMaterials() {
    return Optional.empty();
  }

  default void saveRefiningMaterials(RefiningMaterialScan scan, java.time.Duration ttl) {}

  /** 생활 분류 전체 시세 스캔(재료 시세 예열). 키 TTL이 지났으면 없다. */
  default Optional<LifeMaterialScan> findLifeMaterials() {
    return Optional.empty();
  }

  default void saveLifeMaterials(LifeMaterialScan scan, java.time.Duration ttl) {}
}
