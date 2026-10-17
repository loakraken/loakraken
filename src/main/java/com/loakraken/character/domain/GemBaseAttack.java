package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 보석 하나의 '기본 공격력 n% 증가'(4티어 보석 추가 효과). 공식 보석 효과 문구(armory Effects.Skills의 Option)를 먼저 읽고, 문구가 없을 때만
 * 레벨 표로 채운다. 표는 공식 보석 툴팁에서 확인한 6~10레벨 값이고 combat-power/gem-coefficients.json의 baseAttackPct와
 * 같다(테스트로 고정). 1~5레벨과 3티어는 근거가 없어 null이다.
 */
public final class GemBaseAttack {
  public static final String TOOLTIP = "TOOLTIP";
  public static final String LEVEL_TABLE = "LEVEL_TABLE";
  static final Map<Integer, BigDecimal> TIER4 =
      Map.of(
          6, new BigDecimal("0.45"),
          7, new BigDecimal("0.60"),
          8, new BigDecimal("0.80"),
          9, new BigDecimal("1.00"),
          10, new BigDecimal("1.20"));
  private static final Pattern OPTION = Pattern.compile("기본 공격력 ([\\d.]+)% 증가");
  private static final Pattern TIER4_KIND = Pattern.compile("(겁화|작열|광휘)의 보석");

  /** source는 TOOLTIP(공식 문구) 또는 LEVEL_TABLE(레벨 표). */
  public record Value(BigDecimal pct, String source) {}

  private GemBaseAttack() {}

  /** option: 그 칸의 공식 보석 효과 Option 문구(없으면 null). 근거가 없으면 null. */
  public static Value of(CharacterLoadout.Gem gem, String option) {
    if (gem == null) return null;
    if (option != null) {
      var matcher = OPTION.matcher(option);
      if (matcher.find()) return new Value(new BigDecimal(matcher.group(1)), TOOLTIP);
    }
    boolean tier4 =
        (gem.tier() != null && gem.tier() == 4)
            || (gem.name() != null && TIER4_KIND.matcher(gem.name()).find());
    var pct = tier4 && gem.level() != null ? TIER4.get(gem.level()) : null;
    return pct == null ? null : new Value(pct, LEVEL_TABLE);
  }
}
