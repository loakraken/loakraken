package com.loakraken.stats.infrastructure;

import com.loakraken.stats.domain.ClassEngravingCatalog;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class ClassEngravingConfiguration {
  static final String RESOURCE = "stats/class-engravings.json";

  /** 직업별 직업 각인 목록(#618). 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  ClassEngravingCatalog classEngravingCatalog(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return mapper.readValue(input, ClassEngravingCatalog.class);
    }
  }
}
