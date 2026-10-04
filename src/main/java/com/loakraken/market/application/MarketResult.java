package com.loakraken.market.application;

import java.time.Instant;
import java.util.Objects;

public record MarketResult(
    MarketSource source, Instant observedAt, MarketSearch search, MarketPage page) {
  public MarketResult {
    Objects.requireNonNull(source);
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(search);
    Objects.requireNonNull(page);
    if (search.pageNo() != page.pageNo()
        || (source == MarketSource.RECENT_TRADE
            && page.items().stream().anyMatch(item -> item.currentMinPrice() != null))) {
      throw new IllegalArgumentException("거래소 응답 문맥이 일치하지 않습니다.");
    }
  }
}
