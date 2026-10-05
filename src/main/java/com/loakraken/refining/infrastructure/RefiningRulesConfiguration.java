package com.loakraken.refining.infrastructure;

import com.loakraken.refining.domain.RefiningRules;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class RefiningRulesConfiguration {
  static final String RESOURCE = "refining/t4-refining-rules.json";

  /** 아이스펭(MIT) 데이터를 고정한 리소스를 시작 시 한 번 읽는다. 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  RefiningRules refiningRules(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return mapper.readValue(input, RefiningRules.class);
    }
  }
}
