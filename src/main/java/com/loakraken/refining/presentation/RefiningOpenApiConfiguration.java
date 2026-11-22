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
      // 상급 재련(#1091): 등록이 빠져 @NullableResponse(expectedSilver·enhanced)가 문서에 붙지 않았다(프론트2 11-22).
      ResponseContracts.apply(AdvancedRefiningPlanResponse.class, schemas);
    };
  }
}
