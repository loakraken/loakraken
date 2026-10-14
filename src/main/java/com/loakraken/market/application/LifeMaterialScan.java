package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 거래소 생활 분류(90000) 전체 시세. 예열이 페이지를 다 모아 저장한다. observedAt은 스캔한 페이지 중 가장 이른 관측 시각이다. */
public record LifeMaterialScan(Instant observedAt, List<MarketPrice> items) {
  public LifeMaterialScan {
    Objects.requireNonNull(observedAt);
    items = List.copyOf(items);
  }
}
