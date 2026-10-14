package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 예열이 분류 전체를 받은 시세(생활 90000·전투 용품 60000). observedAt은 스캔한 페이지 중 가장 이른 관측 시각이다.
 *
 * @param completeCategories 공식 TotalCount만큼 다 받은 분류. 여기 있는 분류는 이 스캔에 없는 이름을 다시 검색하지 않는다
 */
public record LifeMaterialScan(
    Instant observedAt, List<MarketPrice> items, List<Integer> completeCategories) {
  public LifeMaterialScan {
    Objects.requireNonNull(observedAt);
    items = List.copyOf(items);
    completeCategories = completeCategories == null ? List.of() : List.copyOf(completeCategories);
  }

  /** 다 받았는지 모르는 스캔(값은 쓰되 검색을 건너뛰지 않는다). */
  public LifeMaterialScan(Instant observedAt, List<MarketPrice> items) {
    this(observedAt, items, List.of());
  }
}
