package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 치명타 적중률·공격/이동 속도 계산(crit-speed-v1).
 *
 * <p>캐릭터에서 관측한 공식 수치(OBSERVED)와 요청 가정(ASSUMED)을 분리해 합산한다. 현재 수치는 공식 Tooltip의 %를 그대로 쓰고, 스탯 계수는 필요
 * 스탯 계산에만 쓴다. 뭉툭한 가시·음속 돌파는 장착 노드의 공식 문구에서 수치를 읽고, 미장착이면 2026-10-05 공식 Lv.2 문구를 기준값으로 쓴다.
 */
public final class CritSpeedCalculation {
  public static final String RULE_VERSION = "crit-speed-v6";

  /**
   * 치명타 피해 앞(같은 문장)에 오면 조건부로 보고 합산하지 않는 말. 로펙·일로아는 "폭주 중"·"마력 해방 중"(각성 상태)은 넣고 "사신화 상태에서"· "페르소나
   * 상태일 때"는 뺀다(계산 검증 표본 5명, 10-09).
   */
  private static final List<String> CONDITIONAL_STATE = List.of("상태에서", "상태일 때");

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
      Pattern.compile(
          "공격(?: ?속도)?(?: 및 |와 )이동 ?속도(?:가)?\\s*\\+?\\s*" + NUMBER + "%(?!\\s*만큼)" + DIRECTION);

  /** "공격 속도가 4.0초 간 30.0% 증가"처럼 값 앞에 지속 시간이 오는 문구(속공 준비)도 읽는다. */
  private static final String DURATION = "(?:[0-9.]+초\\s*(?:간|동안)\\s*)?";

  private static final Pattern ATTACK_SPEED =
      Pattern.compile(
          "(의 )?공격 ?속도(?:가)?\\s*" + DURATION + "\\+?\\s*" + NUMBER + "%(?!\\s*만큼)" + DIRECTION);
  private static final Pattern MOVE_SPEED =
      Pattern.compile(
          "(의 )?이동 ?속도(?:가)?\\s*" + DURATION + "\\+?\\s*" + NUMBER + "%(?!\\s*만큼)" + DIRECTION);
  private static final Pattern LEVEL = Pattern.compile("Lv\\.(\\d+)");
  private static final Pattern BRACELET_STACKS = Pattern.compile("\\(최대 (\\d+)중첩\\)");
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
    /** 공용 아크 그리드 코어(부수는 일격·재빠른 공격, #189). */
    ARK_GRID,
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
      Kind kind, Category category, Axis axis, String name, BigDecimal value, String text) {
    /** 직업 상태 가정(정답표의 피냄새 같은 상태 노드와 직업 버프)인가. 요청 classState로 끄고 켠다. */
    boolean classState() {
      return kind == Kind.ASSUMED
          && name != null
          && (name.contains(" 가정(")
              || CritEffectTable.CLASS_BUFFS.stream().anyMatch(buff -> buff.label().equals(name)));
    }
  }

  /** 모든 값은 % 단위 가정이다. null은 0으로 본다. */
  public record Assumptions(
      boolean backAttack,
      boolean critPet,
      int critSynergyCount,
      BigDecimal supportCritRate,
      BigDecimal tripodCritRate,
      BigDecimal extraCritRate,
      BigDecimal extraAttackSpeed,
      BigDecimal extraMoveSpeed,
      boolean classState,
      Map<String, Boolean> toggles) {
    /** 가정 입력이 없을 때. 직업 상태 가정은 목표 사이트와 같이 켠다(#189). */
    public static final Assumptions NONE =
        new Assumptions(false, false, 0, null, null, null, null, null, true);

    public Assumptions {
      toggles = toggles == null ? Map.of() : Map.copyOf(toggles);
    }

    public Assumptions(
        boolean backAttack,
        boolean critPet,
        int critSynergyCount,
        BigDecimal supportCritRate,
        BigDecimal tripodCritRate,
        BigDecimal extraCritRate,
        BigDecimal extraAttackSpeed,
        BigDecimal extraMoveSpeed,
        boolean classState) {
      this(
          backAttack,
          critPet,
          critSynergyCount,
          supportCritRate,
          tripodCritRate,
          extraCritRate,
          extraAttackSpeed,
          extraMoveSpeed,
          classState,
          Map.of());
    }

    /** 토글 key의 켬 여부. 요청에 없으면 핵심 상태는 classState, 상황 조건은 끔. */
    boolean on(String key, boolean coreState) {
      var value = toggles.get(key);
      return value != null ? value : coreState && classState;
    }

    public Assumptions(
        boolean backAttack,
        boolean critPet,
        int critSynergyCount,
        BigDecimal supportCritRate,
        BigDecimal tripodCritRate,
        BigDecimal extraCritRate,
        BigDecimal extraAttackSpeed,
        BigDecimal extraMoveSpeed) {
      this(
          backAttack,
          critPet,
          critSynergyCount,
          supportCritRate,
          tripodCritRate,
          extraCritRate,
          extraAttackSpeed,
          extraMoveSpeed,
          true);
    }
  }

  /**
   * 직업 상태 가정 표(#189). 공식 노드 문구가 "어떤 상태에서 치명타 적중률이 X%로 바뀐다"인 경우, 목표 사이트(일로아·클로아)는 그 상태를 가정해 X를 더한다.
   * 표에 있는 노드만 가정으로 더하고(출처 kind=ASSUMED, 이름에 상태와 노드), 요청 classState=false면 뺀다. 같은 구조의 다른 직업은 계산 검증
   * 전수 표로 이 목록에 더한다.
   */
  record StateAssumption(String node, String state, Pattern pattern) {}

  static final List<StateAssumption> CLASS_STATE_ASSUMPTIONS =
      List.of(
          // 리퍼 갈증: "혼돈 게이지가 가득차면 혼돈 효과가 강화되어 치명타 적중률이 23.0% 로 변경된다."(공식, 10-09 확인)
          new StateAssumption(
              "피냄새", "혼돈 상태", Pattern.compile("치명타 적중률이\\s*" + NUMBER + "%\\s*로 변경")));

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

  /**
   * 스킬별 치명타 적중률(#168, 계산 검증 규칙 10-09: 클로아 17/17). total = base(crit.observedRate) + backAttack(백 어택
   * +10) + tripod(선택 트라이포드 "치명타 적중률이 X% 증가" 합), effective는 상한(100, 뭉툭한 가시 장착 시 그 상한) 적용. 공격 타입을
   * 모르거나 갱신 전 캐시라 트라이포드 문구가 없으면 해당 값과 total·effective가 null이다(추정 금지). conditional은 조건부 문구라 더하지 않은
   * 트라이포드다.
   */
  public record SkillCrit(
      String name,
      CharacterLoadout.AttackType attackType,
      BigDecimal base,
      BigDecimal backAttack,
      BigDecimal tripod,
      BigDecimal total,
      BigDecimal effective,
      List<SkillTripod> tripods,
      List<SkillTripod> conditional,
      BigDecimal node,
      List<SkillTripod> nodes,
      BigDecimal damage,
      List<SkillTripod> damageNodes) {}

  /** 스킬 한정 노드 하나(정답표 6-1). target·skill은 CritEffectTable.SkillBonus와 같다. value는 배수까지 곱한 값. */
  record NodeBonus(
      String name,
      Axis axis,
      CritEffectTable.Target target,
      String skill,
      BigDecimal value,
      String text) {
    boolean appliesTo(CharacterLoadout.Skill skill) {
      return switch (target) {
        case BACK_ATTACK -> skill.attackType() == CharacterLoadout.AttackType.BACK_ATTACK;
        case SKILL -> this.skill.equals(skill.name());
        case CATEGORY -> this.skill.equals(skill.category());
      };
    }
  }

  public record SkillTripod(String name, BigDecimal value, String text) {}

  /**
   * 켜고 끄는 효과 하나(PM 총괄 23:24). key는 요청 assumptions.toggles의 키다. coreState(직업 핵심 상태·자기 버프)는 기본 켬, 그
   * 밖(스킬 직후 N초 등)은 기본 끔. text는 공식 원문 또는 근거.
   */
  public record ToggleState(
      String key,
      String label,
      Axis axis,
      boolean coreState,
      boolean on,
      BigDecimal value,
      String text) {}

  public record Result(
      String ruleVersion,
      List<Source> sources,
      Crit crit,
      Speed speed,
      List<String> limitations,
      List<SkillCrit> skills,
      List<ToggleState> toggles) {}

  public static Result calculate(
      CharacterProfile profile, CharacterLoadout loadout, Assumptions assumptions) {
    var input = assumptions == null ? Assumptions.NONE : assumptions;
    var sources = new ArrayList<Source>();
    var limitations = new ArrayList<String>();
    Integer critStat = statValue(profile, "치명");
    Integer swiftness = statValue(profile, "신속");
    BigDecimal critFromStat = statSources(profile, critStat, swiftness, sources, limitations);
    var conditional = new ArrayList<String>();
    var conditionalRate = new ArrayList<String>();
    var unclassified = new ArrayList<String>();
    var agility = new BigDecimal[2];
    var skillBonuses = new ArrayList<NodeBonus>();
    var toggles = new ArrayList<ToggleState>();
    var speedNotes = new ArrayList<String>();
    engravingSources(loadout, sources, conditional);
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
        arkPassiveNode(
            name,
            effect.effect(),
            input,
            sources,
            conditional,
            conditionalRate,
            unclassified,
            agility,
            skillBonuses,
            toggles,
            speedNotes);
      }
    }
    equipmentSources(loadout, sources);
    cardSources(loadout, sources);
    gridCoreSources(loadout, input, sources, toggles, unclassified, conditionalRate);
    braceletToggles(loadout, input, sources, toggles, conditionalRate, conditional);
    classBuffSources(profile, loadout, input, sources, toggles);
    classSpeedSources(profile, input, sources, toggles);
    tripodSpeedSources(loadout, input, sources, toggles, speedNotes);
    assumptionSources(input, sources);
    agilitySources(agility, sources);
    // 직업 상태 가정(정답표 표의 피냄새·직업 버프)은 스킬별 치적 기본에도 넣는다.
    var classStateCrit =
        sources.stream()
            .filter(
                source ->
                    source.kind() == Kind.ASSUMED
                        && source.axis() == Axis.CRIT
                        && source.classState())
            .map(Source::value)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

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
        "치명타 피해는 기본 200%에 장비·팔찌·각인·아크 패시브·카드의 일반 문구만 더합니다. 스킬 한정('방향성 공격 스킬의 치명타 피해' 등), 받는 치명타 피해, 특정 상태 한정('…상태에서')은 제외하고, 각성 중 상시 효과('폭주 중', '마력 해방 중')는 더합니다.");
    var skills =
        // 스킬별 기본은 관측 + 직업 상태 가정(목표 사이트와 같은 기준, #189).
        skillCrits(
            loadout,
            observedCrit.add(classStateCrit),
            equippedThorn == null ? CRIT_CAP : thornRule.critCap(),
            BASE_CRIT_DAMAGE.add(sum(sources, Kind.OBSERVED, Axis.CRIT_DAMAGE)),
            limitations,
            skillBonuses);
    if (!conditionalRate.isEmpty())
      limitations.add(
          "기본 치명타 적중률에 넣지 않은 효과(공식 문구의 조건·대상 스킬, 스킬 한정은 스킬별 치명타 적중률에 반영): "
              + String.join(", ", conditionalRate));
    if (!speedNotes.isEmpty())
      limitations.add("기본 공격·이동 속도에 넣지 않은 효과: " + String.join(", ", speedNotes));
    if (!unclassified.isEmpty())
      limitations.add("직업별 치명 효과 표에 아직 없어 합산하지 않은 효과(분류 중): " + String.join(", ", unclassified));
    if (!conditional.isEmpty())
      limitations.add(
          "기본 치명타 피해에 넣지 않은 효과(공식 문구의 조건·대상 스킬, 스킬 한정은 스킬별 치명타 피해에 반영): "
              + String.join(", ", conditional));
    return new Result(
        RULE_VERSION,
        List.copyOf(sources),
        crit,
        speed,
        List.copyOf(limitations),
        skills,
        List.copyOf(toggles));
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

  private static void engravingSources(
      CharacterLoadout loadout, List<Source> sources, List<String> conditional) {
    if (loadout.engravings() == null) return;
    for (var engraving : loadout.engravings().arkPassiveEffects()) {
      if (engraving == null || engraving.description() == null) continue;
      scan(
          Category.ENGRAVING,
          engraving.name(),
          engraving.description(),
          sources,
          true,
          conditional);
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
        var scanned = new ArrayList<Source>();
        scan(Category.BRACELET, equipment.type(), bracelet.text(), scanned, false);
        var stacks = BRACELET_STACKS.matcher(bracelet.text());
        for (var source : scanned) {
          boolean speed = source.axis() == Axis.ATTACK_SPEED || source.axis() == Axis.MOVE_SPEED;
          // "공격 적중 시 매 초마다 … 공격 및 이동 속도가 1% 증가한다. (최대 6중첩)" → 두 사이트는 최대 중첩(×6)으로 본다(#198).
          if (speed && stacks.reset().find())
            sources.add(
                new Source(
                    source.kind(),
                    source.category(),
                    source.axis(),
                    source.name() + "(최대 " + stacks.group(1) + "중첩 가정)",
                    source.value().multiply(new BigDecimal(stacks.group(1))),
                    source.text()));
          else sources.add(source);
        }
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

  /** 트라이포드의 치명타 적중률. "…의 치명타 적중률"은 스킬 일부(예: 폭발의) 한정이라 조건부로 둔다. */
  private static final Pattern TRIPOD_CRIT =
      Pattern.compile("(의 )?치명타 적중률(?:이)?\\s*\\+?\\s*" + NUMBER + "%" + DIRECTION);

  /** 같은 문장에서 앞에 오면 조건부로 보는 말: "…시"(오버차지 시, 퍼펙트 존 성공 시), "…상태에서", "…일 때". */
  private static final Pattern TRIPOD_CONDITION = Pattern.compile("(?:(?:^|\\s)시|상태에서|일 때)[ ,]");

  /** 레벨을 올린(2 이상) 스킬만 이름순으로 계산한다. */
  static List<SkillCrit> skillCrits(
      CharacterLoadout loadout, BigDecimal observedCrit, BigDecimal cap, List<String> limitations) {
    return skillCrits(loadout, observedCrit, cap, BASE_CRIT_DAMAGE, limitations, List.of());
  }

  static List<SkillCrit> skillCrits(
      CharacterLoadout loadout,
      BigDecimal observedCrit,
      BigDecimal cap,
      BigDecimal critDamage,
      List<String> limitations,
      List<NodeBonus> bonuses) {
    if (loadout.skills() == null) return List.of();
    var result = new ArrayList<SkillCrit>();
    boolean missingTooltip = false;
    for (var skill : loadout.skills()) {
      if (skill == null || skill.name() == null || skill.level() == null || skill.level() < 2)
        continue;
      var counted = new ArrayList<SkillTripod>();
      var conditional = new ArrayList<SkillTripod>();
      boolean known = true;
      for (var tripod : skill.tripods()) {
        if (tripod == null) continue;
        if (tripod.effect() == null) {
          known = false;
          continue;
        }
        var matcher = TRIPOD_CRIT.matcher(tripod.effect());
        while (matcher.find()) {
          var value = signed(matcher.group(2), matcher.group(3));
          var entry = new SkillTripod(tripod.name(), scale(value), tripod.effect());
          if (matcher.group(1) != null || conditionalClause(tripod.effect(), matcher.start()))
            conditional.add(entry);
          else counted.add(entry);
        }
      }
      if (!known) missingTooltip = true;
      var tripodSum =
          known
              ? counted.stream().map(SkillTripod::value).reduce(BigDecimal.ZERO, BigDecimal::add)
              : null;
      var back =
          skill.attackType() == null
              ? null
              : skill.attackType() == CharacterLoadout.AttackType.BACK_ATTACK
                  ? BACK_ATTACK_CRIT
                  : BigDecimal.ZERO;
      var nodes = new ArrayList<SkillTripod>();
      var damageNodes = new ArrayList<SkillTripod>();
      for (var bonus : bonuses) {
        if (!bonus.appliesTo(skill)) continue;
        var entry = new SkillTripod(bonus.name(), scale(bonus.value()), bonus.text());
        if (bonus.axis() == Axis.CRIT) nodes.add(entry);
        else damageNodes.add(entry);
      }
      var damageSum =
          damageNodes.stream().map(SkillTripod::value).reduce(BigDecimal.ZERO, BigDecimal::add);
      var nodeSum = nodes.stream().map(SkillTripod::value).reduce(BigDecimal.ZERO, BigDecimal::add);
      var total =
          tripodSum == null || back == null
              ? null
              : observedCrit.add(back).add(tripodSum).add(nodeSum);
      result.add(
          new SkillCrit(
              skill.name(),
              skill.attackType(),
              scale(observedCrit),
              back == null ? null : scale(back),
              tripodSum == null ? null : scale(tripodSum),
              total == null ? null : scale(total),
              total == null ? null : scale(total.min(cap)),
              List.copyOf(counted),
              List.copyOf(conditional),
              scale(nodeSum),
              List.copyOf(nodes),
              scale(critDamage.add(damageSum)),
              List.copyOf(damageNodes)));
    }
    result.sort(java.util.Comparator.comparing(SkillCrit::name));
    if (missingTooltip) limitations.add("갱신 전 캐시(최대 5분)라 트라이포드 효과를 읽지 못한 스킬은 스킬별 치명타 적중률을 비워 둡니다.");
    return List.copyOf(result);
  }

  /** 일치 위치가 속한 문장 앞쪽에 "…시"·"…상태에서"·"…일 때" 조건이 있는지. */
  static boolean conditionalClause(String text, int matchStart) {
    int sentence = text.lastIndexOf('.', matchStart - 1);
    while (sentence > 0
        && sentence + 1 < text.length()
        && Character.isDigit(text.charAt(sentence - 1))
        && Character.isDigit(text.charAt(sentence + 1)))
      sentence = text.lastIndexOf('.', sentence - 1);
    return TRIPOD_CONDITION.matcher(text.substring(sentence + 1, matchStart)).find();
  }

  /** 아크 패시브 노드 하나를 직업별 치명 효과 정답표로 판정한다(#189). 속도 효과는 기존 문구 해석을 그대로 쓰고, 치적·치피는 표의 판정으로만 더한다. */
  private static void arkPassiveNode(
      String name,
      String text,
      Assumptions input,
      List<Source> sources,
      List<String> conditionalDamage,
      List<String> conditionalRate,
      List<String> unclassified,
      BigDecimal[] agility,
      List<NodeBonus> skillBonuses,
      List<ToggleState> toggles,
      List<String> speedNotes) {
    var scanned = new ArrayList<Source>();
    scan(Category.ARK_PASSIVE, name, text, scanned, true, new ArrayList<>());
    nodeSpeed(
        name,
        text,
        scanned.stream()
            .filter(s -> s.axis() == Axis.ATTACK_SPEED || s.axis() == Axis.MOVE_SPEED)
            .toList(),
        input,
        sources,
        speedNotes,
        toggles);
    var rule = CritEffectTable.node(name);
    if (rule == null) {
      if (CritEffectTable.ANY_CRIT.matcher(text).find()) unclassified.add(name);
      return;
    }
    String base = rule.node();
    if (rule.rate() == CritEffectTable.Decision.SKILL)
      skillBonus(name, base, text, Axis.CRIT, input, skillBonuses, conditionalRate);
    if (rule.damage() == CritEffectTable.Decision.SKILL)
      skillBonus(name, base, text, Axis.CRIT_DAMAGE, input, skillBonuses, conditionalDamage);
    for (var toggle : CritEffectTable.toggles(base)) {
      var value = toggleValue(toggle, base, text);
      if (value == null) continue;
      boolean on = input.on(toggle.key(), toggle.coreState());
      if (on
          && toggle.exclusiveWith() != null
          && toggles.stream().anyMatch(t -> t.on() && t.key().equals(toggle.exclusiveWith()))) {
        on = false;
        (toggle.axis() == Axis.CRIT ? conditionalRate : conditionalDamage)
            .add(name + " " + plain(value) + "%(" + toggle.exclusiveWith() + "와 동시에 적용되지 않아 끔)");
      }
      toggles.add(
          new ToggleState(
              toggle.key(),
              toggle.label(),
              toggle.axis(),
              toggle.coreState(),
              on,
              scale(value),
              text));
      if (on)
        sources.add(
            new Source(
                Kind.ASSUMED,
                Category.ASSUMPTION,
                toggle.axis(),
                toggle.label() + "(" + name + ")",
                value,
                text));
      else
        (toggle.axis() == Axis.CRIT ? conditionalRate : conditionalDamage)
            .add(name + " " + plain(value) + "%(" + toggle.label() + ", 켜기 가능)");
    }
    if (rule.rate() == CritEffectTable.Decision.INCLUDE
        || rule.rate() == CritEffectTable.Decision.EXCLUDE) {
      var value = rateValue(base, text);
      if (rule.rate() == CritEffectTable.Decision.INCLUDE) {
        if (base.equals("기민함")) agility[0] = value;
        else if (base.equals("피냄새")) {
          var state = classState(name, text);
          if (state != null) {
            boolean on = input.on(base, true);
            toggles.add(
                new ToggleState(base, "혼돈 상태 가정", Axis.CRIT, true, on, scale(state.value()), text));
            if (on) sources.add(state);
          }
        } else if (value != null)
          sources.add(
              new Source(Kind.OBSERVED, Category.ARK_PASSIVE, Axis.CRIT, name, value, text));
      } else if (value != null) conditionalRate.add(name + " " + plain(value) + "%");
    }
    if (rule.damage() == CritEffectTable.Decision.INCLUDE
        || rule.damage() == CritEffectTable.Decision.EXCLUDE) {
      var value = damageValue(base, text);
      if (rule.damage() == CritEffectTable.Decision.INCLUDE) {
        if (base.equals("기민함")) agility[1] = value;
        else if (value != null)
          sources.add(
              new Source(Kind.OBSERVED, Category.ARK_PASSIVE, Axis.CRIT_DAMAGE, name, value, text));
      } else if (value != null) conditionalDamage.add(name + " " + plain(value) + "%");
    }
  }

  /** 노드의 공격·이동 속도를 공이속 표(#198)로 판정한다. 표에 없으면 "분류 중"으로 남긴다. */
  private static void nodeSpeed(
      String name,
      String text,
      List<Source> speeds,
      Assumptions input,
      List<Source> sources,
      List<String> notes,
      List<ToggleState> toggles) {
    if (speeds.isEmpty()) return;
    var rule = CritEffectTable.speed(name);
    if (rule == null) {
      notes.add(name + "(분류 중)");
      return;
    }
    switch (rule.decision()) {
      case INCLUDE -> sources.addAll(speeds);
      case EXCLUDE -> notes.add(name + "(" + rule.basis() + ")");
      case CORE_STATE, SITUATIONAL -> {
        boolean core = rule.decision() == CritEffectTable.SpeedDecision.CORE_STATE;
        boolean on = input.on(rule.node(), core);
        for (var speed : speeds) {
          toggles.add(
              new ToggleState(
                  rule.node(), rule.label(), speed.axis(), core, on, scale(speed.value()), text));
          if (on)
            sources.add(
                new Source(
                    Kind.ASSUMED,
                    Category.ASSUMPTION,
                    speed.axis(),
                    rule.label() + "(" + name + ")",
                    speed.value(),
                    text));
        }
        if (!on) notes.add(name + "(" + rule.label() + ", 켜기 가능)");
      }
    }
  }

  /** 스킬 한정 노드: 값(공식 문구 첫 값 × 배수)을 대상 스킬에만 넘기고, 기본에서 뺀 항목으로 남긴다. */
  private static void skillBonus(
      String name,
      String base,
      String text,
      Axis axis,
      Assumptions input,
      List<NodeBonus> skillBonuses,
      List<String> excluded) {
    var bonus = CritEffectTable.skillBonus(base, axis);
    if (bonus == null) return;
    var value = axis == Axis.CRIT ? rateValue(base, text) : damageValue(base, text);
    if (value == null) return;
    value = value.multiply(BigDecimal.valueOf(bonus.multiplier()));
    if (!bonus.classState() || input.classState())
      skillBonuses.add(new NodeBonus(name, axis, bonus.target(), bonus.skill(), value, text));
    excluded.add(
        name
            + " "
            + plain(value)
            + "%("
            + (bonus.target() == CritEffectTable.Target.BACK_ATTACK ? "백 어택 스킬" : bonus.skill())
            + "에만"
            + (bonus.basis() == null ? "" : ", " + bonus.basis())
            + ")");
  }

  private static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static final Pattern RATE_VALUE =
      Pattern.compile("치명타 적중률(?:이)?\\s*(?:추가로\\s*)?\\+?\\s*" + NUMBER + "%" + DIRECTION);
  private static final Pattern DAMAGE_VALUE =
      Pattern.compile("치명타 피해(?:량)?(?:이|가)?\\s*(?:추가로\\s*)?\\+?\\s*" + NUMBER + "%" + DIRECTION);
  private static final Pattern AGILITY_DAMAGE =
      Pattern.compile("공격 속도 증가량 %의\\s*" + NUMBER + "%\\s*만큼 치명타 피해");
  private static final Pattern AGILITY_RATE =
      Pattern.compile("이동 속도 증가량 %의\\s*" + NUMBER + "%\\s*만큼 치명타 적중률");

  /** 표의 노드에서 치명타 적중률 값. 달인은 중첩 곱, 피냄새는 "로 변경" 값, 기민함은 이동 속도 1%당 비율. 그 밖에는 첫 일치 값. */
  static BigDecimal rateValue(String base, String text) {
    Matcher matcher;
    switch (base) {
      case "달인" -> {
        matcher = STACKED_CRIT.matcher(text);
        if (matcher.find())
          return new BigDecimal(matcher.group(1)).multiply(new BigDecimal(matcher.group(2)));
        return null;
      }
      case "피냄새" -> {
        var state = CLASS_STATE_ASSUMPTIONS.getFirst().pattern().matcher(text);
        return state.find() ? new BigDecimal(state.group(1)) : null;
      }
      case "기민함" -> {
        matcher = AGILITY_RATE.matcher(text);
        return matcher.find()
            ? new BigDecimal(matcher.group(1)).divide(BigDecimal.valueOf(100))
            : null;
      }
      default -> {
        matcher = RATE_VALUE.matcher(text);
        return matcher.find() ? signed(matcher.group(1), matcher.group(2)) : null;
      }
    }
  }

  static BigDecimal damageValue(String base, String text) {
    if (base.equals("기민함")) {
      var matcher = AGILITY_DAMAGE.matcher(text);
      return matcher.find()
          ? new BigDecimal(matcher.group(1)).divide(BigDecimal.valueOf(100))
          : null;
    }
    var matcher = DAMAGE_VALUE.matcher(text);
    return matcher.find() ? signed(matcher.group(1), matcher.group(2)) : null;
  }

  private static BigDecimal toggleValue(CritEffectTable.Toggle toggle, String base, String text) {
    BigDecimal value;
    if (toggle.pattern() == null) value = rateValue(base, text);
    else {
      var matcher = toggle.pattern().matcher(text);
      value = matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }
    if (value == null || !toggle.incremental()) return value;
    var baseValue = rateValue(base, text);
    return baseValue == null ? null : value.subtract(baseValue);
  }

  /** 기민함: 기본 공격·이동 속도 증가량(상한 40 적용)에 비율을 곱한다. 일로아 "질풍노도 기민함"과 같은 기준(정답표 츄델 12/48). */
  private static void agilitySources(BigDecimal[] agility, List<Source> sources) {
    if (agility[0] == null && agility[1] == null) return;
    // "기본 … 속도 증가량"은 증가분만 합한다. 질량 증가(공격 속도 -10%) 같은 감소는 빼지 않는다(일로아·로펙 일치, 정답표 6-2).
    var move = increase(sources, Axis.MOVE_SPEED).min(SPEED_CAP);
    var attack = increase(sources, Axis.ATTACK_SPEED).min(SPEED_CAP);
    if (agility[0] != null)
      sources.add(
          new Source(
              Kind.OBSERVED,
              Category.ARK_PASSIVE,
              Axis.CRIT,
              "기민함(이동 속도 " + move.stripTrailingZeros().toPlainString() + "%)",
              move.multiply(agility[0]),
              "기본 이동 속도 증가량 %의 "
                  + agility[0].movePointRight(2).stripTrailingZeros().toPlainString()
                  + "% 만큼 치명타 적중률"));
    if (agility[1] != null)
      sources.add(
          new Source(
              Kind.OBSERVED,
              Category.ARK_PASSIVE,
              Axis.CRIT_DAMAGE,
              "기민함(공격 속도 " + attack.stripTrailingZeros().toPlainString() + "%)",
              attack.multiply(agility[1]),
              "기본 공격 속도 증가량 %의 "
                  + agility[1].movePointRight(2).stripTrailingZeros().toPlainString()
                  + "% 만큼 치명타 피해"));
  }

  private static BigDecimal increase(List<Source> sources, Axis axis) {
    return sources.stream()
        .filter(source -> source.axis() == axis && source.value().signum() > 0)
        .map(Source::value)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** 공용 아크 그리드 코어: 현재 포인트로 달성한 구간 문구의 치명타 적중률·피해를 더한다. */
  private static void gridCoreSources(
      CharacterLoadout loadout,
      Assumptions input,
      List<Source> sources,
      List<ToggleState> toggles,
      List<String> unclassified,
      List<String> conditionalRate) {
    if (loadout.arkGrid() == null) return;
    for (var slot : loadout.arkGrid().slots()) {
      if (slot == null || slot.name() == null || slot.core() == null || slot.point() == null)
        continue;
      for (var core : CritEffectTable.COMMON_CORES.entrySet()) {
        if (!slot.name().endsWith(core.getKey())) continue;
        var pattern = core.getValue() == Axis.CRIT ? RATE_VALUE : DAMAGE_VALUE;
        var total = BigDecimal.ZERO;
        var texts = new ArrayList<String>();
        for (var option : slot.core().options()) {
          if (option == null || option.text() == null || option.threshold() > slot.point())
            continue;
          var matcher = pattern.matcher(option.text());
          while (matcher.find()) {
            total = total.add(signed(matcher.group(1), matcher.group(2)));
            texts.add(option.text());
          }
        }
        if (total.signum() != 0)
          sources.add(
              new Source(
                  Kind.OBSERVED,
                  Category.ARK_GRID,
                  core.getValue(),
                  slot.name() + " " + slot.point() + "P",
                  total,
                  String.join(" ", texts)));
      }
      if (slot.name().endsWith("재빠른 공격")) commonCoreSpeed(slot, sources);
      if (CritEffectTable.COMMON_CORES.keySet().stream().noneMatch(slot.name()::endsWith))
        classCoreSources(slot, input, sources, toggles, unclassified, conditionalRate);
    }
  }

  /** 직업 코어를 이름 표(CritEffectTable.CLASS_CORES)로 판정한다. 급소 노출(WEAK_POINT)은 직업 버프 표에서 처리한다. */
  private static void classCoreSources(
      CharacterLoadout.GridSlot slot,
      Assumptions input,
      List<Source> sources,
      List<ToggleState> toggles,
      List<String> unclassified,
      List<String> conditionalRate) {
    var rule = CritEffectTable.classCore(slot.name());
    if (rule == null) {
      boolean crit =
          slot.core().options().stream()
              .anyMatch(
                  option ->
                      option != null
                          && option.text() != null
                          && option.threshold() <= slot.point()
                          && CritEffectTable.ANY_CRIT.matcher(option.text()).find());
      if (crit) unclassified.add(slot.name());
      return;
    }
    if (rule.decision() == CritEffectTable.CoreDecision.SITUATIONAL) {
      for (var option : slot.core().options()) {
        if (option == null || option.text() == null || option.threshold() > slot.point()) continue;
        var matcher = RATE_VALUE.matcher(option.text());
        if (!matcher.find()) continue;
        var value = signed(matcher.group(1), matcher.group(2));
        boolean on = input.on(rule.core(), false);
        toggles.add(
            new ToggleState(
                rule.core(), rule.label(), Axis.CRIT, false, on, scale(value), option.text()));
        if (on)
          sources.add(
              new Source(
                  Kind.ASSUMED,
                  Category.ASSUMPTION,
                  Axis.CRIT,
                  rule.label() + "(" + slot.name() + ")",
                  value,
                  option.text()));
        else
          conditionalRate.add(slot.name() + " " + plain(value) + "%(" + rule.label() + ", 켜기 가능)");
      }
      return;
    }
    if (rule.decision() != CritEffectTable.CoreDecision.INCLUDE) return;
    for (var axis : List.of(Axis.CRIT, Axis.CRIT_DAMAGE)) {
      var pattern = axis == Axis.CRIT ? CORE_RATE_ONLY : CORE_DAMAGE_ONLY;
      var total = BigDecimal.ZERO;
      var texts = new ArrayList<String>();
      for (var option : slot.core().options()) {
        if (option == null || option.text() == null || option.threshold() > slot.point()) continue;
        var matcher = pattern.matcher(option.text().strip());
        if (!matcher.matches()) continue;
        total = total.add(new BigDecimal(matcher.group(1)));
        texts.add(option.text());
      }
      if (total.signum() != 0)
        sources.add(
            new Source(
                Kind.OBSERVED,
                Category.ARK_GRID,
                axis,
                slot.name() + " " + slot.point() + "P",
                total,
                String.join(" ", texts)));
    }
  }

  private static final Pattern CORE_RATE_ONLY =
      Pattern.compile("치명타 적중률이\\s*" + NUMBER + "%\\s*증가한다\\.");
  private static final Pattern CORE_DAMAGE_ONLY =
      Pattern.compile("치명타 피해(?:량이|가)\\s*" + NUMBER + "%\\s*증가한다\\.");

  /** 직업 코어의 급소 노출: "급소 노출 효과가 적용되어, 대상이 자신 및 파티원에게 받는 치명타 저항률이 … X% 감소" */
  private static final Pattern CORE_WEAK_POINT =
      Pattern.compile("급소 노출 효과가 적용되어[^.]*?치명타 저항률이(?:\\s*[0-9.]+초 동안)?\\s*" + NUMBER + "%\\s*감소");

  /** 달성한 직업 코어(표의 WEAK_POINT) 옵션 중 급소 노출(치적 시너지) 문구. 없으면 null. */
  private static CharacterLoadout.CoreOption coreWeakPoint(CharacterLoadout loadout) {
    if (loadout.arkGrid() == null) return null;
    for (var slot : loadout.arkGrid().slots()) {
      if (slot == null || slot.core() == null || slot.point() == null) continue;
      var rule = CritEffectTable.classCore(slot.name());
      if (rule == null || rule.decision() != CritEffectTable.CoreDecision.WEAK_POINT) continue;
      for (var option : slot.core().options())
        if (option != null
            && option.text() != null
            && option.threshold() <= slot.point()
            && CORE_WEAK_POINT.matcher(option.text()).find()) return option;
    }
    return null;
  }

  /** 혼돈의 해 "재빠른 공격" 달성 구간의 공격 속도(10P 1.00 + 17P 3.00 = 4, 두 사이트 포함, 10-10). */
  private static void commonCoreSpeed(CharacterLoadout.GridSlot slot, List<Source> sources) {
    var total = BigDecimal.ZERO;
    var texts = new ArrayList<String>();
    for (var option : slot.core().options()) {
      if (option == null || option.text() == null || option.threshold() > slot.point()) continue;
      var matcher = ATTACK_SPEED.matcher(option.text());
      while (matcher.find()) {
        if (matcher.group(1) != null) continue;
        total = total.add(signed(matcher.group(2), matcher.group(3)));
        texts.add(option.text());
      }
    }
    if (total.signum() != 0)
      sources.add(
          new Source(
              Kind.OBSERVED,
              Category.ARK_GRID,
              Axis.ATTACK_SPEED,
              slot.name() + " " + slot.point() + "P",
              total,
              String.join(" ", texts)));
  }

  /** 팔찌의 적중 시 8초 효과(대상 치명타 저항·치명타 피해 저항 감소). 기본 끔 토글. */
  private static void braceletToggles(
      CharacterLoadout loadout,
      Assumptions input,
      List<Source> sources,
      List<ToggleState> toggles,
      List<String> conditionalRate,
      List<String> conditionalDamage) {
    for (var equipment : loadout.equipment()) {
      if (equipment == null || equipment.detail() == null) continue;
      for (var bracelet : equipment.detail().braceletEffects()) {
        if (bracelet == null || bracelet.text() == null) continue;
        for (var rule : CritEffectTable.BRACELET_TOGGLES) {
          var matcher = rule.pattern().matcher(bracelet.text());
          if (!matcher.find()) continue;
          var value = new BigDecimal(matcher.group(1));
          boolean on = input.on(rule.key(), false);
          toggles.add(
              new ToggleState(
                  rule.key(), rule.label(), rule.axis(), false, on, scale(value), bracelet.text()));
          if (on)
            sources.add(
                new Source(
                    Kind.ASSUMED,
                    Category.ASSUMPTION,
                    rule.axis(),
                    rule.label() + "(팔찌)",
                    value,
                    bracelet.text()));
          else
            (rule.axis() == Axis.CRIT ? conditionalRate : conditionalDamage)
                .add("팔찌 " + plain(value) + "%(" + rule.label() + ", 켜기 가능)");
        }
      }
    }
  }

  /** 직업 자기 버프의 공이속(슬레이어 폭주 20). 같은 label의 치명 버프와 한 토글로 켜고 끈다. */
  private static void classSpeedSources(
      CharacterProfile profile,
      Assumptions input,
      List<Source> sources,
      List<ToggleState> toggles) {
    for (var buff : CritEffectTable.CLASS_SPEED_BUFFS) {
      if (!buff.className().equals(profile.className())) continue;
      boolean on = input.on(buff.label(), true);
      for (var axis : List.of(Axis.ATTACK_SPEED, Axis.MOVE_SPEED)) {
        var value = axis == Axis.ATTACK_SPEED ? buff.attack() : buff.move();
        String text = "기본 포함(계산 검증 10-10)";
        toggles.add(
            new ToggleState(buff.label(), buff.label(), axis, true, on, scale(value), text));
        if (on)
          sources.add(
              new Source(Kind.ASSUMED, Category.ASSUMPTION, axis, buff.label(), value, text));
      }
    }
  }

  /** 레벨 2 이상 스킬의 선택 트라이포드 공이속을 트라이포드 표로 판정한다. */
  private static void tripodSpeedSources(
      CharacterLoadout loadout,
      Assumptions input,
      List<Source> sources,
      List<ToggleState> toggles,
      List<String> notes) {
    if (loadout.skills() == null) return;
    for (var skill : loadout.skills()) {
      if (skill == null || skill.level() == null || skill.level() < 2) continue;
      for (var tripod : skill.tripods()) {
        if (tripod == null || tripod.effect() == null) continue;
        var scanned = new ArrayList<Source>();
        scan(Category.ASSUMPTION, tripod.name(), tripod.effect(), scanned, false);
        var speeds =
            scanned.stream()
                .filter(s -> s.axis() == Axis.ATTACK_SPEED || s.axis() == Axis.MOVE_SPEED)
                .toList();
        if (speeds.isEmpty()) continue;
        var rule = CritEffectTable.tripodSpeed(tripod.name());
        String name = skill.name() + " - " + tripod.name();
        if (rule == null) {
          notes.add(name + "(분류 중)");
          continue;
        }
        switch (rule.decision()) {
          case EXCLUDE -> notes.add(name + "(" + rule.basis() + ")");
          case INCLUDE, CORE_STATE, SITUATIONAL -> {
            // 트라이포드는 공식 출처 분류가 없어 기본 포함도 기본 켬 토글(가정 출처)로 낸다.
            boolean core = rule.decision() != CritEffectTable.SpeedDecision.SITUATIONAL;
            boolean on = input.on(rule.tripod(), core);
            for (var s : speeds) {
              toggles.add(
                  new ToggleState(
                      rule.tripod(),
                      rule.label(),
                      s.axis(),
                      core,
                      on,
                      scale(s.value()),
                      tripod.effect()));
              if (on)
                sources.add(
                    new Source(
                        Kind.ASSUMED,
                        Category.ASSUMPTION,
                        s.axis(),
                        rule.label() + "(" + name + ")",
                        s.value(),
                        tripod.effect()));
            }
            if (!on) notes.add(name + "(" + rule.label() + ", 켜기 가능)");
          }
        }
      }
    }
  }

  /** 노드 문구에 없는 직업 버프(정답표 1절 "일치 포함"). 트라이포드 문구가 있으면 그 값을, 없으면 표 값을 쓴다. */
  private static void classBuffSources(
      CharacterProfile profile,
      CharacterLoadout loadout,
      Assumptions input,
      List<Source> sources,
      List<ToggleState> toggles) {
    for (var buff : CritEffectTable.CLASS_BUFFS) {
      if (buff.className() != null && !buff.className().equals(profile.className())) continue;
      BigDecimal value = null;
      String text = buff.basis();
      if (buff.skill() == null && buff.tripod() == null) value = buff.value();
      if (buff.skill() != null && hasSkill(loadout, buff.skill())) value = buff.value();
      if (buff.tripod() != null && buff.tripod().equals("급소 노출")) {
        // 직업 코어(예: 데빌헌터 "샷건 오버로드" 14P)가 급소 노출을 거는 경우도 같은 시너지다(중복 적용 없음).
        var core = coreWeakPoint(loadout);
        if (core != null) {
          var matcher = CORE_WEAK_POINT.matcher(core.text());
          if (matcher.find()) {
            value = new BigDecimal(matcher.group(1));
            text = core.text();
          }
        }
      }
      if (buff.tripod() != null) {
        var tripod = selectedTripod(loadout, buff.tripod());
        if (tripod != null) {
          value = buff.value();
          if (tripod.effect() != null) {
            var matcher = RATE_VALUE.matcher(tripod.effect());
            if (matcher.find()) {
              value = signed(matcher.group(1), matcher.group(2));
              text = tripod.effect();
            }
          }
        }
      }
      if (value == null) continue;
      boolean on = input.on(buff.label(), buff.defaultOn());
      toggles.add(
          new ToggleState(
              buff.label(), buff.label(), Axis.CRIT, buff.defaultOn(), on, scale(value), text));
      if (on)
        sources.add(
            new Source(Kind.ASSUMED, Category.ASSUMPTION, Axis.CRIT, buff.label(), value, text));
    }
  }

  private static boolean hasSkill(CharacterLoadout loadout, String name) {
    if (loadout.skills() == null) return false;
    return loadout.skills().stream()
        .anyMatch(
            skill ->
                skill != null
                    && name.equals(skill.name())
                    && skill.level() != null
                    && skill.level() > 1);
  }

  private static CharacterLoadout.Tripod selectedTripod(CharacterLoadout loadout, String name) {
    if (loadout.skills() == null) return null;
    for (var skill : loadout.skills()) {
      if (skill == null || skill.level() == null || skill.level() < 2) continue;
      for (var tripod : skill.tripods())
        if (tripod != null && name.equals(tripod.name())) return tripod;
    }
    return null;
  }

  /** 표에 있는 직업 상태 노드면 가정 출처를 만든다. 아니면 null. */
  static Source classState(String name, String text) {
    if (name == null || text == null) return null;
    for (var rule : CLASS_STATE_ASSUMPTIONS) {
      if (!name.startsWith(rule.node())) continue;
      var matcher = rule.pattern().matcher(text);
      if (matcher.find())
        return new Source(
            Kind.ASSUMED,
            Category.ASSUMPTION,
            Axis.CRIT,
            rule.state() + " 가정(" + name + ")",
            new BigDecimal(matcher.group(1)),
            text);
    }
    return null;
  }

  /** 일치 위치가 속한 문장(마침표로 나눔)에서 앞쪽에 조건 상태 말이 있는지. */
  static boolean conditionalState(String text, int matchStart) {
    int sentence = text.lastIndexOf('.', matchStart - 1);
    // 소수점(51.0)은 문장 끝이 아니다.
    while (sentence > 0
        && sentence + 1 < text.length()
        && Character.isDigit(text.charAt(sentence - 1))
        && Character.isDigit(text.charAt(sentence + 1)))
      sentence = text.lastIndexOf('.', sentence - 1);
    String before = text.substring(sentence + 1, matchStart);
    return CONDITIONAL_STATE.stream().anyMatch(before::contains);
  }

  private static void assumed(
      List<Source> sources, Axis axis, String name, BigDecimal value, String text) {
    if (value == null || value.signum() == 0) return;
    sources.add(new Source(Kind.ASSUMED, Category.ASSUMPTION, axis, name, value, text));
  }

  /** 문구에서 치명타 적중률과 공격/이동 속도 증가를 찾는다. '…의' 한정 효과는 건너뛴다. */
  static void scan(
      Category category, String name, String text, List<Source> sources, boolean allowStacked) {
    scan(category, name, text, sources, allowStacked, new ArrayList<>());
  }

  /** conditional에는 조건부라 합산하지 않은 치명타 피해를 "이름 값%"로 모은다. */
  static void scan(
      Category category,
      String name,
      String text,
      List<Source> sources,
      boolean allowStacked,
      List<String> conditional) {
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
      if (conditionalState(text, damage.start())) {
        conditional.add(
            name
                + " "
                + signed(damage.group(2), damage.group(3)).stripTrailingZeros().toPlainString()
                + "%");
        continue;
      }
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
      // "…의 공격 속도"는 다른 대상 한정이지만 "자신의 이동속도"는 자기 속도다(아르데타인의 기술).
      boolean other =
          matcher.group(1) != null && !text.substring(0, matcher.start()).endsWith("자신");
      if (other || inside(skip, matcher.start())) continue;
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
