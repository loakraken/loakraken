package com.loakraken.home.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  HomeCacheProperties.class,
  com.loakraken.home.application.ShopNoticePolicy.class
})
class HomeConfiguration {}
