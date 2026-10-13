package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterLoadout.EffectGrade;
import com.loakraken.character.domain.CombatPowerPolishValues;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 연마 교체의 새 수치 고르기: 같은 이름의 %·고정값이 둘 다 있는 부위(귀걸이 공격력). */
class CombatPowerPolishValueTest {
  private static final CombatPowerPolishValues VALUES =
      new CombatPowerPolishValues(
          "test",
          Map.of(),
          "고대",
          1680,
          List.of(
              new CombatPowerPolishValues.Value(
                  "공격력", true, List.of("귀걸이"), "+0.40%", "+0.95%", "+1.55%"),
              new CombatPowerPolishValues.Value(
                  "공격력", false, List.of("목걸이", "귀걸이", "반지"), "+80", "+195", "+390")));

  @Test
  void 같은_줄의_등급만_바꾸면_지금_줄_값으로_퍼센트인지_고른다() {
    // 웹 시뮬레이터는 등급만 바꿀 때 옵션에 지금 줄 이름을 그대로 보낸다.
    var percentLine = new CharacterLoadout.PolishEffect("공격력", "+0.40%", EffectGrade.LOW);
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력", "HIGH", percentLine))
        .isEqualTo("+1.55%");
    var flatLine = new CharacterLoadout.PolishEffect("공격력", "+80", EffectGrade.LOW);
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력", "HIGH", flatLine))
        .isEqualTo("+390");
  }

  @Test
  void 옵션을_바꿀_때는_끝의_퍼센트_표시로_고른다() {
    var other = new CharacterLoadout.PolishEffect("무기 공격력", "+195", EffectGrade.LOW);
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력 %", "MID", other))
        .isEqualTo("+0.95%");
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "귀걸이", "공격력", "MID", other))
        .isEqualTo("+195");
    // 부위에 한 줄뿐이면 표시와 관계없이 그 줄이다.
    assertThat(CombatPowerSimulationService.polishValue(VALUES, "목걸이", "공격력", "LOW", other))
        .isEqualTo("+80");
  }
}
