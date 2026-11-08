package com.loakraken.character.domain;

import java.math.BigDecimal;

/** 4티어 장신구 기본 효과 힘민지가 그 연마 단계 범위의 몇 %인지(흡수 #6). */
public record AccessoryBaseStat(
    int polishStage, int mainStat, int mainStatMin, int mainStatMax, BigDecimal mainStatRangePct) {

  /** 장신구가 아니거나 해석할 수 없으면 null. (red 단계 스텁) */
  public static AccessoryBaseStat of(
      String type, String grade, CharacterLoadout.EquipmentDetail detail) {
    return null;
  }

  /** 힘민지가 그 단계 범위 밖이라 %를 내지 않았는가(범위표 점검 대상). */
  public boolean outOfRange() {
    return mainStatRangePct == null;
  }
}
