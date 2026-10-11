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
 *   <li>유물 각인 21종(#344): 1 + c/100, c = 표 books[각인서 레벨] + stoneDelta[스톤 레벨]. 기여 0인 9종은 1.
 *   <li>아드레날린: 1 + 6 × 중첩당 공격력(스톤) + 0.7 × 최대 중첩 치명(각인서).
 *   <li>돌격대장: 1 + 0.40 × v, v = 40 + 2 × 각인서 레벨 + 스톤 가산(스톤 1은 미확인).
 *   <li>어빌리티 스톤 [레벨 보너스]: 증가 각인 2개의 세공 레벨 합이 5 이상이면 기본 공격력 1.5%(공식 가이드).
 * </ul>
 *
 * <p>현재 값은 공식 각인 설명값과 계수표가 같은지 확인하고, 다르면 계산하지 않는다(추정 금지). 표의 official(공식 설명 수치와 레벨의 관계)이 없는 각인은 공식
 * 각인서·스톤 레벨 필드만 쓴다.
 */
public final class CombatPowerEquipmentModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
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
      Map<String, TableEngraving> engravingTable,
      String engravingTableSource,
      List<String> zeroContribution,
      Adrenaline adrenaline,
      AssaultCaptain assaultCaptain,
      StoneLevelBonus stoneLevelBonus,
      String stoneLevelBonusSource) {
    public Coefficients {
      source = source == null ? Map.of() : Map.copyOf(source);
      descriptionPercentIndex =
          descriptionPercentIndex == null ? Map.of() : Map.copyOf(descriptionPercentIndex);
      engravingTable = Map.copyOf(engravingTable);
      zeroContribution = List.copyOf(zeroContribution);
    }
  }

  /** 각인 기여 표(#344). books·stoneDelta는 5칸(0~4), null이면 그 레벨은 확인하지 않음(승부사 L0~3). */
  public record TableEngraving(
      List<BigDecimal> books, List<BigDecimal> stoneDelta, Official official) {
    public TableEngraving {
      books = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(books));
      stoneDelta = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(stoneDelta));
    }

    /** 기여 %(c). 확인하지 않은 레벨이면 null. */
    BigDecimal contribution(int book, int stone) {
      var b = books.get(book);
      var s = stoneDelta.get(stone);
      return b == null || s == null ? null : b.add(s);
    }
  }

  /** 공식 각인 설명에 나오는 효과 수치 = base + perBook × 각인서 레벨 + stoneAdd[스톤 레벨](지금 값 확인용). */
  public record Official(BigDecimal base, BigDecimal perBook, List<BigDecimal> stoneAdd) {
    BigDecimal value(int book, int stone) {
      return base.add(perBook.multiply(BigDecimal.valueOf(book))).add(stoneAdd.get(stone));
    }
  }

  /** 어빌리티 스톤 [레벨 보너스]: 세공 레벨 합이 minSum 이상이면 기본 공격력 baseAttackPct%. */
  public record StoneLevelBonus(int minSum, BigDecimal baseAttackPct) {}

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
    return c.engravingTable().containsKey(name)
        || c.zeroContribution().contains(name)
        || ADRENALINE.equals(name)
        || ASSAULT_CAPTAIN.equals(name);
  }

  /** 각인 하나의 전투력 배수. 계수가 없는 조합(돌격대장 스톤 1 등)이면 null. */
  public static BigDecimal engravingFactor(Coefficients c, String name, int book, int stone) {
    if (book < 0 || book > 4 || stone < 0 || stone > 4) return null;
    if (c.zeroContribution().contains(name)) return BigDecimal.ONE;
    var table = c.engravingTable().get(name);
    if (table != null) {
      var v = table.contribution(book, stone);
      return v == null ? null : BigDecimal.ONE.add(v.divide(HUNDRED, MATH));
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
    if (book < 0 || book > 4 || stone < 0 || stone > 4) return false;
    // 기여 0인 각인은 수치가 계산에 쓰이지 않는다.
    if (c.zeroContribution().contains(name)) return true;
    var table = c.engravingTable().get(name);
    if (table != null) {
      if (table.contribution(book, stone) == null) return false;
      if (table.official() == null) return true;
      var v = table.official().value(book, stone);
      var index = c.descriptionPercentIndex().get(name);
      return index != null ? percentAt(text, index, v) : anyPercent(text, v);
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

  /**
   * 품질의 무기 추가 피해 % = 10 + 20 × (품질/100)², 반올림 없음(계산 검증 10-11 17:25: 대조 사이트 60~99 실측 40개가 소수 셋째 자리까지
   * 같음). 공식 툴팁은 이 값을 올림해 보여 주므로(95 → 28.06) 툴팁 숫자를 쓰지 않는다. 0~100 밖이면 null.
   */
  public static BigDecimal weaponAddDamage(int quality) {
    if (quality < 0 || quality > 100) return null;
    return BigDecimal.TEN.add(
        BigDecimal.valueOf((long) quality * quality).divide(BigDecimal.valueOf(500)));
  }

  /** 지금 무기의 추가 피해 %: 품질로 식을 계산한다(툴팁 올림 값이 아님). 품질을 모르면 null. */
  public static BigDecimal currentWeaponAddDamage(CharacterLoadout.EquipmentDetail detail) {
    if (detail == null || detail.quality() == null) return null;
    return weaponAddDamage(detail.quality());
  }

  /** 무기 추가 피해 배수 비. */
  public static BigDecimal weaponQualityRatio(BigDecimal current, BigDecimal next) {
    return BigDecimal.ONE
        .add(next.divide(HUNDRED, MATH))
        .divide(BigDecimal.ONE.add(current.divide(HUNDRED, MATH)), MATH);
  }

  /** 스톤 세공 레벨 합의 [레벨 보너스] 기본 공격력 %(공식 가이드: 합이 5 이상이면 1.5, 아니면 0). */
  public static BigDecimal stoneBonusPct(Coefficients c, int sum) {
    var bonus = c.stoneLevelBonus();
    return sum >= bonus.minSum() ? bonus.baseAttackPct() : BigDecimal.ZERO;
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

  /** 설명의 % 값 가운데 expected(소수 둘째 자리)가 있는지. 수치 위치를 확인하지 않은 각인용. */
  private static boolean anyPercent(String text, BigDecimal expected) {
    var target = expected.setScale(2, RoundingMode.HALF_UP);
    var matcher = PERCENT.matcher(text);
    while (matcher.find()) if (new BigDecimal(matcher.group(1)).compareTo(target) == 0) return true;
    return false;
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
