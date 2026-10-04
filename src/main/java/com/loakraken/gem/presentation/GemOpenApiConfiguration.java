package com.loakraken.gem.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GemOpenApiConfiguration {
  @Bean
  OpenApiCustomizer gemResponseContract() {
    return document ->
        ResponseContracts.apply(GemSynthesisResponse.class, document.getComponents().getSchemas());
  }
}
