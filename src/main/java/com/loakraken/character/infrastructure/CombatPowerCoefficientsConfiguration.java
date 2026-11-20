package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CombatPowerArkGridModel;
import com.loakraken.character.domain.CombatPowerBraceletModel;
import com.loakraken.character.domain.CombatPowerEquipmentModel;
import com.loakraken.character.domain.CombatPowerGemModel;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.character.domain.CombatPowerRefineModel;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class CombatPowerCoefficientsConfiguration {
  static final String GEM_RESOURCE = "combat-power/gem-coefficients.json";
  static final String EQUIPMENT_RESOURCE = "combat-power/equipment-engraving-coefficients.json";
  static final String POLISH_RESOURCE = "combat-power/polish-values.json";
  static final String REFINE_RESOURCE = "combat-power/refine-coefficients.json";
  static final String BRACELET_RESOURCE = "combat-power/bracelet-coefficients.json";
  static final String ARK_GRID_RESOURCE = "combat-power/ark-grid-coefficients.json";
  static final String ARK_PASSIVE_RESOURCE = "combat-power/ark-passive-coefficients.json";
  static final String KRAKEN_SCORE_TIERS_RESOURCE = "kraken-score/tiers.json";
  static final String KRAKEN_SCORE_TABLES_RESOURCE = "kraken-score/reference-tables.json";

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

  /** 재련 단계 모델 계수(#319): 전율 단계표·펫 가정·아바타·카르마. 근거는 리소스 source에 있다. */
  @Bean
  CombatPowerRefineModel.Coefficients combatPowerRefineCoefficients(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(REFINE_RESOURCE).getInputStream()) {
      return mapper.readValue(input, CombatPowerRefineModel.Coefficients.class);
    }
  }

  /** 팔찌 모델 계수(#327): 특수 옵션 계열·전투 특성·도약. 근거는 리소스 source에 있다. */
  @Bean
  CombatPowerBraceletModel.Coefficients combatPowerBraceletCoefficients(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(BRACELET_RESOURCE).getInputStream()) {
      return mapper.readValue(input, CombatPowerBraceletModel.Coefficients.class);
    }
  }

  /** 아크 그리드 코어·젬 계수(L29·L39, #323·#335·#358·#368). 근거는 리소스 source에 있다. */
  @Bean
  CombatPowerArkGridModel.Coefficients combatPowerArkGridCoefficients(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(ARK_GRID_RESOURCE).getInputStream()) {
      return mapper.readValue(input, CombatPowerArkGridModel.Coefficients.class);
    }
  }

  /** 아크 패시브 진화·깨달음 포인트 계수(PM 총괄 11-15 19:12). 근거는 리소스 source에 있다. */
  @Bean
  com.loakraken.character.domain.CombatPowerArkPassiveModel.Coefficients
      combatPowerArkPassiveCoefficients(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(ARK_PASSIVE_RESOURCE).getInputStream()) {
      return mapper.readValue(
          input, com.loakraken.character.domain.CombatPowerArkPassiveModel.Coefficients.class);
    }
  }

  /** 크라켄 점수 티어 컷(#522). 읽을 수 없으면 애플리케이션이 시작하지 않는다(조용한 UNSUPPORTED 금지). */
  @Bean
  com.loakraken.character.domain.KrakenScoreTier.Table krakenScoreTierTable(JsonMapper mapper)
      throws IOException {
    try (var input = new ClassPathResource(KRAKEN_SCORE_TIERS_RESOURCE).getInputStream()) {
      return mapper.readValue(input, com.loakraken.character.domain.KrakenScoreTier.Table.class);
    }
  }

  /** 크라켄 점수 엔진 표(#539): 비교 기준 B 원본 표 중 엔진이 읽는 것만. 읽을 수 없거나 표가 빠지면 애플리케이션이 시작하지 않는다(조용한 근사 금지). */
  @Bean
  com.loakraken.character.domain.KrakenScoreEngine.Tables krakenScoreTables(JsonMapper mapper)
      throws IOException {
    // 바뀌지 않는 자원 표(노드 약 1만 7천 개)라 JVM에 한 벌만 둔다 — 테스트처럼 스프링 컨텍스트가 여럿 떠도 컨텍스트마다 다시 읽지 않는다.
    var tables = sharedKrakenScoreTables;
    if (tables != null) return tables;
    synchronized (CombatPowerCoefficientsConfiguration.class) {
      if (sharedKrakenScoreTables == null) {
        try (var input = new ClassPathResource(KRAKEN_SCORE_TABLES_RESOURCE).getInputStream()) {
          var doc = mapper.readTree(input);
          sharedKrakenScoreTables =
              new com.loakraken.character.domain.KrakenScoreEngine.Tables(
                  doc.path("savedAt").asString(""), doc.path("tables"));
        }
      }
      return sharedKrakenScoreTables;
    }
  }

  private static volatile com.loakraken.character.domain.KrakenScoreEngine.Tables
      sharedKrakenScoreTables;

  /** 장비로 지은 딜러 전투력 표(#1031): 읽을 수 없거나 표 모양·값이 틀리면 애플리케이션이 시작하지 않는다(조용한 ×1 금지, 코드 리뷰 #1031 P2). */
  @Bean
  com.loakraken.character.domain.DealerCombatPower.Tables dealerCombatPowerTables(JsonMapper mapper)
      throws IOException {
    try (var input =
        new ClassPathResource("kraken-score/combat-power-tables.json").getInputStream()) {
      return new com.loakraken.character.domain.DealerCombatPower.Tables(
          mapper.readTree(input).path("tables"));
    }
  }

  /** 연마 옵션별 하·중·상 수치(#251). 근거는 리소스 source에 있다. */
  @Bean
  CombatPowerPolishValues combatPowerPolishValues(JsonMapper mapper) throws IOException {
    try (var input = new ClassPathResource(POLISH_RESOURCE).getInputStream()) {
      return mapper.readValue(input, CombatPowerPolishValues.class);
    }
  }
}
