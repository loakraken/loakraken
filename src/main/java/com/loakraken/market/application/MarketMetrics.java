package com.loakraken.market.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class MarketMetrics {
  private final MeterRegistry registry;

  public MarketMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void cache(Kind kind, Outcome outcome) {
    registry
        .counter(
            "loakraken.market.cache.operations",
            "kind",
            kind.name().toLowerCase(Locale.ROOT),
            "outcome",
            outcome.name().toLowerCase(Locale.ROOT))
        .increment();
  }

  /**
   * 악세 경매 검색기(N13) 사건: quota_reject(기능 몫), gate_reject(공유 게이트), incomplete(뒤 필터 complete=false).
   */
  public void auctionSearch(String event) {
    registry.counter("loakraken.market.auction_search", "event", event).increment();
  }

  public enum Kind {
    PRICES,
    OPTIONS,
    ITEM_HISTORY,
    BOARD_SCAN,
    RELIC_BOOKS,
    AUCTION_SEARCH
  }

  public enum Outcome {
    HIT,
    MISS,
    INVALID,
    READ_ERROR,
    WRITE_SUCCESS,
    WRITE_ERROR
  }
}
