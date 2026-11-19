package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketItemHistoryPort;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.domain.MarketItemHistory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LostArkMarketItemClient implements MarketItemHistoryPort {
  private static final Logger log = LoggerFactory.getLogger(LostArkMarketItemClient.class);

  /** 버린 값 이유(지표 reason, 고정 집합). toDomain의 dropped 순서와 같다. */
  static final String[] DROP_REASONS = {"variants", "rows", "price", "date", "trade_count"};

  private final LostArkMarketHttpClient client;
  private final Clock clock;
  private final MarketMetrics metrics;

  LostArkMarketItemClient(LostArkMarketHttpClient client, Clock clock, MarketMetrics metrics) {
    this.client = client;
    this.clock = clock;
    this.metrics = metrics;
  }

  public Optional<MarketItemHistory> fetchHistory(long itemId) {
    var items = client.requestItem(itemId, LostArkMarketItemResponse[].class);
    if (items == null || items.length == 0) return Optional.empty();
    var dropped = new int[DROP_REASONS.length];
    var today = LocalDate.now(clock.withZone(MarketAuctionService.KST));
    var history = LostArkMarketItemResponse.toDomain(itemId, items, today, dropped);
    int total = 0;
    for (int k = 0; k < dropped.length; k++) {
      if (dropped[k] == 0) continue;
      total += dropped[k];
      metrics.upstreamValue(DROP_REASONS[k], dropped[k]);
    }
    // 공식 응답에서 상한 밖 값을 버렸다(보안 11-16 (b)). 품목 id와 개수만 남긴다(값은 남기지 않음).
    if (total > 0)
      log.warn(
          "event=market_item_values_dropped itemId={} variants={} rows={} price={} date={} tradeCount={}",
          itemId,
          dropped[0],
          dropped[1],
          dropped[2],
          dropped[3],
          dropped[4]);
    return Optional.of(history);
  }
}
