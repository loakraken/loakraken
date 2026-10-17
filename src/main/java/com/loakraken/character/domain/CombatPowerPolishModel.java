package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 장신구 연마 줄의 전투력 배수(계산 검증 #325, 검증 표본 ref-a-polish-arkpassive-2026-10-11.json). 줄마다 독립 배수 (1 +
 * c/100)이고, 무기 공격력 줄은 재련 모델의 W 항(고정 N은 W에, %는 무공%에 더함)으로 넘긴다. 확인하지 않은 옵션·수치는 null이다(추정 금지).
 */
public final class CombatPowerPolishModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Pattern PERCENT = Pattern.compile("^\\+([\\d.]+)%$");
  private static final Pattern FLAT = Pattern.compile("^\\+([\\d,]+)$");

  /** 공격력 +N: c = N / 142,857 × 100(80·195·390 → 0.056·0.1365·0.273). */
  private static final BigDecimal ATTACK_FLAT_DIVISOR = BigDecimal.valueOf(142_857);

  /** 목걸이 추가 피해: c = x / 1.3(기존 추가 피해 30% 가정). */
  private static final BigDecimal ADDITIONAL_DAMAGE_DIVISOR = new BigDecimal("1.3");

  /** 반지 치명타 피해: c = 0.3 × x. */
  private static final BigDecimal CRIT_DAMAGE_FACTOR = new BigDecimal("0.3");

  /** 반지 치명타 적중률: 하·중·상 값별 c(선형이 아님). 표 밖 수치는 계산하지 않는다. */
  private static final Map<String, BigDecimal> CRIT_RATE =
      Map.of(
          "0.40", new BigDecimal("0.30968"),
          "0.95", new BigDecimal("0.73549"),
          "1.55", new BigDecimal("1.20001"));

  /** 딜러 전투력에 0인 옵션(생명력·마나·회복·보호막·상태이상·낙인력·아군·게이지). */
  private static final List<String> ZERO =
      List.of("생명력", "마나", "회복", "보호막", "상태이상", "낙인력", "아군", "게이지");

  private CombatPowerPolishModel() {}

  /** 연마 줄 하나의 몫. factor는 독립 배수, weaponFlat·weaponPct는 W 항에 더할 무기 공격력 고정값·비율(0.018 = 1.8%)이다. */
  public record Line(BigDecimal factor, BigDecimal weaponFlat, BigDecimal weaponPct) {
    static final Line NEUTRAL = new Line(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** 공식 툴팁 줄(이름, "+2.60%"·"+390")의 몫. 확인하지 않은 옵션이나 읽지 못한 수치는 null. */
  public static Line line(String name, String value) {
    if (name == null || value == null) return null;
    var option = name.strip();
    var text = value.strip();
    for (var zero : ZERO) if (option.contains(zero)) return Line.NEUTRAL;
    var percent = PERCENT.matcher(text);
    var flat = FLAT.matcher(text);
    BigDecimal x =
        percent.matches()
            ? new BigDecimal(percent.group(1))
            : flat.matches() ? new BigDecimal(flat.group(1).replace(",", "")) : null;
    if (x == null) return null;
    boolean pct = percent.matches();
    if (option.equals("무기 공격력"))
      return pct
          ? new Line(BigDecimal.ONE, BigDecimal.ZERO, x.divide(HUNDRED, MATH))
          : new Line(BigDecimal.ONE, x, BigDecimal.ZERO);
    BigDecimal c;
    if (option.equals("공격력")) c = pct ? x : x.divide(ATTACK_FLAT_DIVISOR, MATH).multiply(HUNDRED);
    else if (!pct) return null;
    else if (option.equals("추가 피해")) c = x.divide(ADDITIONAL_DAMAGE_DIVISOR, MATH);
    else if (option.startsWith("적에게 주는 피해")) c = x;
    else if (option.equals("치명타 피해")) c = x.multiply(CRIT_DAMAGE_FACTOR, MATH);
    else if (option.equals("치명타 적중률")) {
      c = null;
      for (var entry : CRIT_RATE.entrySet())
        if (new BigDecimal(entry.getKey()).compareTo(x) == 0) c = entry.getValue();
      if (c == null) return null;
    } else return null;
    return new Line(
        BigDecimal.ONE.add(c.divide(HUNDRED, MATH), MATH), BigDecimal.ZERO, BigDecimal.ZERO);
  }
}
