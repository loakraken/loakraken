package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout.GemEffectKind;
import com.loakraken.character.domain.CharacterLoadout.SkillGemEffect;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 보석 반영 쿨타임(#377). 문구 형식은 공식 armory combat-skills Tooltip(#299)과 같다. */
class SkillCooldownTest {
  @Test
  void 공식_재사용_대기시간에_보석_쿨감을_곱한다() {
    var cooldown =
        SkillCooldown.of(
            skill(
                List.of("일반", "재사용 대기시간 8초", "[일반 스킬]"),
                List.of(
                    new SkillGemEffect("[블레이드] 어퍼 슬래쉬 피해 28.00% 증가", GemEffectKind.DAMAGE),
                    new SkillGemEffect(
                        "[블레이드] 어퍼 슬래쉬 재사용 대기시간 16.00% 감소", GemEffectKind.COOLDOWN))));
    assertThat(cooldown.cooldownSec()).isEqualByComparingTo("8");
    assertThat(cooldown.gemCooldownReductionPct()).isEqualByComparingTo("16");
    assertThat(cooldown.cooldownWithGemsSec()).isEqualByComparingTo("6.72");
  }

  @Test
  void 보석이_없으면_원문_그대로이고_원문이_없으면_null이다() {
    var plain = SkillCooldown.of(skill(List.of("콤보", "재사용 대기시간 12초", "[일반 스킬]"), List.of()));
    assertThat(plain.gemCooldownReductionPct()).isZero();
    assertThat(plain.cooldownWithGemsSec()).isEqualByComparingTo("12");
    assertThat(SkillCooldown.of(skill(List.of(), List.of()))).isNull();
    var minutes = SkillCooldown.of(skill(List.of("일반", "재사용 대기시간 1분 30초"), List.of()));
    assertThat(minutes.cooldownSec()).isEqualByComparingTo("90");
  }

  @Test
  void 계산_검증_표본_51개와_같다() throws Exception {
    var mapper = new JsonMapper();
    try (var in =
        getClass()
            .getResourceAsStream("/verification/skill/official-gem-cooldown-2026-10-12.json")) {
      var samples = mapper.readTree(in).path("samples");
      int count = 0;
      for (var name : samples.propertyNames()) {
        for (var row : samples.path(name)) {
          var expected = row.path("gemCd").decimalValue();
          var actual =
              SkillCooldown.withGems(
                  row.path("officialCd").decimalValue(), row.path("gemCdr").decimalValue());
          assertThat(actual)
              .as(name + " " + row.path("skill").asText())
              .isEqualByComparingTo(expected);
          count++;
        }
      }
      assertThat(count).isEqualTo(51);
    }
  }

  private static CharacterLoadout.Skill skill(List<String> titleLines, List<SkillGemEffect> gems) {
    return new CharacterLoadout.Skill(
        "스킬", null, 14, null, null, null, List.of(), null, gems, null, null, titleLines, List.of());
  }
}
