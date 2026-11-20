package com.loakraken.specup.domain;

import java.util.Map;

/** 깨달음 카르마·어빌리티 스톤 후보의 기대 골드(비교 기준 B 원본 식과 같은 계산 순서, 배정밀도). 금액은 반올림 전 값이며 효율은 이 값으로 나눈다(B와 같음). */
public final class SpecUpCostRules {
  private SpecUpCostRules() {}

  /** 카르마 레벨 시도 1회 골드. */
  public static final double KARMA_ATTEMPT_GOLD = 900;

  /** 1→2 … 24→25: 이 네 단계(성공률·실패 시 장인의 기운, bp)가 여섯 번 되풀이된다. */
  private static final int[][] KARMA_CYCLE = {{2000, 1000}, {1500, 750}, {1000, 500}, {700, 350}};

  /** 25→26 … 29→30(성공률·실패 시 장인의 기운, bp). */
  private static final int[][] KARMA_LAST = {{400, 180}, {200, 90}, {100, 40}, {50, 20}, {20, 8}};

  /**
   * 어빌리티 스톤 목표 조합(세공 성공 수, 예: 97돌 = 9·7)의 성공 확률(PM 총괄 11-19 (e), 계산 검증 '(e) 스톤 비용 공식 세공 규칙 대조
   * 2026-11-19').
   *
   * <p>출처: 로스트아크 확률 정보 공개 — 어빌리티 스톤 자동 세공, https://lostark.game.onstove.com/Probability/어빌리티 스톤 자동
   * 세공, 최근 수정일 2025-12-10 08:00:00, 세공 기회 10회 표. 고정 방향(첫째 증가 각인 ≥ x, 둘째 ≥ y, 레벨 Lv1 6 / Lv2 7 / Lv3
   * 9 / Lv4 10 성공, 감소 각인 제한 없음)의 합. 표가 대칭이라 이름마다 값 하나.
   *
   * <p>가정(비교 기준에서 가져옴): 고대 스톤은 증가 각인마다 세공 10회, 키트(돌) 1개 50골드, 페온 비용은 넣지 않음.
   */
  private static final Map<String, Double> STONE_SUCCESS =
      Map.of(
          "97돌", 5.95277252363659e-4,
          "106돌", 1.13500515579666e-4,
          "107돌", 1.77111141166665e-5,
          "99돌", 2.90215230371851e-6,
          "109돌", 6.75363837265100e-8,
          "1010돌", 1.49833007451000e-9);

  public static final double STONE_KIT_GOLD = 50;

  /**
   * 카르마 지금 레벨에서 목표 레벨까지 기대 골드 = 단계마다 기대 시도 × 900의 합. 0레벨에서 시작해도 0→1 단계는 세지 않는다(B와 같음). 범위 밖이면 null.
   */
  public static Double karmaGold(int current, int target) {
    if (current < 0 || target > 30 || current >= target) return null;
    double gold = 0;
    for (int level = Math.max(current, 1); level < target; level++) {
      var stage =
          level <= 24 ? KARMA_CYCLE[(level - 1) % KARMA_CYCLE.length] : KARMA_LAST[level - 25];
      gold +=
          karmaExpectedAttempts(stage[0] / 1e4, (int) Math.ceil(1e4 / stage[1]))
              * KARMA_ATTEMPT_GOLD;
    }
    return gold;
  }

  /** 한 단계 기대 시도: 천장(실패 pity번 뒤 성공)까지 Σ (1−p)^l·p·(l+1) + (1−p)^pity·(pity+1). */
  static double karmaExpectedAttempts(double rate, int pity) {
    double fail = 1 - rate;
    double reach = 1;
    double attempts = 0;
    for (int l = 0; l < pity; l++) {
      attempts += reach * rate * (l + 1);
      reach *= fail;
    }
    return attempts + reach * (pity + 1);
  }

  /** 목표 조합까지 기대 키트 수 × 50골드(키트 수 = 1 ÷ 성공 확률). 표에 없는 조합이면 null. */
  public static Double stoneGold(String label) {
    var success = STONE_SUCCESS.get(label);
    return success == null ? null : STONE_KIT_GOLD * (1 / success);
  }
}
