package com.loakraken.market.infrastructure;

import com.loakraken.market.application.AuctionPort;
import com.loakraken.market.application.AuctionSearchPort;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
class LostArkAuctionClient implements AuctionSearchPort {
  private final LostArkMarketHttpClient client;
  private final Clock clock;

  LostArkAuctionClient(LostArkMarketHttpClient client, Clock clock) {
    this.client = client;
    this.clock = clock;
  }

  @Override
  public AuctionPort.AuctionPage search(AuctionPort.AuctionQuery query) {
    return client.requestAuction(query, LostArkAuctionResponse.class).toPage(clock.instant());
  }
}
