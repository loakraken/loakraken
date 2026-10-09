package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LostArkSkillTooltipTest {
  @Test
  void 부위_파괴_레벨과_보석_효과를_해석하고_카운터가_없으면_false다() {
    var parsed =
        LostArkSkillTooltip.parse(
            """
            {"Element_000":{"type":"NameTagBox","value":"<FONT COLOR='#FFD200'>테스트 스킬</FONT>"},
             "Element_003":{"type":"SingleTextBox","value":"<FONT SIZE='12'>스킬 레벨 14</FONT>"},
             "Element_005":{"type":"SingleTextBox","value":"<BR><FONT COLOR='#C0C0C0'>부위 파괴 : 레벨 1</FONT><BR>무력화 : 하<BR>공격 타입 : 백 어택"},
             "Element_007":{"type":"ItemPartBox","value":{"Element_000":"스킬 룬 효과","Element_001":"[광분] 스킬 사용 시 공격 속도 16% 증가"}},
             "Element_008":{"type":"ItemPartBox","value":{"Element_000":"<FONT COLOR='#A9D0F5'>보석 효과</FONT>",
               "Element_001":"[블레이드] 테스트 스킬 피해 <FONT COLOR='#99ff99'>28.00%</FONT> 증가<BR>[블레이드] 테스트 스킬 재사용 대기시간 16.00% 감소<BR>[블레이드] 기타 효과 "}}}
            """);
    assertThat(parsed.partBreakLevel()).isEqualTo(1);
    assertThat(parsed.counter()).isFalse();
    assertThat(parsed.attackType()).isEqualTo(CharacterLoadout.AttackType.BACK_ATTACK);
    assertThat(parsed.gemEffects())
        .containsExactly(
            new CharacterLoadout.SkillGemEffect(
                "[블레이드] 테스트 스킬 피해 28.00% 증가", CharacterLoadout.GemEffectKind.DAMAGE),
            new CharacterLoadout.SkillGemEffect(
                "[블레이드] 테스트 스킬 재사용 대기시간 16.00% 감소", CharacterLoadout.GemEffectKind.COOLDOWN),
            new CharacterLoadout.SkillGemEffect("[블레이드] 기타 효과", null));
  }

  @Test
  void 카운터_가능은_true이고_부위_파괴가_없으면_null이다() {
    var parsed =
        LostArkSkillTooltip.parse(
            """
            {"Element_005":{"type":"SingleTextBox","value":"<BR>카운터 : 가능 <BR>공격 타입 : 헤드 어택"},
             "Element_006":{"type":"TripodSkillCustom","value":{"Element_000":{"desc":"카운터 공격 강화","name":"트라이포드"}}}}
            """);
    assertThat(parsed.counter()).isTrue();
    assertThat(parsed.partBreakLevel()).isNull();
    assertThat(parsed.attackType()).isEqualTo(CharacterLoadout.AttackType.HEAD_ATTACK);
    assertThat(parsed.gemEffects()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  ", "not-json", "[1]", "\"text\""})
  void 해석할_수_없는_Tooltip은_카운터와_부위_파괴를_null로_둔다(String tooltip) {
    var parsed = LostArkSkillTooltip.parse(tooltip);
    assertThat(parsed.partBreakLevel()).isNull();
    assertThat(parsed.counter()).isNull();
    assertThat(parsed.gemEffects()).isEmpty();
    assertThat(LostArkSkillTooltip.parse(null).counter()).isNull();
    assertThat(LostArkSkillTooltip.parse(null).attackType()).isNull();
    assertThat(
            LostArkSkillTooltip.parse(
                    "{\"Element_005\":{\"type\":\"SingleTextBox\",\"value\":\"무력화 : 하\"}}")
                .attackType())
        .isEqualTo(CharacterLoadout.AttackType.NONE);
    assertThat(
            LostArkSkillTooltip.parse(
                    "{\"Element_005\":{\"type\":\"SingleTextBox\",\"value\":\"공격 타입 : 백 어택\"}}")
                .attackType())
        .isEqualTo(CharacterLoadout.AttackType.BACK_ATTACK);
  }

  /** 공식 스킬 Tooltip의 SingleTextBox 형식(언새늘 블레이드 2026-10-09 응답의 줄 구성. 원문 전체가 아닌 해당 줄만). */
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "<FONT COLOR='#C0C0C0'>부위 파괴 : 레벨 1</FONT><BR>무력화 : 하<BR>공격 타입 : 백 어택|BACK_ATTACK",
        "<BR>슈퍼아머 : 경직 면역<BR>무력화 : 중<BR>공격 타입 : 헤드 어택|HEAD_ATTACK",
        "<BR>슈퍼아머 : 피격 이상 면역<BR>무력화 : 상|NONE"
      })
  void 실제_형식의_공격_타입_줄을_구조화한다(String line, CharacterLoadout.AttackType expected) {
    var tooltip =
        "{\"Element_005\":{\"type\":\"SingleTextBox\",\"value\":\""
            + line.replace("\"", "\\\"")
            + "\"}}";
    assertThat(LostArkSkillTooltip.parse(tooltip).attackType()).isEqualTo(expected);
  }
}
