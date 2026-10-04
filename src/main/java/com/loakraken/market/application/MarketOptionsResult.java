package com.loakraken.market.application;

import com.loakraken.market.domain.MarketOptions;
import java.time.Instant;
import java.util.Objects;

public record MarketOptionsResult(Instant observedAt, MarketOptions options) {
  public MarketOptionsResult {
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(options);
  }
}
