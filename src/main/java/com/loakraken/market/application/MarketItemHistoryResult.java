package com.loakraken.market.application;

import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;

/** observedAt은 서버가 공식 상세를 읽은 시각이며 캐시 적중 시 유지한다. */
public record MarketItemHistoryResult(Instant observedAt, MarketItemHistory history) {}
