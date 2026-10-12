package com.loakraken.market.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
  MarketCacheProperties.class,
  MarketWarmupProperties.class,
  RelicBookWarmupProperties.class,
  com.loakraken.market.application.AccessorySearchPolicy.class
})
class MarketConfiguration {}
