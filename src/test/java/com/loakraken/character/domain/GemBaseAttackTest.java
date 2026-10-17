package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class GemBaseAttackTest {
  @Test
  void 공식_보석_효과_문구가_있으면_그_값을_쓴다() {
    var value = GemBaseAttack.of(gem("8레벨 겁화의 보석", 8, 4), "기본 공격력 0.80% 증가");
    assertThat(value.pct()).isEqualByComparingTo("0.80");
    assertThat(value.source()).isEqualTo(GemBaseAttack.TOOLTIP);
  }

  @Test
  void 문구가_없으면_4티어_6에서_10레벨만_레벨_표로_채운다() {
    var table = GemBaseAttack.of(gem("10레벨 광휘의 보석 (귀속)", 10, null), null);
    assertThat(table.pct()).isEqualByComparingTo("1.20");
    assertThat(table.source()).isEqualTo(GemBaseAttack.LEVEL_TABLE);
    assertThat(GemBaseAttack.of(gem("7레벨 작열의 보석", 7, 4), "다른 문구").pct())
        .isEqualByComparingTo("0.60");
    // 근거 없는 레벨·3티어는 비운다.
    assertThat(GemBaseAttack.of(gem("5레벨 겁화의 보석", 5, 4), null)).isNull();
    assertThat(GemBaseAttack.of(gem("10레벨 멸화의 보석", 10, 3), null)).isNull();
    assertThat(GemBaseAttack.of(null, "기본 공격력 0.80% 증가")).isNull();
  }

  @Test
  void 레벨_표는_전투력_보석_계수표와_같다() throws Exception {
    CombatPowerGemModel.Coefficients coefficients;
    try (var input = new ClassPathResource("combat-power/gem-coefficients.json").getInputStream()) {
      coefficients = new JsonMapper().readValue(input, CombatPowerGemModel.Coefficients.class);
    }
    var tier = coefficients.tierOf("겁화");
    for (int level = 1; level <= 10; level++) {
      int current = level;
      BigDecimal expected =
          tier.levels().stream()
              .filter(value -> value.level() == current)
              .findFirst()
              .orElseThrow()
              .baseAttackPct();
      var actual = GemBaseAttack.TIER4.get(level);
      if (expected == null) assertThat(actual).as("레벨 %d", level).isNull();
      else assertThat(actual).as("레벨 %d", level).isEqualByComparingTo(expected);
    }
  }

  private static CharacterLoadout.Gem gem(String name, int level, Integer tier) {
    return new CharacterLoadout.Gem(0, name, null, level, "고대", List.of(), null, null, tier, List.of());
  }
}
