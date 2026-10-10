package com.loakraken.character.domain;

/** 다른 패키지 테스트가 쓰는 장비 요약 합성 데이터. */
public final class EquipmentSummaryTestData {
  private EquipmentSummaryTestData() {}

  public static EquipmentSummary summary(String combatPower, boolean shuffled) {
    return EquipmentSummary.from(
        EquipmentSummaryTest.profile(combatPower), EquipmentSummaryTest.loadout(shuffled));
  }
}
