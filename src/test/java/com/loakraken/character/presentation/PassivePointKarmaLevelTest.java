package com.loakraken.character.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerRefineModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 아크 패시브 포인트의 카르마 레벨(프론트 #1815 후속): 웹이 설명 글을 다시 읽지 않게 응답에 숫자로 준다. */
class PassivePointKarmaLevelTest {
  @Test
  void 포인트마다_설명의_카르마_레벨을_준다() {
    var response =
        CharacterLoadoutResponse.ArkPassiveResponse.from(
            new CharacterLoadout.ArkPassive(
                "아크 패시브",
                true,
                List.of(
                    new CharacterLoadout.PassivePoint("진화", 140, "6랭크 30레벨"),
                    new CharacterLoadout.PassivePoint("깨달음", 101, "6랭크 29레벨"),
                    new CharacterLoadout.PassivePoint("도약", 70, "0랭크 0레벨"),
                    new CharacterLoadout.PassivePoint("새 줄", 1, "레벨 없음"),
                    new CharacterLoadout.PassivePoint("빈 설명", 1, null)),
                List.of()));
    assertThat(response.points())
        .extracting(CharacterLoadoutResponse.PassivePointResponse::karmaLevel)
        .containsExactly(30, 29, 0, null, null);
  }

  @Test
  void 깨달음_카르마_레벨은_계산과_같은_값이다() {
    var loadout =
        new CharacterLoadout(
            List.of(),
            null,
            null,
            null,
            new CharacterLoadout.ArkPassive(
                "아크 패시브",
                true,
                List.of(new CharacterLoadout.PassivePoint("깨달음", 101, "6랭크 29레벨")),
                List.of()),
            null,
            List.of());
    var response = CharacterLoadoutResponse.ArkPassiveResponse.from(loadout.arkPassive());
    assertThat(response.points().getFirst().karmaLevel())
        .isEqualTo(CombatPowerRefineModel.karmaLevel(loadout))
        .isEqualTo(29);
  }
}
