package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 화면에 그대로 나가는 이유 문장에는 '출처'를 쓰지 않는다(웹 규칙 A13, 프론트2 11-22). 코드 분류는 그대로. */
class CombatPowerReasonsTextTest {
  @Test
  void 기본_공격력_이유_문장에_출처가_없고_코드_분류는_그대로다() {
    assertThat(CombatPowerReasons.BASE_ATTACK_SOURCE_UNKNOWN.text()).doesNotContain("출처");
    var modelSentence = "확인하지 않은 기본 공격력 % 효과(「기본 공격력 +0.80%」)가 있어 보석 변화는 계산하지 않습니다.";
    assertThat(CombatPowerReasons.classify(modelSentence))
        .isEqualTo(CombatPowerReasons.BASE_ATTACK_SOURCE_UNKNOWN);
    assertThat(CombatPowerReasons.classify(CombatPowerReasons.BASE_ATTACK_SOURCE_UNKNOWN.text()))
        .isEqualTo(CombatPowerReasons.BASE_ATTACK_SOURCE_UNKNOWN);
  }
}
