package com.loakraken.market.infrastructure;

import com.loakraken.common.lostark.LostArkMetrics.Endpoint;
import com.loakraken.market.application.MarketPage;
import com.loakraken.market.application.MarketPricesPort;
import com.loakraken.market.application.MarketSearch;
import com.loakraken.market.application.MarketSource;
import org.springframework.stereotype.Component;

@Component
class LostArkMarketClient implements MarketPricesPort {
  private final LostArkMarketHttpClient client;

  LostArkMarketClient(LostArkMarketHttpClient client) {
    this.client = client;
  }

  public MarketPage fetch(MarketSource source, MarketSearch search) {
    var endpoint =
        source == MarketSource.ACTIVE_LISTING ? Endpoint.MARKET_ITEMS : Endpoint.MARKET_TRADES;
    return client
        .request(endpoint, search, LostArkMarketResponse.class)
        .toPage(source, search.pageNo());
  }
}
