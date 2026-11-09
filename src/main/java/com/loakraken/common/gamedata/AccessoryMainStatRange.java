package com.loakraken.common.gamedata;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * T4 장신구 기본 효과 힘민지 범위(체크리스트 L11·N13·N15). 시세 장신구 검색과 캐릭터 장비 상세가 같은 표를 쓴다.
 *
 * <p>출처: 공식 아이템 사전(https://lostark.game.onstove.com/ItemDictionary) 장신구 "연마 효과" 탭의 연마 단계별 기본 효과
 * 범위(2026-10-12 확인, 검증 표본 {@code verification/accessory/accessory-stat-range-2026-10-12.json}). 4티어
 * 연마 가능 장신구 12종의 힘민지 범위는 부위와 연마 단계로만 정해진다.
 *
 * <p>범위 % = (힘민지 − 그 연마 단계 최솟값) ÷ (최댓값 − 최솟값) × 100, 소수 둘째 자리. 연마 단계 = 연마 효과 줄 수(0~3). 기준 캐릭터 장신구
 * 5개가 비교 기준값과 같다(31.02·94.05·11.04·65.01·56.02).
 */
public final class AccessoryMainStatRange {
  public static final int MAX_STAGE = 3;

  /** 부위. 순서가 아래 표의 첫 번째 색인이다. */
  public enum Slot {
    NECKLACE("목걸이"),
    EARRING("귀걸이"),
    RING("반지");

    private final String official;

    Slot(String official) {
      this.official = official;
    }

    /** 공식 장비 종류 이름(목걸이·귀걸이·반지). 아니면 null. */
    public static Slot fromOfficial(String type) {
      for (var slot : values()) if (slot.official.equals(type)) return slot;
      return null;
    }
  }

  // [부위][연마 단계] = {최솟값, 최댓값}
  private static final int[][][] MAIN_STAT = {
    {{12678, 15357}, {13035, 15714}, {13749, 16428}, {15178, 17857}},
    {{9861, 11944}, {10139, 12222}, {10695, 12778}, {11806, 13889}},
    {{9156, 11091}, {9414, 11349}, {9930, 11865}, {10962, 12897}}
  };

  private AccessoryMainStatRange() {}

  public static int min(Slot slot, int stage) {
    return MAIN_STAT[slot.ordinal()][stage][0];
  }

  public static int max(Slot slot, int stage) {
    return MAIN_STAT[slot.ordinal()][stage][1];
  }

  /** 범위 %. 연마 단계가 0~3 밖이거나 힘민지가 없으면 null. 범위를 벗어난 값도 그대로 계산한다(공식 원문 값). */
  public static BigDecimal rangePct(Slot slot, int stage, Integer mainStat) {
    if (mainStat == null || stage < 0 || stage > MAX_STAGE) return null;
    int min = min(slot, stage);
    int width = max(slot, stage) - min;
    return BigDecimal.valueOf((long) mainStat - min)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(width), 2, RoundingMode.HALF_UP);
  }
}
