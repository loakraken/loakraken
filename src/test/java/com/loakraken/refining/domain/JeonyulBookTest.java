package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** #218 B: 전율 책 증가 = 그 단계 기본 성공률(계산 검증 2026-10-10, 로아와·일로아 일치). */
class JeonyulBookTest {
  @Test
  void 전율_책은_12_19단계에서_그_단계_기본_성공률만큼_더하고_20단계부터는_없다() throws IOException {
    RefiningRules rules;
    try (var input =
        JeonyulBookTest.class.getResourceAsStream("/refining/t4-refining-rules.json")) {
      rules = JsonMapper.builder().build().readValue(input, RefiningRules.class);
    }
    assertThat(rules.ruleVersion()).isEqualTo("t4-refining-v2");
    var weapon = rules.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.WEAPON);
    var armor = rules.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.ARMOR);
    for (int stage = 12; stage <= 19; stage++) {
      var rule = weapon.stage(stage);
      assertThat(rule.book().rateBp()).isEqualTo(rule.baseRateBp());
      assertThat(rule.book().name()).startsWith("야금술 : 전율 [" + (stage <= 15 ? "12-15" : "16-19"));
      assertThat(armor.stage(stage).book().name()).startsWith("재봉술 : 전율");
    }
    assertThat(weapon.stage(12).book().rateBp()).isEqualTo(500);
    assertThat(weapon.stage(17).book().rateBp()).isEqualTo(300);
    assertThat(weapon.stage(20).book()).isNull();
    // 첫 시도: 기본 3% + 숨결 + 책 3%(로아와 표본 18→19 무기 숨결 + 책 9%)
    var plan =
        RefiningPlan.calculate(
            weapon,
            RefiningRules.Grade.JEONYUL,
            new RefiningPlan.Options(18, 19, true, true, false, 0, 0));
    assertThat(plan.stages().getFirst().firstAttemptSuccessRateBp()).isEqualTo(900);
  }
}
