package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 딜러 아크 그리드 전투력 배수(L29·L39). 계수는 combat-power/ark-grid-coefficients.json(계산 검증
 * #323·#335·#358·#368).
 *
 * <ul>
 *   <li>코어 배수 = 1 + C(P)/100. C는 코어 계열·등급 상한을 적용한 포인트 구간(10~13 → 10, 14~16 → 14, 17~20 각 값, 10 미만
 *       0) 값이고, 고대는 17P 이상에서 +1.0%p다.
 *   <li>젬 옵션 배수 = Π (10000 + ⌊레벨 ÷ 120 × 계수⌋) / 10000. 레벨은 공식 ArkGrid.Effects의 Level(질서·혼돈 젬 합)이다.
 *   <li>혼돈의 별 : 무기 코어는 무기 공격력 항으로 들어가 이 표에 없다. 계산하지 않는다.
 * </ul>
 */
public final class CombatPowerArkGridModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final BigDecimal BASIS = BigDecimal.valueOf(10_000);
  private static final String SEPARATOR = " : ";
  private static final String WEAPON_CORE = "혼돈의 별 코어 : 무기";
  static final String CORE_UNREADABLE = "코어 이름·등급·포인트를 읽지 못해 계산하지 않았습니다.";
  static final String WEAPON_CORE_REASON = "혼돈의 별 : 무기 코어는 무기 공격력으로 전투력에 들어가 아직 계산하지 않습니다.";
  static final String CORE_UNCONFIRMED = "이 코어의 전투력 계수를 아직 확인하지 않아 계산하지 않았습니다.";
  static final String GEM_UNREADABLE = "젬 옵션 레벨을 읽지 못해 계산하지 않았습니다.";

  private CombatPowerArkGridModel() {}

  public record Coefficients(
      String ruleVersion,
      String source,
      int gemLevelDivisor,
      Map<String, Integer> gemOptions,
      List<String> gemZeroOptions,
      Map<String, Integer> gradeCaps,
      String ancientGrade,
      int ancientMinPoint,
      List<Core> cores) {
    public Coefficients {
      if (gemLevelDivisor <= 0) throw new IllegalArgumentException("젬 레벨 나눗수는 양수여야 합니다.");
      gemOptions = gemOptions == null ? Map.of() : Map.copyOf(gemOptions);
      gemZeroOptions = gemZeroOptions == null ? List.of() : List.copyOf(gemZeroOptions);
      gradeCaps = gradeCaps == null ? Map.of() : Map.copyOf(gradeCaps);
      cores = cores == null ? List.of() : List.copyOf(cores);
    }
  }

  /**
   * @param prefixes 공식 코어 이름의 " : " 앞부분(예: "질서의 해 코어")
   * @param options " : " 뒷부분. 비어 있으면 어떤 이름이든(질서 코어는 직업 스킬 이름)
   * @param points 포인트 구간 시작("10"·"14"·"17"~"20") → C(%)
   */
  public record Core(
      String name,
      List<String> prefixes,
      List<String> options,
      BigDecimal ancientBonus,
      Map<String, BigDecimal> points) {
    public Core {
      prefixes = prefixes == null ? List.of() : List.copyOf(prefixes);
      options = options == null ? List.of() : List.copyOf(options);
      points = points == null ? Map.of() : Map.copyOf(points);
    }
  }

  /** 코어 하나의 C(%) 또는 계산하지 않은 이유. */
  public record CoreValue(BigDecimal pct, String reason) {}

  /** 젬 옵션 배수(Π, 반올림 전)와 줄 문구, 또는 계산하지 않은 이유(그때 ratio는 null). */
  public record GemValue(String text, BigDecimal ratio, String reason) {
    public BigDecimal pct() {
      return ratio == null ? null : percent(ratio);
    }
  }

  public static CoreValue core(Coefficients c, CharacterLoadout.GridSlot slot) {
    if (slot == null || slot.name() == null || slot.grade() == null || slot.point() == null)
      return new CoreValue(null, CORE_UNREADABLE);
    var name = slot.name().strip();
    if (name.startsWith(WEAPON_CORE)) return new CoreValue(null, WEAPON_CORE_REASON);
    var cap = c.gradeCaps().get(slot.grade());
    if (cap == null)
      return new CoreValue(null, "코어 등급(" + slot.grade() + ")의 상한을 확인하지 않아 계산하지 않았습니다.");
    int split = name.indexOf(SEPARATOR);
    var prefix = split < 0 ? name : name.substring(0, split).strip();
    var option = split < 0 ? "" : name.substring(split + SEPARATOR.length()).strip();
    var core =
        c.cores().stream()
            .filter(
                item ->
                    item.prefixes().contains(prefix)
                        && (item.options().isEmpty() || item.options().contains(option)))
            .findFirst()
            .orElse(null);
    if (core == null) return new CoreValue(null, CORE_UNCONFIRMED);
    int point = Math.min(Math.max(slot.point(), 0), cap);
    var bucket = point >= 17 ? point : point >= 14 ? 14 : point >= 10 ? 10 : 0;
    BigDecimal pct = bucket == 0 ? BigDecimal.ZERO : core.points().get(String.valueOf(bucket));
    if (pct == null) return new CoreValue(null, CORE_UNCONFIRMED);
    if (c.ancientGrade().equals(slot.grade()) && point >= c.ancientMinPoint())
      pct = pct.add(core.ancientBonus());
    return new CoreValue(pct, null);
  }

  public static GemValue gems(Coefficients c, List<CharacterLoadout.GridEffect> effects) {
    var ratio = BigDecimal.ONE;
    var parts = new ArrayList<String>();
    for (var effect : effects == null ? List.<CharacterLoadout.GridEffect>of() : effects) {
      if (effect == null || effect.name() == null)
        return new GemValue(gemText(parts), null, GEM_UNREADABLE);
      var name = effect.name().strip();
      if (c.gemZeroOptions().contains(name)) continue;
      var coefficient = c.gemOptions().get(name);
      if (coefficient == null)
        return new GemValue(gemText(parts), null, "확인하지 않은 젬 옵션(" + name + ")이 있어 계산하지 않았습니다.");
      if (effect.level() == null || effect.level() < 0)
        return new GemValue(gemText(parts), null, GEM_UNREADABLE);
      long bp = (long) effect.level() * coefficient / c.gemLevelDivisor();
      ratio = ratio.multiply(BASIS.add(BigDecimal.valueOf(bp)).divide(BASIS, MATH), MATH);
      parts.add(name + " Lv." + effect.level());
    }
    return new GemValue(gemText(parts), ratio, null);
  }

  /** 배수 → 효율 %(배수 − 1, 소수 둘째 자리). */
  public static BigDecimal percent(BigDecimal ratio) {
    return ratio
        .subtract(BigDecimal.ONE, MATH)
        .multiply(HUNDRED)
        .setScale(2, java.math.RoundingMode.HALF_UP);
  }

  /** C(%) → 배수. */
  public static BigDecimal factor(BigDecimal pct) {
    return BigDecimal.ONE.add(pct.divide(HUNDRED, MATH), MATH);
  }

  private static String gemText(List<String> parts) {
    return parts.isEmpty() ? "젬 옵션" : "젬 옵션(" + String.join(" · ", parts) + ")";
  }
}
