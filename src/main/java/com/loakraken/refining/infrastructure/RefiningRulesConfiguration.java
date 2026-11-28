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

  /**
   * MIT 라이선스 데이터(저작자 표시는 NOTICE와 THIRD-PARTY-LICENSE 사본)를 고정한 리소스를 시작 시 한 번 읽는다. 읽을 수 없으면 애플리케이션이
   * 시작하지 않는다.
   */
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

  static final String SPECIAL_RESOURCE = "refining/t4-special-refine.json";

  /** 특수 재련 1회 소모량(M42). 출처는 파일 안. 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  com.loakraken.refining.domain.SpecialRefineRules specialRefineRules(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(SPECIAL_RESOURCE).getInputStream()) {
      var root = mapper.readTree(input);
      var source = root.path("source");
      var tracks =
          new java.util.ArrayList<com.loakraken.refining.domain.SpecialRefineRules.Track>();
      for (var node : root.path("tracks")) {
        var perTry = new java.util.HashMap<Integer, Integer>();
        node.path("perTryByFromStage")
            .properties()
            .forEach(e -> perTry.put(Integer.parseInt(e.getKey()), e.getValue().asInt()));
        tracks.add(
            new com.loakraken.refining.domain.SpecialRefineRules.Track(
                RefiningRules.Grade.valueOf(node.path("grade").asString()),
                RefiningRules.EquipmentType.valueOf(node.path("equipmentType").asString()),
                perTry));
      }
      return new com.loakraken.refining.domain.SpecialRefineRules(
          root.path("ruleVersion").asString(),
          new com.loakraken.refining.domain.SpecialRefineRules.Source(
              source.path("rule").asString(),
              source.path("perTry").asString(),
              source.path("capturedOn").asString(),
              source.path("singleSource").asBoolean()),
          tracks);
    }
  }

  static final String ADVANCED_RESOURCE = "refining/t4-advanced-refining-rules.json";

  /** 상급 재련 규칙(계산 검증 명세 2026-11-22). 블록마다 출처가 있다. 읽을 수 없거나 값이 어긋나면 애플리케이션이 시작하지 않는다. */
  @Bean
  com.loakraken.refining.domain.AdvancedRefineRules advancedRefineRules(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(ADVANCED_RESOURCE).getInputStream()) {
      return AdvancedRefineRulesReader.read(mapper.readTree(input));
    }
  }
}
