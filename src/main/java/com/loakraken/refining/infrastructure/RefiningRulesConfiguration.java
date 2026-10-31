package com.loakraken.refining.infrastructure;

import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.refining.domain.VambraceRules;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class RefiningRulesConfiguration {
  static final String RESOURCE = "refining/t4-refining-rules.json";
  static final String GROWTH_RESOURCE = "refining/t4-growth-costs.json";
  static final String VAMBRACE_RESOURCE = "refining/t4-vambrace-rules.json";

  /** 아이스펭(MIT) 데이터를 고정한 리소스를 시작 시 한 번 읽는다. 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  RefiningRules refiningRules(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return mapper.readValue(input, RefiningRules.class);
    }
  }

  /** 장비 성장 비용(#200, 계산 검증 2026-10-10 외부 계산 결과의 단계 실측 + 구간 합). */
  /** 팔 보호구(완갑) 재련 단계표(스펙업 완갑 비용). */
  @Bean
  VambraceRules vambraceRules(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(VAMBRACE_RESOURCE).getInputStream()) {
      return mapper.readValue(input, VambraceRules.class);
    }
  }

  @Bean
  GrowthCosts growthCosts(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(GROWTH_RESOURCE).getInputStream()) {
      return mapper.readValue(input, GrowthCosts.class);
    }
  }
}
