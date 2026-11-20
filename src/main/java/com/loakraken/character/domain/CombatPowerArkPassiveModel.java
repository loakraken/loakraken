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
   * = 깨달음 포인트 최대(넘으면 요청 오류), enlightenmentMaxPoints = 확인한 깨달음 포인트 상한(넘으면 계산하지 않음).
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

  /** 깨달음 포인트 P의 배수: 1 + c × P. 확인한 상한을 넘으면 null(계산하지 않음). */
  public static BigDecimal enlightenment(Coefficients coefficients, int points) {
    if (points > coefficients.enlightenmentMaxPoints()) return null;
    return BigDecimal.ONE.add(
        coefficients.enlightenmentPerPoint().multiply(BigDecimal.valueOf(points), MATH), MATH);
  }
}
