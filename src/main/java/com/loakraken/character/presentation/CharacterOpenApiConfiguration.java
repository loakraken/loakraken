package com.loakraken.character.presentation;

import com.loakraken.common.api.ResponseContracts;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CharacterOpenApiConfiguration {
  @Bean
  OpenApiCustomizer characterResponseContract() {
    return document -> {
      ResponseContracts.apply(CharacterResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(
          CharacterSnapshotHistoryResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(
          CharacterSiblingsResponse.class, document.getComponents().getSchemas());
    };
  }
}
