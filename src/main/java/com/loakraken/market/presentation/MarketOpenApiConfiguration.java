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
      ResponseContracts.apply(MaterialQuoteResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(MaterialPriceResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(
          MarketItemHistoryResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(MarketBoardResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(MarketTabsResponse.class, document.getComponents().getSchemas());
    };
  }
}
