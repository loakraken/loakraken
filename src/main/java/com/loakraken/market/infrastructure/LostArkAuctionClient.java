package com.loakraken.market.infrastructure;

import com.loakraken.market.application.AuctionPort;
import com.loakraken.market.application.AuctionSearchPort;
import com.loakraken.market.application.MarketMetrics;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LostArkAuctionClient implements AuctionSearchPort {
  private static final Logger log = LoggerFactory.getLogger(LostArkAuctionClient.class);

  /** 상한 밖 값으로 버린 경매장 매물의 지표 reason(거래소 품목 상세의 DROP_REASONS와 같은 지표). */
  static final String DROP_REASON = "auction_listing";

  private final LostArkMarketHttpClient client;
  private final Clock clock;
  private final MarketMetrics metrics;

  LostArkAuctionClient(LostArkMarketHttpClient client, Clock clock, MarketMetrics metrics) {
    this.client = client;
    this.clock = clock;
    this.metrics = metrics;
  }

  @Override
  public AuctionPort.AuctionPage search(AuctionPort.AuctionQuery query) {
    var response = client.requestAuction(query, LostArkAuctionResponse.class);
    int dropped = response.implausibleItems();
    if (dropped > 0) {
      metrics.upstreamValue(DROP_REASON, dropped);
      // 분류 코드와 개수만 남긴다(값·품목 이름은 남기지 않음).
      log.warn(
          "event=auction_listings_dropped category={} count={}", query.categoryCode(), dropped);
    }
    return response.toPage(clock.instant());
  }
}
