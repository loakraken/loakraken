package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 연마 효과 줄 뽑기: 장신구(목걸이·귀걸이·반지)의 연마 효과만, 장신구 아이템 레벨·등급·효과 등급(색)과 함께. */
class CharacterCardServicePolishLinesTest {
  private static CharacterLoadout.Equipment item(
      String type, String grade, Integer itemLevel, List<CharacterLoadout.PolishEffect> polish) {
    return new CharacterLoadout.Equipment(
        type,
        type + " 이름",
        null,
        grade,
        new CharacterLoadout.EquipmentDetail(
            null, 90, itemLevel, 4, null, List.of(), List.of(), polish, List.of()));
  }

  private static CharacterLoadout loadout(List<CharacterLoadout.Equipment> equipment) {
    return new CharacterLoadout(equipment, null, null, null, null, null);
  }

  @Test
  void 장신구_연마_효과만_뽑고_등급과_레벨을_붙인다() {
    var lines =
        CharacterCardService.polishLines(
            loadout(
                List.of(
                    item(
                        "목걸이",
                        "고대",
                        1680,
                        List.of(
                            new CharacterLoadout.PolishEffect(
                                " 추가 피해 ", " +2.60% ", CharacterLoadout.EffectGrade.HIGH),
                            new CharacterLoadout.PolishEffect("공격력", "+390", null))),
                    item(
                        "무기",
                        "고대",
                        1700,
                        List.of(
                            new CharacterLoadout.PolishEffect(
                                "무시", "+1", CharacterLoadout.EffectGrade.LOW))))));
    assertThat(lines)
        .containsExactly(
            new CharacterCardPort.PolishLine("목걸이", 1680, "고대", "추가 피해", "+2.60%", "HIGH"),
            new CharacterCardPort.PolishLine("목걸이", 1680, "고대", "공격력", "+390", null));
  }

  @Test
  void 장비를_관측하지_않았으면_null이다() {
    assertThat(CharacterCardService.polishLines(loadout(List.of()))).isNull();
  }
}
