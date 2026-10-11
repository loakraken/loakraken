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

  public enum Kind {
    PRICES,
    OPTIONS,
    ITEM_HISTORY,
    BOARD_SCAN,
    RELIC_BOOKS
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
