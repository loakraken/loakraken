package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;

/** 시험 전용: 다른 패키지 시험이 실제 공식 Tooltip 원문을 같은 파서로 읽게 한다. */
public final class LostArkEquipmentTooltipAccess {
  private LostArkEquipmentTooltipAccess() {}

  public static CharacterLoadout.EquipmentDetail parse(String name, String tooltip) {
    return LostArkEquipmentTooltip.parse(name, tooltip);
  }
}
