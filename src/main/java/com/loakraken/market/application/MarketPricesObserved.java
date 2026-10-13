package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 예열이 공식 거래소에서 받은 품목 가격(M33 시세 알림이 듣는다). 예열 주기마다 발행하며, 공식 호출을 더 하지 않는다. 듣는 쪽 실패가 예열을 멈추지 않게 발행하는 쪽이
 * 예외를 삼킨다.
 */
public record MarketPricesObserved(Instant observedAt, List<MarketPrice> prices) {
  public MarketPricesObserved {
    Objects.requireNonNull(observedAt);
    prices = List.copyOf(prices);
  }
}
