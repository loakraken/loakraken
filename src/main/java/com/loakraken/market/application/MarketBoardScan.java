package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 탭 전체 스캔 결과. 상승·하락 정렬이 공유하며 정렬 전 원본 순서를 보관한다.
 *
 * <p>observedAt은 스캔한 페이지 중 가장 이른 관측 시각, scannedItems가 totalItems보다 적으면 페이지 상한에 걸린 것이다.
 */
public record MarketBoardScan(
    MarketTab tab, Integer tier, Instant observedAt, int totalItems, List<MarketPrice> items) {
  public MarketBoardScan {
    Objects.requireNonNull(tab);
    Objects.requireNonNull(observedAt);
    items = List.copyOf(items);
  }

  public boolean complete() {
    return items.size() >= totalItems;
  }
}
