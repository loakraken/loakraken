package com.loakraken.gem.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gem", name = "enabled", havingValue = "true")
class GemOpenApiConfiguration {
  @Bean
  OpenApiCustomizer gemResponseContract() {
    return document ->
        ResponseContracts.apply(GemSynthesisResponse.class, document.getComponents().getSchemas());
  }
}
