package com.loakraken.market.application;

import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class MarketQueryService {
  private final MarketPricesPort prices;
  private final Clock clock;

  public MarketQueryService(MarketPricesPort prices, Clock clock) {
    this.prices = prices;
    this.clock = clock;
  }

  public MarketResult search(MarketSource source, MarketSearch search) {
    var page = prices.fetch(source, search);
    return new MarketResult(source, clock.instant(), search, page);
  }
}
