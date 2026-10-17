package com.loakraken.stats.presentation;

import com.loakraken.common.api.ResponseContracts;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class StatsOpenApiConfiguration {
  @Bean
  OpenApiCustomizer statsResponseContract() {
    return document -> {
      var schemas = document.getComponents().getSchemas();
      for (var type :
          List.of(
              StatsResponses.StatsSummaryResponse.class,
              StatsResponses.StatsClassesResponse.class,
              StatsResponses.StatsBestCombatPowerResponse.class,
              StatsResponses.StatsServersResponse.class,
              StatsResponses.StatsDistributionResponse.class,
              StatsResponses.StatsSnapshotsResponse.class,
              StatsResponses.StatsRankingResponse.class,
              StatsResponses.StatsEfficiencyRankingResponse.class,
              StatsResponses.StatsCharacterRankResponse.class))
        ResponseContracts.apply(type, schemas);
    };
  }
}
