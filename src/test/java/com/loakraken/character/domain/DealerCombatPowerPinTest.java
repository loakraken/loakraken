package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비로 지은 딜러 전투력 고정 값(계산 검증 11-21 '절대 전투력 핀'): 실제 공식 응답 36명(일부 두 시점)의 점수 입력에서, 비교 기준 B 식(보정값
 * 2,400)으로 계산 검증이 낸 값과 ±0.01 안. 고정 파일은 점수 입력만 담는다(이름 없음).
 */
class DealerCombatPowerPinTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void 핀_전부가_비교_기준_식과_소수_둘째_자리까지_같다() throws Exception {
    var engine = engineTables();
    var tables = cpTables();
    var misses = new ArrayList<String>();
    int checked = 0;
    for (var name : read("kraken-score/cp-pins/index.json")) {
      var fixture = read("kraken-score/cp-pins/" + name.asString() + ".json");
      var input = fixture.path("input");
      // 1단계(카드 문자열) 규칙이 계산 검증 핀과 같다.
      assertThat(input.path("card").asString(null))
          .as(name.asString())
          .isEqualTo(fixture.path("card").asString(null));
      var cp = DealerCombatPower.of(input, engine, tables);
      var expected = fixture.path("expected").decimalValue();
      checked++;
      if (cp == null || cp.subtract(expected).abs().compareTo(new BigDecimal("0.01")) > 0)
        misses.add(
            name.asString()
                + " expected "
                + expected
                + " got "
                + cp
                + " "
                + DealerCombatPower.factors(input, engine, tables));
    }
    assertThat(checked).isGreaterThanOrEqualTo(36);
    assertThat(misses).isEmpty();
  }

  static KrakenScoreEngine.Tables engineTables() throws Exception {
    var doc = read("kraken-score/reference-tables.json");
    return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
  }

  static DealerCombatPower.Tables cpTables() throws Exception {
    return new DealerCombatPower.Tables(
        read("kraken-score/combat-power-tables.json").path("tables"));
  }

  private static JsonNode read(String path) throws Exception {
    return MAPPER.readTree(new ClassPathResource(path).getInputStream());
  }
}
