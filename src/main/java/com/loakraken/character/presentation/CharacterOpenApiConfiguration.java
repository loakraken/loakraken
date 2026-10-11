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
      ResponseContracts.apply(
          CharacterHistoryResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(CharacterViewsResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(CardCollectionResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(
          CharacterPopularResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(
          CardCollectionRefreshResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(RosterTitlesResponse.class, document.getComponents().getSchemas());
      ResponseContracts.apply(
          CharacterPublicSummaryController.CharacterPublicSummaryResponse.class,
          document.getComponents().getSchemas());
      ResponseContracts.apply(
          CharacterItemTooltipController.CharacterItemTooltipsResponse.class,
          document.getComponents().getSchemas());
      // 자동완성(#372): items·이름·서버·직업·아이템 레벨은 필수, 초상만 nullable(프론트 10-12 05:32).
      ResponseContracts.apply(
          CharacterSuggestController.CharacterSuggestResponse.class,
          document.getComponents().getSchemas());
    };
  }
}
