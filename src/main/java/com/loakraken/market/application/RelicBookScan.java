package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 유물 각인서 전체 시세(L30). observedAt은 스캔한 페이지 중 가장 이른 관측 시각이다. */
public record RelicBookScan(Instant observedAt, int totalItems, List<MarketPrice> items) {
  public RelicBookScan {
    Objects.requireNonNull(observedAt);
    items = List.copyOf(items);
  }
}
