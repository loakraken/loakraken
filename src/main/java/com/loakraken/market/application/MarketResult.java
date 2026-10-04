package com.loakraken.market.application;

import java.time.Instant;

public record MarketResult(
    MarketSource source, Instant observedAt, MarketSearch search, MarketPage page) {}
