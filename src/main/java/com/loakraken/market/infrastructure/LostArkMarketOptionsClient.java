package com.loakraken.market.infrastructure;

import com.loakraken.common.lostark.LostArkMetrics.Endpoint;
import com.loakraken.market.application.MarketOptionsPort;
import com.loakraken.market.domain.MarketOptions;
import org.springframework.stereotype.Component;

@Component
class LostArkMarketOptionsClient implements MarketOptionsPort {
  private final LostArkMarketHttpClient client;

  LostArkMarketOptionsClient(LostArkMarketHttpClient client) {
    this.client = client;
  }

  public MarketOptions fetchOptions() {
    return client
        .request(Endpoint.MARKET_OPTIONS, null, LostArkMarketOptionsResponse.class)
        .toDomain();
  }
}
