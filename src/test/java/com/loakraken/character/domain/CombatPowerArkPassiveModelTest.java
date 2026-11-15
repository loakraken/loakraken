package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** 진화·깨달음 포인트 배수(PM 총괄 11-15 19:12). 계수는 리소스 그대로 읽는다. */
class CombatPowerArkPassiveModelTest {
  private static CombatPowerArkPassiveModel.Coefficients coefficients() throws Exception {
    try (var input =
        new ClassPathResource("combat-power/ark-passive-coefficients.json").getInputStream()) {
      return JsonMapper.builder()
          .build()
          .readValue(input, CombatPowerArkPassiveModel.Coefficients.class);
    }
  }

  @Test
  void 진화는_40포인트_넘는_만큼_포인트당_0_75퍼센트이고_140이면_75퍼센트다() throws Exception {
    var c = coefficients();
    assertThat(CombatPowerArkPassiveModel.evolution(c, 0)).isEqualByComparingTo("1");
    assertThat(CombatPowerArkPassiveModel.evolution(c, 40)).isEqualByComparingTo("1");
    assertThat(CombatPowerArkPassiveModel.evolution(c, 41)).isEqualByComparingTo("1.0075");
    assertThat(CombatPowerArkPassiveModel.evolution(c, 140)).isEqualByComparingTo("1.75");
    assertThat(c.evolutionMaxPoints()).isEqualTo(140);
    assertThat(c.enlightenmentLimitPoints()).isEqualTo(101);
  }

  @Test
  void 깨달음은_포인트당_0_7퍼센트이고_100을_넘으면_계산하지_않는다() throws Exception {
    var c = coefficients();
    assertThat(CombatPowerArkPassiveModel.enlightenment(c, 0)).isEqualByComparingTo("1");
    assertThat(CombatPowerArkPassiveModel.enlightenment(c, 100)).isEqualByComparingTo("1.70");
    assertThat(CombatPowerArkPassiveModel.enlightenment(c, 101)).isNull();
    assertThat(c.source()).isNotBlank();
    assertThat(c.enlightenmentPerPoint()).isEqualByComparingTo(new BigDecimal("0.007"));
  }
}
