package com.loakraken.market.domain;

import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * T4 장신구 기본 효과 범위(체크리스트 L11·N13).
 *
 * <p>출처: 공식 아이템 사전(https://lostark.game.onstove.com/ItemDictionary) 장신구 "연마 효과" 탭의 연마 단계별 기본 효과
 * 범위(2026-10-12 확인, 검증 표본 {@code verification/accessory/accessory-stat-range-2026-10-12.json}). 4티어
 * 연마 가능 장신구 12종의 힘민지 범위는 부위와 연마 단계로만 정해지고, 깨달음 범위만 등급별로 다르다.
 *
 * <p>범위 % = (힘민지 − 그 연마 단계 최솟값) ÷ (최댓값 − 최솟값) × 100, 소수 둘째 자리. 연마 단계 = 연마 효과 줄 수(0~3). 언새늘 장신구 5개가
 * 비교 기준값과 같다(31.02·94.05·11.04·65.01·56.02).
 */
public final class AccessoryStatRange {
  public static final int MAX_STAGE = 3;

  // [부위][연마 단계] = {최솟값, 최댓값}
  private static final int[][][] MAIN_STAT = {
    {{12678, 15357}, {13035, 15714}, {13749, 16428}, {15178, 17857}},
    {{9861, 11944}, {10139, 12222}, {10695, 12778}, {11806, 13889}},
    {{9156, 11091}, {9414, 11349}, {9930, 11865}, {10962, 12897}}
  };

  // [등급 유물·고대][부위][연마 단계] = {최솟값, 최댓값}
  private static final int[][][][] ENLIGHTENMENT = {
    {
      {{2, 4}, {3, 5}, {5, 7}, {8, 10}},
      {{1, 3}, {2, 4}, {4, 6}, {7, 9}},
      {{1, 3}, {2, 4}, {4, 6}, {7, 9}}
    },
    {
      {{2, 4}, {4, 6}, {7, 9}, {11, 13}},
      {{1, 3}, {3, 5}, {6, 8}, {10, 12}},
      {{1, 3}, {3, 5}, {6, 8}, {10, 12}}
    }
  };

  private AccessoryStatRange() {}

  public static int mainStatMin(Slot slot, int stage) {
    return MAIN_STAT[slot.ordinal()][stage][0];
  }

  public static int mainStatMax(Slot slot, int stage) {
    return MAIN_STAT[slot.ordinal()][stage][1];
  }

  /** 범위 %. 연마 단계가 0~3 밖이거나 힘민지가 없으면 null. 범위를 벗어난 값도 그대로 계산한다(공식 원문 값). */
  public static BigDecimal mainStatRangePct(Slot slot, int stage, Integer mainStat) {
    if (mainStat == null || stage < 0 || stage > MAX_STAGE) return null;
    int min = mainStatMin(slot, stage);
    int width = mainStatMax(slot, stage) - min;
    return BigDecimal.valueOf((long) mainStat - min)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(width), 2, RoundingMode.HALF_UP);
  }

  /**
   * 범위 %가 pct 이상인 매물이 가져야 할 힘민지 하한. 단계마다 폭이 같고 최솟값은 단계가 오를수록 크므로, 0단계 기준 하한은 모든 단계의 필요조건이다(서버 필터로
   * 미리 좁히는 데만 쓴다).
   */
  public static int mainStatLowerBound(Slot slot, int pct) {
    int min = mainStatMin(slot, 0);
    int width = mainStatMax(slot, 0) - min;
    return min + (int) Math.floor(width * pct / 100.0);
  }

  /** 등급·부위에서 나올 수 있는 깨달음 최댓값(3단계 최댓값). */
  public static int enlightenmentMax(Grade grade, Slot slot) {
    return ENLIGHTENMENT[grade == Grade.RELIC ? 0 : 1][slot.ordinal()][MAX_STAGE][1];
  }
}
