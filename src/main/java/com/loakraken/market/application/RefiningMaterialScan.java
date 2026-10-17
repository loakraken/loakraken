package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** T4 재련 재료·추가 재료 탭 전체 시세(L30 후속). observedAt은 스캔한 페이지 중 가장 이른 관측 시각이다. */
public record RefiningMaterialScan(Instant observedAt, List<MarketPrice> items) {
  public RefiningMaterialScan {
    Objects.requireNonNull(observedAt);
    items = List.copyOf(items);
  }
}
