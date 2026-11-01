package com.loakraken.refining.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RefiningOpenApiConfiguration {
  @Bean
  OpenApiCustomizer refiningResponseContract() {
    return document -> {
      var schemas = document.getComponents().getSchemas();
      ResponseContracts.apply(RefiningPlanResponse.class, schemas);
      ResponseContracts.apply(RefiningPlanTotalResponse.class, schemas);
      ResponseContracts.apply(RefiningPlanTotalResponse.MaterialLine.class, schemas);
      ResponseContracts.apply(RefiningPlanTotalResponse.ListingResponse.class, schemas);
    };
  }
}
