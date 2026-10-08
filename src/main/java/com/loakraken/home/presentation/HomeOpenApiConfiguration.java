package com.loakraken.home.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class HomeOpenApiConfiguration {
  @Bean
  OpenApiCustomizer homeResponseContract() {
    return document -> {
      ResponseContracts.apply(HomeScheduleResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(HomeNewsResponse.class, document.getComponents().getSchemas());
    };
  }
}
