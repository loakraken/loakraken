package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CombatPowerEquipmentModel;
import com.loakraken.character.domain.CombatPowerGemModel;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class CombatPowerCoefficientsConfiguration {
  static final String GEM_RESOURCE = "combat-power/gem-coefficients.json";
  static final String EQUIPMENT_RESOURCE = "combat-power/equipment-engraving-coefficients.json";

  /** 전투력 시뮬레이터 보석 계수(#243). 근거·확인 날짜는 리소스 source에 있다. 읽을 수 없으면 애플리케이션이 시작하지 않는다. */
  @Bean
  CombatPowerGemModel.Coefficients combatPowerGemCoefficients(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(GEM_RESOURCE).getInputStream()) {
      return mapper.readValue(input, CombatPowerGemModel.Coefficients.class);
    }
  }

  /** 품질·각인·스톤 계수(#275). 근거는 리소스 source에 있다. */
  @Bean
  CombatPowerEquipmentModel.Coefficients combatPowerEquipmentCoefficients(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(EQUIPMENT_RESOURCE).getInputStream()) {
      return mapper.readValue(input, CombatPowerEquipmentModel.Coefficients.class);
    }
  }
}
