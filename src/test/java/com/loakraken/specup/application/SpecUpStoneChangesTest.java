package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.character.domain.CharacterLoadout;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** 스톤 줄의 시뮬레이터 변경: 장착한 스톤의 각인 줄에 있는 이름만(없는 각인은 시뮬레이터가 오류로 돌려주므로 null → 점수만). 합성 장비. */
class SpecUpStoneChangesTest {
  private static CharacterLoadout loadout(String... names) {
    var stone = mock(CharacterLoadout.Equipment.class);
    var detail = mock(CharacterLoadout.EquipmentDetail.class);
    when(stone.detail()).thenReturn(detail);
    var lines = new java.util.ArrayList<CharacterLoadout.StoneEngraving>();
    for (var name : names) lines.add(new CharacterLoadout.StoneEngraving(name, 1));
    when(detail.stoneEngravings()).thenReturn(lines);
    var loadout = mock(CharacterLoadout.class);
    when(loadout.equipment()).thenReturn(java.util.Arrays.asList(null, stone));
    return loadout;
  }

  @Test
  void 스톤_각인마다_목표_레벨의_STONE_ENGRAVING이다() {
    var levels = new LinkedHashMap<String, Integer>();
    levels.put("아드레날린", 2);
    levels.put("원한", 3);
    var changes = SpecUpService.stoneChanges(loadout("아드레날린", "원한", "공격력 감소"), levels);
    assertThat(changes).hasSize(2);
    assertThat(changes).allMatch(change -> change.kind() == Kind.STONE_ENGRAVING);
    assertThat(changes.stream().map(change -> change.name() + ":" + change.level()).toList())
        .containsExactly("아드레날린:2", "원한:3");
  }

  @Test
  void 장착한_스톤에_없는_각인이면_변경을_만들지_않는다() {
    var levels = new LinkedHashMap<String, Integer>();
    levels.put("아드레날린", 2);
    levels.put("원한", 3);
    assertThat(SpecUpService.stoneChanges(loadout("아드레날린", "돌격대장"), levels)).isNull();
    assertThat(SpecUpService.stoneChanges(loadout(), levels)).isNull();
    assertThat(SpecUpService.stoneChanges(loadout("원한"), new LinkedHashMap<>())).isNull();
  }
}
