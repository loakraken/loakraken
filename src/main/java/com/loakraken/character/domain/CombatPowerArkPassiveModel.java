package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 아크 패시브 진화·깨달음 포인트의 전투력 배수(독립 배수, 바꾼 비율을 공식 전투력에 곱한다). 계수 리소스
 * combat-power/ark-passive-coefficients.json, 근거는 그 source에 있다. 도약은 팔찌 계수(leapPerPoint)를 쓴다.
 */
public final class CombatPowerArkPassiveModel {
  private static final MathContext MATH = MathContext.DECIMAL64;

  private CombatPowerArkPassiveModel() {}

  /**
   * evolutionMaxPoints = 진화 포인트 최대(넘으면 요청 오류), evolutionFreePoints = 전투력이 없는 진화 1티어 포인트,
   * evolutionPerPoint = 그 위 포인트당 배수, enlightenmentPerPoint = 깨달음 포인트당 배수, enlightenmentLimitPoints
   * = 깨달음 포인트 최대(넘으면 요청 오류), enlightenmentMaxPoints = 전투력에 세는 깨달음 포인트 상한(그 위 포인트는 전투력이 없다, M13 PM
   * 총괄 11-22 — 캐릭터 25명 공식 전투력 대조).
   */
  public record Coefficients(
      String ruleVersion,
      String source,
      int evolutionMaxPoints,
      int evolutionFreePoints,
      BigDecimal evolutionPerPoint,
      BigDecimal enlightenmentPerPoint,
      int enlightenmentMaxPoints,
      int enlightenmentLimitPoints) {}

  /** 진화 포인트 P의 배수: 1 + c × max(P − 무료 포인트, 0). */
  public static BigDecimal evolution(Coefficients coefficients, int points) {
    int counted = Math.max(points - coefficients.evolutionFreePoints(), 0);
    return BigDecimal.ONE.add(
        coefficients.evolutionPerPoint().multiply(BigDecimal.valueOf(counted), MATH), MATH);
  }

  /**
   * 깨달음 포인트 P의 배수: 1 + c × min(P, 상한 100). 101번째 포인트는 전투력이 없다(비교 기준 식과 같고, 101인 캐릭터 25명이 이 규칙에서 공식
   * 전투력과 0.000% — 세면 모두 −0.41% 어긋남). 요청 상한(101)을 넘으면 null.
   */
  public static BigDecimal enlightenment(Coefficients coefficients, int points) {
    if (points > coefficients.enlightenmentLimitPoints()) return null;
    int counted = Math.min(points, coefficients.enlightenmentMaxPoints());
    return BigDecimal.ONE.add(
        coefficients.enlightenmentPerPoint().multiply(BigDecimal.valueOf(counted), MATH), MATH);
  }
}
