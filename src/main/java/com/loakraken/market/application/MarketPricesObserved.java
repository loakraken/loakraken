package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 예열이 공식 거래소에서 받은 품목 가격(M33 시세 알림·K35 분 단위 차트가 듣는다). 예열 주기마다 발행하며, 공식 호출을 더 하지 않는다. 듣는 쪽 실패가 예열을
 * 멈추지 않게 발행하는 쪽이 예외를 삼킨다.
 *
 * @param sampleEvery 이 품목들을 다시 받는 예열 주기(보드 30초·각인서 30분·재련 재료 10분 등). 분 단위 차트가 실제 확인 간격을 알리는 데 쓴다
 */
public record MarketPricesObserved(
    Instant observedAt, Duration sampleEvery, List<MarketPrice> prices) {
  public MarketPricesObserved {
    Objects.requireNonNull(observedAt);
    Objects.requireNonNull(sampleEvery);
    prices = List.copyOf(prices);
  }
}
