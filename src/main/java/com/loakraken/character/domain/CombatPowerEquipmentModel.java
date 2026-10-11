package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 전투력 시뮬레이터 무기·방어구 품질과 각인·스톤 항목(B단계, #275). 근거는 리소스
 * combat-power/equipment-engraving-coefficients.json과 docs/verification/combat-power.md 「장비·각인·스톤
 * 변화량」. 모든 항목은 독립 배수라 전투력 비 = Π 항목 배수다.
 *
 * <ul>
 *   <li>무기 품질: (1 + 추가 피해(새)) / (1 + 추가 피해(현재)). 현재 값은 공식 무기 "추가 효과", 새 값은 확인된 품질만.
 *   <li>방어구 품질: 1(전투력 영향 없음).
 *   <li>원한·저주받은 인형·질량 증가·타격의 대가: 1 + v, v = 기본 + 0.75 × 각인서 레벨 + 스톤 가산.
 *   <li>아드레날린: 1 + 6 × 중첩당 공격력(스톤) + 0.7 × 최대 중첩 치명(각인서).
 *   <li>돌격대장: 1 + 0.40 × v, v = 40 + 2 × 각인서 레벨 + 스톤 가산(스톤 1은 미확인).
 * </ul>
 *
 * <p>현재 값은 공식 각인 설명값과 계수표가 같은지 확인하고, 다르면 계산하지 않는다(추정 금지).
 */
public final class CombatPowerEquipmentModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Pattern ADD_DAMAGE = Pattern.compile("^추가 피해 \\+([\\d.]+)%$");
  private static final Pattern PERCENT = Pattern.compile("([\\d.]+)%");
  public static final String ADRENALINE = "아드레날린";
  public static final String ASSAULT_CAPTAIN = "돌격대장";
  public static final Set<String> ARMOR = Set.of("투구", "상의", "하의", "장갑", "어깨");
  private static final String RELIC = "유물";

  public record Coefficients(
      String ruleVersion,
      Map<String, String> source,
      String weaponQualityAddDamageSource,
      String descriptionPercentIndexSource,
      Map<String, Integer> descriptionPercentIndex,
      Map<Integer, BigDecimal> weaponQualityAddDamage,
      DamageEngravings damageEngravings,
      Adrenaline adrenaline,
      AssaultCaptain assaultCaptain,
      List<Integer> stoneLevelBonusStableSums) {
    public Coefficients {
      source = source == null ? Map.of() : Map.copyOf(source);
      weaponQualityAddDamage = Map.copyOf(weaponQualityAddDamage);
      descriptionPercentIndex =
          descriptionPercentIndex == null ? Map.of() : Map.copyOf(descriptionPercentIndex);
      stoneLevelBonusStableSums = List.copyOf(stoneLevelBonusStableSums);
    }
  }

  public record DamageEngravings(
      Map<String, BigDecimal> baseByName, BigDecimal perBook, Map<Integer, BigDecimal> stoneAdd) {}

  public record Adrenaline(
      Map<Integer, BigDecimal> stackAttackByStone,
      int stacks,
      BigDecimal critBase,
      BigDecimal critPerBook,
      BigDecimal critWeight) {}

  public record AssaultCaptain(
      BigDecimal base, BigDecimal perBook, Map<Integer, BigDecimal> stoneAdd, BigDecimal weight) {}

  private CombatPowerEquipmentModel() {}

  /** 계산하는 각인인지. */
  public static boolean supportsEngraving(Coefficients c, String name) {
    return c.damageEngravings().baseByName().containsKey(name)
        || ADRENALINE.equals(name)
        || ASSAULT_CAPTAIN.equals(name);
  }

  /** 각인 하나의 전투력 배수. 계수가 없는 조합(돌격대장 스톤 1 등)이면 null. */
  public static BigDecimal engravingFactor(Coefficients c, String name, int book, int stone) {
    if (book < 0 || book > 4 || stone < 0 || stone > 4) return null;
    var damage = c.damageEngravings();
    if (damage.baseByName().containsKey(name)) {
      var add = damage.stoneAdd().get(stone);
      if (add == null) return null;
      var v =
          damage
              .baseByName()
              .get(name)
              .add(damage.perBook().multiply(BigDecimal.valueOf(book)))
              .add(add);
      return BigDecimal.ONE.add(v.divide(HUNDRED, MATH));
    }
    if (ADRENALINE.equals(name)) {
      var a = c.adrenaline();
      var attack = a.stackAttackByStone().get(stone);
      if (attack == null) return null;
      return BigDecimal.ONE
          .add(attack.multiply(BigDecimal.valueOf(a.stacks())).divide(HUNDRED, MATH))
          .add(a.critWeight().multiply(adrenalineCrit(a, book)).divide(HUNDRED, MATH));
    }
    if (ASSAULT_CAPTAIN.equals(name)) {
      var v = assaultCaptainValue(c.assaultCaptain(), book, stone);
      if (v == null) return null;
      return BigDecimal.ONE.add(c.assaultCaptain().weight().multiply(v).divide(HUNDRED, MATH));
    }
    return null;
  }

  /**
   * 공식 각인 설명의 효과 수치가 계수표 값과 같은지. 설명에 나오는 순서대로 % 값을 읽고, 각인마다 효과 수치 v의 위치(리소스
   * descriptionPercentIndex: 질량 증가만 둘째, 나머지는 첫째)의 값과 비교한다. 아드레날린은 첫째가 중첩당 공격력·둘째가 최대 중첩 치명이다(#290:
   * 페널티 문구 등 다른 수치가 우연히 같아도 통과하지 않게). 등급이 유물이 아니거나 설명이 없거나 위치 표에 없으면 false.
   */
  public static boolean matchesOfficial(
      Coefficients c, CharacterLoadout.PassiveEngraving engraving) {
    if (engraving == null
        || !RELIC.equals(engraving.grade())
        || engraving.level() == null
        || engraving.description() == null) return false;
    int book = engraving.level();
    int stone = engraving.abilityStoneLevel() == null ? 0 : engraving.abilityStoneLevel();
    var text = engraving.description();
    var name = engraving.name();
    var damage = c.damageEngravings();
    if (damage.baseByName().containsKey(name)) {
      var add = damage.stoneAdd().get(stone);
      if (add == null) return false;
      var v =
          damage
              .baseByName()
              .get(name)
              .add(damage.perBook().multiply(BigDecimal.valueOf(book)))
              .add(add);
      var index = c.descriptionPercentIndex().get(name);
      return index != null && percentAt(text, index, v);
    }
    if (ADRENALINE.equals(name)) {
      var attack = c.adrenaline().stackAttackByStone().get(stone);
      return attack != null
          && percentAt(text, 0, attack)
          && percentAt(text, 1, adrenalineCrit(c.adrenaline(), book));
    }
    if (ASSAULT_CAPTAIN.equals(name)) {
      var v = assaultCaptainValue(c.assaultCaptain(), book, stone);
      var index = c.descriptionPercentIndex().get(name);
      return v != null && index != null && percentAt(text, index, v);
    }
    return false;
  }

  /** 품질의 무기 추가 피해 %. 확인된 품질이 아니면 null. */
  public static BigDecimal weaponAddDamage(Coefficients c, int quality) {
    return c.weaponQualityAddDamage().get(quality);
  }

  /** 공식 무기 Tooltip "추가 효과"의 추가 피해 %. 없으면 null. */
  public static BigDecimal currentWeaponAddDamage(CharacterLoadout.EquipmentDetail detail) {
    if (detail == null) return null;
    for (var group : detail.effects()) {
      if (group == null) continue;
      for (var line : group.lines()) {
        var matcher = ADD_DAMAGE.matcher(line.strip());
        if (matcher.matches()) return new BigDecimal(matcher.group(1));
      }
    }
    return null;
  }

  /** 무기 추가 피해 배수 비. */
  public static BigDecimal weaponQualityRatio(BigDecimal current, BigDecimal next) {
    return BigDecimal.ONE
        .add(next.divide(HUNDRED, MATH))
        .divide(BigDecimal.ONE.add(current.divide(HUNDRED, MATH)), MATH);
  }

  /** 스톤 레벨 합이 바뀌어도 [레벨 보너스]가 그대로인 구간(확인된 범위) 안에서만 계산한다. */
  public static boolean stoneBonusStable(Coefficients c, int currentSum, int nextSum) {
    var range = c.stoneLevelBonusStableSums();
    return inRange(range, currentSum) && inRange(range, nextSum);
  }

  private static boolean inRange(List<Integer> range, int value) {
    return range.size() == 2 && value >= range.get(0) && value <= range.get(1);
  }

  private static BigDecimal adrenalineCrit(Adrenaline a, int book) {
    return a.critBase().add(a.critPerBook().multiply(BigDecimal.valueOf(book)));
  }

  private static BigDecimal assaultCaptainValue(AssaultCaptain a, int book, int stone) {
    var add = a.stoneAdd().get(stone);
    return add == null
        ? null
        : a.base().add(a.perBook().multiply(BigDecimal.valueOf(book))).add(add);
  }

  /** 설명에서 index번째(0부터) % 값이 expected와 같은지(소수 둘째 자리). */
  private static boolean percentAt(String text, int index, BigDecimal expected) {
    var matcher = PERCENT.matcher(text);
    for (int i = 0; matcher.find(); i++)
      if (i == index)
        return new BigDecimal(matcher.group(1))
                .compareTo(expected.setScale(2, RoundingMode.HALF_UP))
            == 0;
    return false;
  }
}
