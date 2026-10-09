package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 치명타 적중률·공격/이동 속도 계산(crit-speed-v1).
 *
 * <p>캐릭터에서 관측한 공식 수치(OBSERVED)와 요청 가정(ASSUMED)을 분리해 합산한다. 현재 수치는 공식 Tooltip의 %를 그대로 쓰고, 스탯 계수는 필요
 * 스탯 계산에만 쓴다. 뭉툭한 가시·음속 돌파는 장착 노드의 공식 문구에서 수치를 읽고, 미장착이면 2026-10-05 공식 Lv.2 문구를 기준값으로 쓴다.
 */
public final class CritSpeedCalculation {
  public static final String RULE_VERSION = "crit-speed-v2";

  /** 치명 27.944 = 치명타 적중률 1%. 공식 Tooltip 576→20.61%, 1800→64.42% 일치(2026-10-05). */
  static final BigDecimal CRIT_STAT_PER_PERCENT = new BigDecimal("27.944");

  /** 신속 58.22 = 공격·이동 속도 1%. 공식 Tooltip 77→1.32%, 1794→30.81% 일치(2026-10-05). */
  static final BigDecimal SPEED_STAT_PER_PERCENT = new BigDecimal("58.22");

  static final BigDecimal BACK_ATTACK_CRIT = BigDecimal.TEN;
  static final BigDecimal SYNERGY_CRIT = BigDecimal.TEN;
  static final int CRIT_PET_STAT = 160;
  static final BigDecimal CRIT_CAP = new BigDecimal("100");
  static final BigDecimal SPEED_CAP = new BigDecimal("40");

  /** 기본 치명타 피해 200%. 일로아·로펙 표시값(언새늘 208.40% = 200 + 팔찌 8.40)과 같은 규칙이다(2026-10-09 계산 검증). */
  static final BigDecimal BASE_CRIT_DAMAGE = new BigDecimal("200");

  static final BluntThornRule BLUNT_THORN_REFERENCE =
      new BluntThornRule(2, dec("15"), dec("80"), dec("150"), dec("75"));
  static final SupersonicRule SUPERSONIC_REFERENCE =
      new SupersonicRule(2, dec("10"), dec("8"), dec("30"), dec("24"));

  private static final String NUMBER = "([0-9]+(?:\\.[0-9]+)?)";
  private static final Pattern STAT_CRIT = Pattern.compile("치명타 적중률이 " + NUMBER + "% 증가");
  private static final Pattern STAT_ATTACK_SPEED = Pattern.compile("공격 속도가 " + NUMBER + "% 증가");
  private static final Pattern STAT_MOVE_SPEED = Pattern.compile("이동 속도가 " + NUMBER + "% 증가");
  private static final Pattern STACKED_CRIT =
      Pattern.compile("치명타 적중률 \\+" + NUMBER + "%(?:(?!\\.\\s).)*?최대 (\\d+)중첩");

  /** 뒤따르는 증가/감소. 감소면 음수로 더한다. */
  private static final String DIRECTION = "(?:\\s*(증가|감소))?";

  private static final Pattern CRIT =
      Pattern.compile(
          "(의 )?치명타 적중률(?:이)?\\s*(?:추가로\\s*)?\\+?\\s*"
              + NUMBER
              + "%(?!\\s*(?:이상|이하|로|만큼))"
              + DIRECTION);

  /** 치명타 피해. "…의 치명타 피해"(스킬 한정)와 "받는 치명타 피해"(방어)는 group(1)로 걸러 제외한다. */
  private static final Pattern CRIT_DAMAGE =
      Pattern.compile(
          "(의 |받는 )?치명타 피해(?:가|량이|량)?\\s*(?:추가로\\s*)?\\+?\\s*"
              + NUMBER
              + "%(?!\\s*(?:이상|이하|로|만큼))"
              + DIRECTION);

  private static final Pattern BOTH_SPEED =
      Pattern.compile("공격 및 이동 ?속도(?:가)?\\s*\\+?\\s*" + NUMBER + "%(?!\\s*만큼)" + DIRECTION);
  private static final Pattern ATTACK_SPEED =
      Pattern.compile("(의 )?공격 ?속도(?:가)?\\s*\\+?\\s*" + NUMBER + "%(?!\\s*만큼)" + DIRECTION);
  private static final Pattern MOVE_SPEED =
      Pattern.compile("(의 )?이동 ?속도(?:가)?\\s*\\+?\\s*" + NUMBER + "%(?!\\s*만큼)" + DIRECTION);
  private static final Pattern LEVEL = Pattern.compile("Lv\\.(\\d+)");
  private static final String BLUNT_THORN = "뭉툭한 가시";
  private static final String SUPERSONIC = "음속 돌파";

  private CritSpeedCalculation() {}

  public enum Kind {
    OBSERVED,
    ASSUMED
  }

  public enum Category {
    STAT,
    ENGRAVING,
    ARK_PASSIVE,
    ACCESSORY,
    BRACELET,
    CARD,
    ASSUMPTION
  }

  public enum Axis {
    CRIT,
    CRIT_DAMAGE,
    ATTACK_SPEED,
    MOVE_SPEED
  }

  public enum NodeSource {
    EQUIPPED_NODE,
    REFERENCE_LEVEL_2
  }

  /** text는 근거 문구(공식 원문 또는 가정 설명)다. */
  public record Source(
      Kind kind, Category category, Axis axis, String name, BigDecimal value, String text) {}

  /** 모든 값은 % 단위 가정이다. null은 0으로 본다. */
  public record Assumptions(
      boolean backAttack,
      boolean critPet,
      int critSynergyCount,
      BigDecimal supportCritRate,
      BigDecimal tripodCritRate,
      BigDecimal extraCritRate,
      BigDecimal extraAttackSpeed,
      BigDecimal extraMoveSpeed) {
    public static final Assumptions NONE =
        new Assumptions(false, false, 0, null, null, null, null, null);
  }

  public record BluntThornRule(
      Integer level,
      BigDecimal baseEvolutionDamage,
      BigDecimal critCap,
      BigDecimal conversionPercent,
      BigDecimal maxEvolutionDamage) {
    BigDecimal optimalCritRate() {
      return critCap.add(
          maxEvolutionDamage
              .subtract(baseEvolutionDamage)
              .multiply(HUNDRED)
              .divide(conversionPercent, 6, RoundingMode.HALF_UP));
    }

    BigDecimal evolutionDamage(BigDecimal critRate) {
      var over = critRate.subtract(critCap).max(BigDecimal.ZERO);
      return baseEvolutionDamage
          .add(over.multiply(conversionPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP))
          .min(maxEvolutionDamage);
    }
  }

  public record SupersonicRule(
      Integer level,
      BigDecimal conversionPercent,
      BigDecimal overCapBonus,
      BigDecimal overCapConversionPercent,
      BigDecimal maxEvolutionDamage) {
    /** 공격·이동 속도를 같은 값으로 올릴 때 최대 진화형 피해에 처음 닿는 속도 증가량. */
    BigDecimal optimalSpeed() {
      var r1 = conversionPercent.divide(HUNDRED, 6, RoundingMode.HALF_UP);
      var underCap = r1.multiply(SPEED_CAP).multiply(TWO);
      if (underCap.compareTo(maxEvolutionDamage) >= 0)
        return maxEvolutionDamage.divide(r1.multiply(TWO), 6, RoundingMode.HALF_UP);
      var rest = maxEvolutionDamage.subtract(underCap).subtract(overCapBonus);
      if (rest.signum() <= 0) return SPEED_CAP;
      var r2 = overCapConversionPercent.divide(HUNDRED, 6, RoundingMode.HALF_UP);
      return SPEED_CAP.add(rest.divide(r2.multiply(TWO), 6, RoundingMode.HALF_UP));
    }

    BigDecimal evolutionDamage(BigDecimal attack, BigDecimal move) {
      var r1 = conversionPercent.divide(HUNDRED, 6, RoundingMode.HALF_UP);
      var value =
          r1.multiply(
              attack
                  .min(SPEED_CAP)
                  .max(BigDecimal.ZERO)
                  .add(move.min(SPEED_CAP).max(BigDecimal.ZERO)));
      if (attack.compareTo(SPEED_CAP) > 0 && move.compareTo(SPEED_CAP) > 0) {
        var r2 = overCapConversionPercent.divide(HUNDRED, 6, RoundingMode.HALF_UP);
        value =
            value
                .add(overCapBonus)
                .add(r2.multiply(attack.subtract(SPEED_CAP).add(move.subtract(SPEED_CAP))));
      }
      return value.min(maxEvolutionDamage);
    }
  }

  public record CritTarget(
      String key,
      BigDecimal targetRate,
      Integer requiredCritStat,
      int requiredAdditionalCritStat,
      boolean reached) {}

  public record BluntThorn(
      NodeSource source,
      BluntThornRule rule,
      BigDecimal optimalCritRate,
      BigDecimal evolutionDamage,
      BigDecimal effectiveCritRate) {}

  public record Crit(
      Integer critStat,
      BigDecimal observedRate,
      BigDecimal assumedRate,
      BigDecimal totalRate,
      BigDecimal effectiveRate,
      BluntThorn bluntThorn,
      List<CritTarget> targets,
      BigDecimal critStatPerPercent,
      CritDamage damage) {}

  /** 치명타 피해(%). base 200 + 관측 출처 합(observed). 스킬·조건 한정 문구는 더하지 않는다. */
  public record CritDamage(BigDecimal base, BigDecimal observed, BigDecimal total) {}

  public record SpeedAxis(
      BigDecimal observed, BigDecimal assumed, BigDecimal total, BigDecimal effective) {}

  public record Supersonic(
      NodeSource source,
      SupersonicRule rule,
      BigDecimal optimalSpeed,
      Integer requiredSwiftness,
      int requiredAdditionalSwiftness,
      BigDecimal evolutionDamage) {}

  public record Speed(
      Integer swiftnessStat,
      BigDecimal cap,
      SpeedAxis attack,
      SpeedAxis move,
      Supersonic supersonic,
      BigDecimal swiftnessPerPercent,
      List<SwiftnessRow> swiftnessTable) {}

  /** 신속 스탯이 swiftness일 때 공격·이동 속도와 음속 돌파 진화형 피해. 신속 외 출처(관측+가정)는 그대로 둔다. */
  public record SwiftnessRow(
      int swiftness,
      BigDecimal attack,
      BigDecimal move,
      BigDecimal attackEffective,
      BigDecimal moveEffective,
      BigDecimal supersonicEvolutionDamage,
      boolean current,
      boolean optimal) {}

  public record Result(
      String ruleVersion, List<Source> sources, Crit crit, Speed speed, List<String> limitations) {}

  public static Result calculate(
      CharacterProfile profile, CharacterLoadout loadout, Assumptions assumptions) {
    var input = assumptions == null ? Assumptions.NONE : assumptions;
    var sources = new ArrayList<Source>();
    var limitations = new ArrayList<String>();
    Integer critStat = statValue(profile, "치명");
    Integer swiftness = statValue(profile, "신속");
    BigDecimal critFromStat = statSources(profile, critStat, swiftness, sources, limitations);
    engravingSources(loadout, sources);
    BluntThornRule equippedThorn = null;
    SupersonicRule equippedSupersonic = null;
    if (loadout.arkPassive() != null) {
      for (var effect : loadout.arkPassive().effects()) {
        if (effect == null || effect.effect() == null) continue;
        String name = nodeName(effect.description());
        if (effect.description() != null && effect.description().contains(BLUNT_THORN)) {
          equippedThorn = bluntThorn(effect, limitations);
          continue;
        }
        if (effect.description() != null && effect.description().contains(SUPERSONIC)) {
          equippedSupersonic = supersonic(effect, limitations);
          continue;
        }
        scan(Category.ARK_PASSIVE, name, effect.effect(), sources, true);
      }
    }
    equipmentSources(loadout, sources);
    cardSources(loadout, sources);
    assumptionSources(input, sources);

    var observedCrit = sum(sources, Kind.OBSERVED, Axis.CRIT);
    var assumedCrit = sum(sources, Kind.ASSUMED, Axis.CRIT);
    var totalCrit = observedCrit.add(assumedCrit);
    var nonStatCrit = totalCrit.subtract(critFromStat);
    var effectiveStat = critStat == null ? null : critStat + (input.critPet() ? CRIT_PET_STAT : 0);

    var thornRule = equippedThorn == null ? BLUNT_THORN_REFERENCE : equippedThorn;
    var thornSource =
        equippedThorn == null ? NodeSource.REFERENCE_LEVEL_2 : NodeSource.EQUIPPED_NODE;
    var thornOptimal = thornRule.optimalCritRate();
    var thorn =
        new BluntThorn(
            thornSource,
            thornRule,
            scale(thornOptimal),
            scale(thornRule.evolutionDamage(totalCrit)),
            scale(totalCrit.min(thornRule.critCap())));
    var targets =
        List.of(
            target("CRIT_100", CRIT_CAP, totalCrit, nonStatCrit, effectiveStat),
            target("BLUNT_THORN_OPTIMAL", thornOptimal, totalCrit, nonStatCrit, effectiveStat));
    var crit =
        new Crit(
            critStat,
            scale(observedCrit),
            scale(assumedCrit),
            scale(totalCrit),
            scale(totalCrit.min(equippedThorn == null ? CRIT_CAP : thornRule.critCap())),
            thorn,
            targets,
            CRIT_STAT_PER_PERCENT,
            new CritDamage(
                scale(BASE_CRIT_DAMAGE),
                scale(sum(sources, Kind.OBSERVED, Axis.CRIT_DAMAGE)),
                scale(BASE_CRIT_DAMAGE.add(sum(sources, Kind.OBSERVED, Axis.CRIT_DAMAGE)))));

    var attack = axis(sources, Axis.ATTACK_SPEED);
    var move = axis(sources, Axis.MOVE_SPEED);
    var supersonicRule = equippedSupersonic == null ? SUPERSONIC_REFERENCE : equippedSupersonic;
    var optimalSpeed = supersonicRule.optimalSpeed();
    var swiftnessSpeed = statSpeed(sources);
    var needAttack = optimalSpeed.subtract(attack.total().subtract(swiftnessSpeed.attack()));
    var needMove = optimalSpeed.subtract(move.total().subtract(swiftnessSpeed.move()));
    int requiredSwiftness =
        ceil(needAttack.max(needMove).max(BigDecimal.ZERO).multiply(SPEED_STAT_PER_PERCENT));
    int additionalSwiftness =
        swiftness == null
            ? ceil(
                optimalSpeed
                    .subtract(attack.total().min(move.total()))
                    .max(BigDecimal.ZERO)
                    .multiply(SPEED_STAT_PER_PERCENT))
            : Math.max(0, requiredSwiftness - swiftness);
    var speed =
        new Speed(
            swiftness,
            SPEED_CAP,
            scaled(attack),
            scaled(move),
            new Supersonic(
                equippedSupersonic == null
                    ? NodeSource.REFERENCE_LEVEL_2
                    : NodeSource.EQUIPPED_NODE,
                supersonicRule,
                scale(optimalSpeed),
                swiftness == null ? null : requiredSwiftness,
                additionalSwiftness,
                scale(supersonicRule.evolutionDamage(attack.total(), move.total()))),
            SPEED_STAT_PER_PERCENT,
            swiftnessTable(
                attack.total().subtract(swiftnessSpeed.attack()),
                move.total().subtract(swiftnessSpeed.move()),
                swiftness,
                requiredSwiftness,
                supersonicRule));
    limitations.add("아크 그리드 코어·젬, 스킬 룬·트라이포드의 치명타·속도 효과는 공식 원문 문구가 없어 가정 입력으로만 반영합니다.");
    limitations.add("특정 스킬·조건 한정 효과(예: '…의 치명타 적중률', 백어택 한정)는 자동 합산하지 않습니다.");
    limitations.add(
        "치명타 피해는 기본 200%에 장비·팔찌·각인·아크 패시브·카드의 일반 문구만 더합니다. 스킬 한정('방향성 공격 스킬의 치명타 피해' 등)과 받는 치명타 피해는 제외합니다.");
    return new Result(RULE_VERSION, List.copyOf(sources), crit, speed, List.copyOf(limitations));
  }

  static final int TABLE_STEP = 200;
  static final int TABLE_MAX = 4000;

  private static List<SwiftnessRow> swiftnessTable(
      BigDecimal nonStatAttack,
      BigDecimal nonStatMove,
      Integer current,
      int optimal,
      SupersonicRule rule) {
    var points = new java.util.TreeSet<Integer>();
    int upper =
        Math.min(
            TABLE_MAX,
            ((Math.max(optimal, current == null ? 0 : current) / TABLE_STEP) + 2) * TABLE_STEP);
    for (int value = 0; value <= upper; value += TABLE_STEP) points.add(value);
    if (current != null && current <= TABLE_MAX) points.add(current);
    if (optimal <= TABLE_MAX) points.add(optimal);
    var rows = new ArrayList<SwiftnessRow>();
    for (int value : points) {
      var percent =
          BigDecimal.valueOf(value).divide(SPEED_STAT_PER_PERCENT, 6, RoundingMode.HALF_UP);
      var attack = nonStatAttack.add(percent);
      var move = nonStatMove.add(percent);
      rows.add(
          new SwiftnessRow(
              value,
              scale(attack),
              scale(move),
              scale(attack.min(SPEED_CAP)),
              scale(move.min(SPEED_CAP)),
              scale(rule.evolutionDamage(attack, move)),
              current != null && value == current,
              value == optimal));
    }
    return List.copyOf(rows);
  }

  private static BigDecimal statSources(
      CharacterProfile profile,
      Integer critStat,
      Integer swiftness,
      List<Source> sources,
      List<String> limitations) {
    BigDecimal critFromStat = BigDecimal.ZERO;
    for (var stat : profile.stats()) {
      if (stat == null || stat.type() == null) continue;
      String text = String.join(" ", stat.tooltip());
      if (stat.type().equals("치명")) {
        var value = first(STAT_CRIT, text);
        if (value == null && critStat != null) {
          value =
              BigDecimal.valueOf(critStat).divide(CRIT_STAT_PER_PERCENT, 6, RoundingMode.HALF_UP);
          limitations.add("치명 Tooltip이 없어 치명 스탯을 계수로 환산했습니다.");
        }
        if (value != null) {
          critFromStat = value;
          sources.add(
              new Source(
                  Kind.OBSERVED, Category.STAT, Axis.CRIT, "치명 " + stat.value(), value, text));
        }
      } else if (stat.type().equals("신속")) {
        var attack = first(STAT_ATTACK_SPEED, text);
        var move = first(STAT_MOVE_SPEED, text);
        if ((attack == null || move == null) && swiftness != null) {
          var converted =
              BigDecimal.valueOf(swiftness).divide(SPEED_STAT_PER_PERCENT, 6, RoundingMode.HALF_UP);
          if (attack == null) attack = converted;
          if (move == null) move = converted;
          limitations.add("신속 Tooltip이 없어 신속 스탯을 계수로 환산했습니다.");
        }
        if (attack != null)
          sources.add(
              new Source(
                  Kind.OBSERVED,
                  Category.STAT,
                  Axis.ATTACK_SPEED,
                  "신속 " + stat.value(),
                  attack,
                  text));
        if (move != null)
          sources.add(
              new Source(
                  Kind.OBSERVED, Category.STAT, Axis.MOVE_SPEED, "신속 " + stat.value(), move, text));
      }
    }
    return critFromStat;
  }

  private static void engravingSources(CharacterLoadout loadout, List<Source> sources) {
    if (loadout.engravings() == null) return;
    for (var engraving : loadout.engravings().arkPassiveEffects()) {
      if (engraving == null || engraving.description() == null) continue;
      scan(Category.ENGRAVING, engraving.name(), engraving.description(), sources, true);
    }
  }

  private static void equipmentSources(CharacterLoadout loadout, List<Source> sources) {
    for (var equipment : loadout.equipment()) {
      if (equipment == null || equipment.detail() == null) continue;
      var detail = equipment.detail();
      for (var polish : detail.polishEffects()) {
        if (polish == null || polish.name() == null) continue;
        String text = polish.name() + " " + (polish.value() == null ? "" : polish.value());
        scan(Category.ACCESSORY, equipment.type(), text, sources, false);
      }
      for (var bracelet : detail.braceletEffects()) {
        if (bracelet == null || bracelet.text() == null) continue;
        scan(Category.BRACELET, equipment.type(), bracelet.text(), sources, false);
      }
    }
  }

  private static void cardSources(CharacterLoadout loadout, List<Source> sources) {
    if (loadout.cards() == null) return;
    for (var set : loadout.cards().effects()) {
      if (set == null) continue;
      for (var effect : set.items()) {
        if (effect == null || effect.description() == null) continue;
        scan(Category.CARD, effect.name(), effect.description(), sources, false);
      }
    }
  }

  private static void assumptionSources(Assumptions input, List<Source> sources) {
    if (input.backAttack())
      assumed(sources, Axis.CRIT, "백어택", BACK_ATTACK_CRIT, "백어택 적중 시 치명타 적중률 +10%");
    if (input.critPet())
      assumed(
          sources,
          Axis.CRIT,
          "치명 펫",
          BigDecimal.valueOf(CRIT_PET_STAT).divide(CRIT_STAT_PER_PERCENT, 6, RoundingMode.HALF_UP),
          "펫 효과 치명 +160");
    if (input.critSynergyCount() > 0)
      assumed(
          sources,
          Axis.CRIT,
          "치명타 시너지 " + input.critSynergyCount() + "개",
          SYNERGY_CRIT.multiply(BigDecimal.valueOf(input.critSynergyCount())),
          "파티 치명타 적중률 시너지 1개당 +10%(동일 직업 중복 제외)");
    assumed(sources, Axis.CRIT, "서포터 치명타 버프", input.supportCritRate(), "요청 가정");
    assumed(sources, Axis.CRIT, "트라이포드 치명타", input.tripodCritRate(), "요청 가정");
    assumed(sources, Axis.CRIT, "기타 치명타", input.extraCritRate(), "요청 가정");
    assumed(
        sources,
        Axis.ATTACK_SPEED,
        "공격 속도 가정",
        input.extraAttackSpeed(),
        "직업 각인·트라이포드·버프·음식 등 요청 가정");
    assumed(
        sources, Axis.MOVE_SPEED, "이동 속도 가정", input.extraMoveSpeed(), "직업 각인·트라이포드·버프·음식 등 요청 가정");
  }

  private static void assumed(
      List<Source> sources, Axis axis, String name, BigDecimal value, String text) {
    if (value == null || value.signum() == 0) return;
    sources.add(new Source(Kind.ASSUMED, Category.ASSUMPTION, axis, name, value, text));
  }

  /** 문구에서 치명타 적중률과 공격/이동 속도 증가를 찾는다. '…의' 한정 효과는 건너뛴다. */
  static void scan(
      Category category, String name, String text, List<Source> sources, boolean allowStacked) {
    var stackedSpans = new ArrayList<int[]>();
    if (allowStacked) {
      Matcher stacked = STACKED_CRIT.matcher(text);
      while (stacked.find()) {
        var value = new BigDecimal(stacked.group(1)).multiply(new BigDecimal(stacked.group(2)));
        sources.add(new Source(Kind.OBSERVED, category, Axis.CRIT, name, value, text));
        stackedSpans.add(new int[] {stacked.start(), stacked.end()});
      }
    }
    Matcher crit = CRIT.matcher(text);
    while (crit.find()) {
      if (crit.group(1) != null || inside(stackedSpans, crit.start())) continue;
      sources.add(
          new Source(
              Kind.OBSERVED,
              category,
              Axis.CRIT,
              name,
              signed(crit.group(2), crit.group(3)),
              text));
    }
    Matcher damage = CRIT_DAMAGE.matcher(text);
    while (damage.find()) {
      if (damage.group(1) != null) continue;
      sources.add(
          new Source(
              Kind.OBSERVED,
              category,
              Axis.CRIT_DAMAGE,
              name,
              signed(damage.group(2), damage.group(3)),
              text));
    }
    Matcher both = BOTH_SPEED.matcher(text);
    var bothSpans = new ArrayList<int[]>();
    while (both.find()) {
      var value = signed(both.group(1), both.group(2));
      sources.add(new Source(Kind.OBSERVED, category, Axis.ATTACK_SPEED, name, value, text));
      sources.add(new Source(Kind.OBSERVED, category, Axis.MOVE_SPEED, name, value, text));
      bothSpans.add(new int[] {both.start(), both.end()});
    }
    speed(ATTACK_SPEED, Axis.ATTACK_SPEED, category, name, text, bothSpans, sources);
    speed(MOVE_SPEED, Axis.MOVE_SPEED, category, name, text, bothSpans, sources);
  }

  private static void speed(
      Pattern pattern,
      Axis axis,
      Category category,
      String name,
      String text,
      List<int[]> skip,
      List<Source> sources) {
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      if (matcher.group(1) != null || inside(skip, matcher.start())) continue;
      sources.add(
          new Source(
              Kind.OBSERVED,
              category,
              axis,
              name,
              signed(matcher.group(2), matcher.group(3)),
              text));
    }
  }

  private static BigDecimal signed(String number, String direction) {
    var value = new BigDecimal(number);
    return "감소".equals(direction) ? value.negate() : value;
  }

  private static boolean inside(List<int[]> spans, int index) {
    for (var span : spans) if (index >= span[0] && index < span[1]) return true;
    return false;
  }

  private static BluntThornRule bluntThorn(
      CharacterLoadout.PassiveEffect effect, List<String> limitations) {
    var text = effect.effect();
    var base = first(Pattern.compile("^진화형 피해가 " + NUMBER + "% 증가"), text);
    var cap = first(Pattern.compile("최대 " + NUMBER + "% ?로 제한"), text);
    var ratio = first(Pattern.compile("확률의 " + NUMBER + "%가 진화형 피해로 전환"), text);
    var max = first(Pattern.compile("최대 " + NUMBER + "%까지"), text);
    if (base == null || cap == null || ratio == null || max == null || ratio.signum() == 0) {
      limitations.add("장착한 뭉툭한 가시 문구를 해석하지 못해 Lv.2 기준값을 사용했습니다.");
      return null;
    }
    return new BluntThornRule(level(effect.description()), base, cap, ratio, max);
  }

  private static SupersonicRule supersonic(
      CharacterLoadout.PassiveEffect effect, List<String> limitations) {
    var text = effect.effect();
    var r1 = first(Pattern.compile("이동 속도 증가량의 " + NUMBER + "%"), text);
    var bonus = first(Pattern.compile("추가로 " + NUMBER + "%"), text);
    var r2 = first(Pattern.compile("초과한 속도 증가량의 " + NUMBER + "%"), text);
    var max = first(Pattern.compile("최대 " + NUMBER + "%까지"), text);
    if (r1 == null
        || bonus == null
        || r2 == null
        || max == null
        || r1.signum() == 0
        || r2.signum() == 0) {
      limitations.add("장착한 음속 돌파 문구를 해석하지 못해 Lv.2 기준값을 사용했습니다.");
      return null;
    }
    return new SupersonicRule(level(effect.description()), r1, bonus, r2, max);
  }

  private static CritTarget target(
      String key, BigDecimal targetRate, BigDecimal total, BigDecimal nonStat, Integer critStat) {
    boolean reached = total.compareTo(targetRate) >= 0;
    if (critStat == null) {
      int additional =
          ceil(targetRate.subtract(total).max(BigDecimal.ZERO).multiply(CRIT_STAT_PER_PERCENT));
      return new CritTarget(key, scale(targetRate), null, additional, reached);
    }
    int required =
        ceil(targetRate.subtract(nonStat).max(BigDecimal.ZERO).multiply(CRIT_STAT_PER_PERCENT));
    return new CritTarget(
        key, scale(targetRate), required, reached ? 0 : Math.max(0, required - critStat), reached);
  }

  private record AxisSum(BigDecimal observed, BigDecimal assumed, BigDecimal total) {}

  private record StatSpeed(BigDecimal attack, BigDecimal move) {}

  private static AxisSum axis(List<Source> sources, Axis axis) {
    var observed = sum(sources, Kind.OBSERVED, axis);
    var assumed = sum(sources, Kind.ASSUMED, axis);
    return new AxisSum(observed, assumed, observed.add(assumed));
  }

  private static SpeedAxis scaled(AxisSum sum) {
    return new SpeedAxis(
        scale(sum.observed()),
        scale(sum.assumed()),
        scale(sum.total()),
        scale(sum.total().min(SPEED_CAP)));
  }

  private static StatSpeed statSpeed(List<Source> sources) {
    BigDecimal attack = BigDecimal.ZERO;
    BigDecimal move = BigDecimal.ZERO;
    for (var source : sources) {
      if (source.category() != Category.STAT) continue;
      if (source.axis() == Axis.ATTACK_SPEED) attack = attack.add(source.value());
      if (source.axis() == Axis.MOVE_SPEED) move = move.add(source.value());
    }
    return new StatSpeed(attack, move);
  }

  private static BigDecimal sum(List<Source> sources, Kind kind, Axis axis) {
    return sources.stream()
        .filter(source -> source.kind() == kind && source.axis() == axis)
        .map(Source::value)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static Integer statValue(CharacterProfile profile, String type) {
    for (var stat : profile.stats()) {
      if (stat == null || !type.equals(stat.type()) || stat.value() == null) continue;
      try {
        return Integer.parseInt(stat.value().replace(",", "").strip());
      } catch (NumberFormatException exception) {
        return null;
      }
    }
    return null;
  }

  private static String nodeName(String description) {
    if (description == null) return null;
    return description.replaceFirst("^\\S+ \\d티어 ", "");
  }

  private static Integer level(String description) {
    if (description == null) return null;
    var matcher = LEVEL.matcher(description);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
  }

  private static BigDecimal first(Pattern pattern, String text) {
    if (text == null) return null;
    var matcher = pattern.matcher(text);
    return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
  }

  private static int ceil(BigDecimal value) {
    return value.setScale(0, RoundingMode.CEILING).intValueExact();
  }

  private static BigDecimal scale(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal dec(String value) {
    return new BigDecimal(value);
  }

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal TWO = new BigDecimal("2");
}
