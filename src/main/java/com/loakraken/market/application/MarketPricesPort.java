package com.loakraken.market.application;

public interface MarketPricesPort {
  MarketPage fetch(MarketSource source, MarketSearch search);
}
