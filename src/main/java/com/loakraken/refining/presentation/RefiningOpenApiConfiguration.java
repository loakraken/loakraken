package com.loakraken.refining.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RefiningOpenApiConfiguration {
  @Bean
  OpenApiCustomizer refiningResponseContract() {
    return document ->
        ResponseContracts.apply(RefiningPlanResponse.class, document.getComponents().getSchemas());
  }
}
