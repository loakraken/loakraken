package com.loakraken.market.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class MarketOpenApiConfiguration {
  @Bean
  OpenApiCustomizer marketResponseContract() {
    return document -> {
      ResponseContracts.apply(MarketPriceResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(MarketOptionsResponse.class, document.getComponents().getSchemas());
    };
  }
}
