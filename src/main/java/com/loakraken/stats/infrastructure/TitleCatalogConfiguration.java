package com.loakraken.stats.infrastructure;

import com.loakraken.stats.domain.TitleCatalog;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class TitleCatalogConfiguration {
  static final String RESOURCE = "stats/title-catalog.json";

  /** 칭호별 등급·획득 조건 표. 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  TitleCatalog titleCatalog(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return mapper.readValue(input, TitleCatalog.class);
    }
  }
}
