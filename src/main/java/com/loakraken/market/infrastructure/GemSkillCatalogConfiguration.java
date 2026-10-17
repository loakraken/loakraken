package com.loakraken.market.infrastructure;

import com.loakraken.market.domain.GemSkillCatalog;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class GemSkillCatalogConfiguration {
  static final String RESOURCE = "market/gem-skill-options.json";

  /** 보석 매물 직업·스킬 목록. 근거·확인 날짜는 리소스 source·checkedOn에 있다. 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  GemSkillCatalog gemSkillCatalog(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      return mapper.readValue(input, GemSkillCatalog.class);
    }
  }
}
