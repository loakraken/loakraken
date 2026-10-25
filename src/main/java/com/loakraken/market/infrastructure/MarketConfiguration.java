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
  RefiningMaterialWarmupProperties.class,
  GemWarmupProperties.class,
  LifeMaterialWarmupProperties.class,
  com.loakraken.market.application.AccessorySearchPolicy.class,
  com.loakraken.market.application.GemFilterPolicy.class
})
class MarketConfiguration {}
