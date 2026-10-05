package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketItemHistoryPort;
import com.loakraken.market.domain.MarketItemHistory;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class LostArkMarketItemClient implements MarketItemHistoryPort {
  private final LostArkMarketHttpClient client;

  LostArkMarketItemClient(LostArkMarketHttpClient client) {
    this.client = client;
  }

  public Optional<MarketItemHistory> fetchHistory(long itemId) {
    var items = client.requestItem(itemId, LostArkMarketItemResponse[].class);
    if (items == null || items.length == 0) return Optional.empty();
    return Optional.of(LostArkMarketItemResponse.toDomain(itemId, items));
  }
}
