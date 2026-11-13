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

  /**
   * day(KST) 거래량 지도에서 그 품목들의 완료일 거래량. 없거나 읽지 못하면 빈 값. 거래량 지도는 날짜별 캐시다(DB 없음). 돌림 탭(아바타)은 며칠에 한
   * 번 받으므로 보드가 최근 7일 지도까지 거슬러 찾을 수 있게 TTL을 8일로 둔다.
   */
  default Map<Long, com.loakraken.market.domain.MarketVolume> findVolumes(
      java.time.LocalDate day, Collection<Long> itemIds) {
    return Map.of();
  }

  /** day 거래량 지도에 더한다(같은 품목은 덮어씀). 쓰지 못해도 예외를 내지 않는다. */
  default void saveVolumes(
      java.time.LocalDate day, Collection<com.loakraken.market.domain.MarketVolume> volumes) {}

  /**
   * day 거래량 채우기 공식 호출 수에 calls를 더하고 더한 뒤 값을 준다(하루 상한용, 인스턴스가 바뀌어도 이어 센다). 읽지 못하면
   * Long.MAX_VALUE(채우기를 멈춤).
   */
  default long addVolumeCalls(java.time.LocalDate day, int calls) {
    return Long.MAX_VALUE;
  }

  /** 거래량 채우기 돌림 탭의 위치. 없거나 읽지 못하면 빈 값(1페이지부터). */
  default Optional<MarketVolumeCursor> findVolumeCursor(MarketTab tab) {
    return Optional.empty();
  }

  /** 거래량 채우기 돌림 탭의 위치를 저장한다. 쓰지 못해도 예외를 내지 않는다. */
  default void saveVolumeCursor(MarketTab tab, MarketVolumeCursor cursor) {}

  /** 유물 각인서 시세 스캔(L30). 키 TTL이 지났으면 없다. */
  default Optional<RelicBookScan> findRelicBooks() {
    return Optional.empty();
  }

  default void saveRelicBooks(RelicBookScan scan, java.time.Duration ttl) {}

  /** 유물 각인서 아이콘(이름 공백 뺀 값 → 공식 아이콘 주소). 만료 없이 둔다 — 시세 캐시가 비어도 각인 그림은 보이게(PM 총괄 10-25). */
  default java.util.Map<String, String> findRelicBookIcons() {
    return java.util.Map.of();
  }

  default void saveRelicBookIcons(java.util.Map<String, String> icons) {}

  /** T4 재련 재료 시세 스캔(L30 후속). 키 TTL이 지났으면 없다. */
  default Optional<RefiningMaterialScan> findRefiningMaterials() {
    return Optional.empty();
  }

  default void saveRefiningMaterials(RefiningMaterialScan scan, java.time.Duration ttl) {}

  /** 생활 분류 전체 시세 스캔(재료 시세 예열). 키 TTL이 지났으면 없다. */
  default Optional<LifeMaterialScan> findLifeMaterials(int category) {
    return Optional.empty();
  }

  default void saveLifeMaterials(int category, LifeMaterialScan scan, java.time.Duration ttl) {}
}
