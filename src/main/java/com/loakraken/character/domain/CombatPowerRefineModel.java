package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 전투력 시뮬레이터 재련 단계(#319). 계산 검증 2026-10-11 18:05 모델 확정, 표본
 * verification/combat-power/loalab-refine-model-2026-10-11.json. 근거·단계표는 리소스
 * combat-power/refine-coefficients.json.
 *
 * <p>공식 기본 공격력 B = (R + F)(1 + q), R = √(S·W/6). F = 팔 보호구 "기본 공격력 +N", q = 기본 공격력 %(보석 모델과 같은 값),
 * W = (무기 + 팔 보호구 무기 공격력 + 장신구 "무기 공격력 +N") × (1 + 장신구 "무기 공격력 x%" + 깨달음 카르마 레벨 × 0.1%), S = 6R²/W.
 * 방어구 단계는 S' = S + Δ주스탯 × mS(mS = 1 + 펫 1% 가정 + 아바타), 무기 단계는 W' = W + Δ무기 공격력 × (1 + 무기 공격력 %). 전투력
 * 비 = (R·√(S'/S · W'/W) + F)/(R + F). 입력을 하나라도 공식 응답에서 읽지 못하면 계산하지 않는다(추정 금지).
 */
public final class CombatPowerRefineModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal SIX = BigDecimal.valueOf(6);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Set<String> PARTS = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final String ARM_GUARD = "완갑";
  private static final String JEONYUL = "전율";
  private static final String UPGRADE = "업화";
  private static final Pattern BASE_ATTACK_STAT = Pattern.compile("기본 공격력은\\s*([\\d,]+)");
  private static final Pattern FLAT_BASE_ATTACK = Pattern.compile("^기본 공격력 \\+([\\d,]+)$");
  private static final Pattern FLAT_WEAPON_ATTACK = Pattern.compile("^무기 공격력 \\+([\\d,]+)$");
  private static final Pattern POLISH_FLAT = Pattern.compile("^\\+([\\d,]+)$");
  private static final Pattern POLISH_PCT = Pattern.compile("^\\+([\\d.]+)%$");
  private static final Pattern KARMA_LEVEL = Pattern.compile("(\\d+)레벨");
  private static final String WEAPON_ATTACK = "무기 공격력";

  private CombatPowerRefineModel() {}

  /** 리소스 combat-power/refine-coefficients.json. 비율은 소수(0.01 = 1%)다. */
  public record Coefficients(
      String ruleVersion,
      String source,
      BigDecimal petStatPct,
      String petAssumption,
      Map<String, BigDecimal> avatarStatPctByGrade,
      List<String> avatarStatTypes,
      BigDecimal karmaWeaponPctPerLevel,
      Map<String, Map<Integer, Long>> jeonyulStatTable,
      String upgradeSource,
      Map<Integer, BigDecimal> upgradeHelmetT,
      List<BigDecimal> upgradeBetween,
      Map<String, BigDecimal> upgradePartMultiplier,
      Map<Integer, BigDecimal> upgradeWeaponExactT,
      Map<Integer, BigDecimal> advancedBonus,
      String armGuardSource,
      Map<Integer, ArmGuardLevel> armGuardTable,
      Map<String, BigDecimal> armGuardGradePct,
      Map<String, List<Integer>> armGuardGradeRange) {
    public Coefficients {
      armGuardTable = armGuardTable == null ? Map.of() : Map.copyOf(armGuardTable);
      armGuardGradePct = armGuardGradePct == null ? Map.of() : Map.copyOf(armGuardGradePct);
      armGuardGradeRange = armGuardGradeRange == null ? Map.of() : Map.copyOf(armGuardGradeRange);
      avatarStatPctByGrade = Map.copyOf(avatarStatPctByGrade);
      avatarStatTypes = List.copyOf(avatarStatTypes);
      var table = new HashMap<String, Map<Integer, Long>>();
      jeonyulStatTable.forEach((part, levels) -> table.put(part, Map.copyOf(levels)));
      jeonyulStatTable = Map.copyOf(table);
      upgradeHelmetT = upgradeHelmetT == null ? Map.of() : Map.copyOf(upgradeHelmetT);
      upgradeBetween = upgradeBetween == null ? List.of() : List.copyOf(upgradeBetween);
      upgradePartMultiplier =
          upgradePartMultiplier == null ? Map.of() : Map.copyOf(upgradePartMultiplier);
      upgradeWeaponExactT =
          upgradeWeaponExactT == null ? Map.of() : Map.copyOf(upgradeWeaponExactT);
      advancedBonus = advancedBonus == null ? Map.of() : Map.copyOf(advancedBonus);
    }
  }

  public enum Family {
    JEONYUL,
    UPGRADE
  }

  /** 계산할 수 있는 부위 하나: 장비 순번, 부위 이름, 장비 계열(전율·업화), 지금 일반 재련 단계·상급 재련 단계. */
  public record Part(int index, String type, Family family, int level, int advanced) {}

  /** 바꾼 뒤 일반 재련 단계·상급 재련 단계. */
  public record Target(int level, int advanced) {}

  /** 팔 보호구 단계 하나(#341): 무기 공격력·주스탯·기본 공격력 +N. confirmed=false는 5단계 주기로 채운 칸(계산하지 않음). */
  public record ArmGuardLevel(
      long weaponAttack, long mainStat, long baseAttack, boolean confirmed) {}

  /** 팔 보호구 단계·등급(영웅·전설·유물·고대). */
  public record ArmGuard(int level, String grade) {}

  /** unsupportedReason이 있으면 재련 변화는 모두 계산하지 않는다. */
  public record Model(
      BigDecimal r,
      BigDecimal f,
      BigDecimal s,
      BigDecimal w,
      BigDecimal weaponPct,
      BigDecimal statMultiplier,
      Map<Integer, Part> parts,
      Map<Integer, String> partReasons,
      Coefficients coefficients,
      String unsupportedReason,
      ArmGuard armGuard,
      String armGuardReason) {
    public Model {
      parts = parts == null ? Map.of() : Map.copyOf(parts);
      partReasons = partReasons == null ? Map.of() : Map.copyOf(partReasons);
    }

    /** 장비 index의 일반 재련을 level로 바꿀 수 있는지(상급 단계는 그대로). 안 되면 이유 문장. */
    public String cannotSet(int index, int level) {
      var part = parts.get(index);
      return cannotSetTarget(index, level, part == null ? 0 : part.advanced());
    }

    /** 장비 index의 상급 재련을 advanced로 바꿀 수 있는지(일반 단계는 그대로). */
    public String cannotSetAdvanced(int index, int advanced) {
      var part = parts.get(index);
      if (part != null && part.family() == Family.JEONYUL)
        return "전율 장비의 상급 재련 수치는 아직 확인하지 않아 계산하지 않았습니다(업화 장비만).";
      return cannotSetTarget(index, part == null ? 0 : part.level(), advanced);
    }

    private String cannotSetTarget(int index, int level, int advanced) {
      var reason = partReasons.get(index);
      if (reason != null) return reason;
      if (unsupportedReason != null) return unsupportedReason;
      var part = parts.get(index);
      if (part == null) return "이 장비의 재련 단계 계산식을 아직 확인하지 않아 계산하지 않았습니다.";
      if (stat(part, level, advanced) == null)
        return part.family() == Family.UPGRADE && "무기".equals(part.type())
            ? "이 단계 조합의 무기 수치를 아직 확인하지 않아 계산하지 않았습니다."
            : "이 장비 " + level + "단계의 수치를 아직 확인하지 않아 계산하지 않았습니다(11~25단계만).";
      return null;
    }

    public Part part(int index) {
      return parts.get(index);
    }

    /** 팔 보호구를 level·grade로 바꿀 수 있는지. 안 되면 이유 문장. */
    public String cannotSetArmGuard(int level, String grade) {
      if (unsupportedReason != null) return unsupportedReason;
      if (armGuardReason != null) return armGuardReason;
      var row = coefficients.armGuardTable().get(level);
      if (row == null || !row.confirmed()) return "이 팔 보호구 단계의 수치는 아직 확인하지 않아 계산하지 않았습니다.";
      // 등급별 관측 범위(armGuardGradeRange)는 참고용이다. 대조 사이트도 범위 밖 조합을 같은 식으로 계산한다(표본 +25 전설).
      if (coefficients.armGuardGradePct().get(grade) == null)
        return "이 팔 보호구 등급(" + grade + ")의 효과는 아직 확인하지 않아 계산하지 않았습니다.";
      return null;
    }

    /** 팔 보호구 등급을 바꿀 때 기본 공격력 % 변화(소수, 0.01 = 1%p). */
    public BigDecimal armGuardPctDelta(ArmGuard target) {
      if (armGuard == null || target == null) return BigDecimal.ZERO;
      return coefficients
          .armGuardGradePct()
          .get(target.grade())
          .subtract(coefficients.armGuardGradePct().get(armGuard.grade()));
    }

    /** 장비 index → 새 일반 재련 단계(상급은 그대로)로 바꾼 상태의 전투력 비. 계산할 수 없으면 null. */
    public BigDecimal ratio(Map<Integer, Integer> levels) {
      return ratio(levels, BigDecimal.ZERO);
    }

    /** 재련 단계 변화와 함께 고정 무기 공격력이 weaponFlatDelta만큼 바뀐 상태(팔찌 #327)의 전투력 비. */
    public BigDecimal ratio(Map<Integer, Integer> levels, BigDecimal weaponFlatDelta) {
      return ratio(levels, weaponFlatDelta, BigDecimal.ZERO);
    }

    /** 위에 더해 주스탯(힘·민첩·지능)이 mainStatDelta만큼 바뀐 상태(팔찌 #332): S' = S + Δ × mS. */
    public BigDecimal ratio(
        Map<Integer, Integer> levels, BigDecimal weaponFlatDelta, BigDecimal mainStatDelta) {
      var targets = new HashMap<Integer, Target>();
      for (var entry : levels.entrySet()) {
        var part = parts.get(entry.getKey());
        if (part == null) return null;
        targets.put(entry.getKey(), new Target(entry.getValue(), part.advanced()));
      }
      return ratioTargets(targets, weaponFlatDelta, mainStatDelta);
    }

    /** 장비 index → (일반, 상급) 단계로 바꾼 상태의 전투력 비(#333). 계산할 수 없으면 null. */
    public BigDecimal ratioTargets(
        Map<Integer, Target> targets, BigDecimal weaponFlatDelta, BigDecimal mainStatDelta) {
      return ratioFull(targets, weaponFlatDelta, mainStatDelta, null);
    }

    /**
     * 위에 더해 팔 보호구 단계가 바뀐 상태(#341): S·W에 단계표 차이를 더하고 F를 새 단계 값으로 바꾼 (R' + F')/(R + F). 등급의 기본 공격력 %
     * 변화(q)는 보석 모델 q와 함께 시뮬레이터가 곱한다.
     */
    public BigDecimal ratioFull(
        Map<Integer, Target> targets,
        BigDecimal weaponFlatDelta,
        BigDecimal mainStatDelta,
        ArmGuard armGuardTarget) {
      if (unsupportedReason != null) return null;
      var nextF = f;
      if (armGuardTarget != null) {
        if (armGuard == null) return null;
        var from = coefficients.armGuardTable().get(armGuard.level());
        var to = coefficients.armGuardTable().get(armGuardTarget.level());
        if (from == null || to == null || !to.confirmed()) return null;
        weaponFlatDelta =
            weaponFlatDelta.add(BigDecimal.valueOf(to.weaponAttack() - from.weaponAttack()));
        mainStatDelta = mainStatDelta.add(BigDecimal.valueOf(to.mainStat() - from.mainStat()));
        nextF = BigDecimal.valueOf(to.baseAttack());
      }
      var nextS = s.add(mainStatDelta.multiply(statMultiplier, MATH), MATH);
      var nextW = w.add(weaponFlatDelta.multiply(BigDecimal.ONE.add(weaponPct), MATH), MATH);
      for (var entry : targets.entrySet()) {
        var part = parts.get(entry.getKey());
        if (part == null) return null;
        var from = stat(part, part.level(), part.advanced());
        var to = stat(part, entry.getValue().level(), entry.getValue().advanced());
        if (from == null || to == null) return null;
        var delta = to.subtract(from);
        if ("무기".equals(part.type()))
          nextW = nextW.add(delta.multiply(BigDecimal.ONE.add(weaponPct), MATH), MATH);
        else nextS = nextS.add(delta.multiply(statMultiplier, MATH), MATH);
      }
      if (nextS.signum() <= 0 || nextW.signum() <= 0) return null;
      var scale = nextS.divide(s, MATH).multiply(nextW.divide(w, MATH), MATH).sqrt(MATH);
      return r.multiply(scale, MATH).add(nextF, MATH).divide(r.add(f, MATH), MATH);
    }

    private BigDecimal stat(Part part, int level, int advanced) {
      return CombatPowerRefineModel.stat(coefficients, part.type(), part.family(), level, advanced);
    }
  }

  /**
   * 부위 수치. 전율은 단계표(상급 0만). 업화는 round(T(5·일반 + 상급) × (1 + 30단계 2% + 40단계 3%))이고, 방어구 T = round(투구 T
   * × 부위 배수), 5의 배수 사이 칸은 ⌈T(a) + (T(a + 5) − T(a)) × f(j)⌉, 무기는 확인된 칸만(#333, 표본
   * loalab-advanced-refine-2026-10-11.json).
   */
  public static BigDecimal stat(
      Coefficients c, String type, Family family, int level, int advanced) {
    if (family == Family.JEONYUL) {
      if (advanced != 0) return null;
      var table = c.jeonyulStatTable().get(type);
      var value = table == null ? null : table.get(level);
      return value == null ? null : BigDecimal.valueOf(value);
    }
    if (level < 0 || advanced < 0 || advanced > 40) return null;
    int index = 5 * level + advanced;
    BigDecimal base;
    if ("무기".equals(type)) base = c.upgradeWeaponExactT().get(index);
    else {
      var multiplier = c.upgradePartMultiplier().get(type);
      var helmet = helmetT(c, index);
      if (multiplier == null || helmet == null) return null;
      base = helmet.multiply(multiplier, MATH).setScale(0, RoundingMode.HALF_UP);
    }
    if (base == null) return null;
    var bonus = BigDecimal.ONE;
    for (var entry : c.advancedBonus().entrySet())
      if (advanced >= entry.getKey()) bonus = bonus.add(entry.getValue());
    return base.multiply(bonus, MATH).setScale(0, RoundingMode.HALF_UP);
  }

  private static BigDecimal helmetT(Coefficients c, int index) {
    var exact = c.upgradeHelmetT().get(index);
    if (exact != null) return exact;
    int base = index - index % 5;
    int j = index % 5;
    var low = c.upgradeHelmetT().get(base);
    var high = c.upgradeHelmetT().get(base + 5);
    if (low == null || high == null || j == 0 || c.upgradeBetween().size() < 4) return null;
    return low.add(high.subtract(low).multiply(c.upgradeBetween().get(j - 1), MATH), MATH)
        .setScale(0, RoundingMode.CEILING);
  }

  /** 캐릭터 하나의 재련 모델. baseAttackPct는 보석 모델이 구한 지금 기본 공격력 %(백분율, null이면 계산하지 않음). */
  public static Model of(
      Coefficients c,
      CharacterLoadout loadout,
      List<CharacterProfile.Stat> stats,
      BigDecimal baseAttackPct) {
    var parts = new HashMap<Integer, Part>();
    var partReasons = new HashMap<Integer, String>();
    for (int index = 0; index < loadout.equipment().size(); index++) {
      var item = loadout.equipment().get(index);
      if (item == null || !PARTS.contains(item.type()) || item.detail() == null) continue;
      var level = item.detail().refineLevel();
      int advanced =
          item.detail().advancedRefineLevel() == null ? 0 : item.detail().advancedRefineLevel();
      if (item.name() != null && item.name().contains(UPGRADE) && level != null) {
        if (stat(c, item.type(), Family.UPGRADE, level, advanced) == null)
          partReasons.put(
              index,
              "무기".equals(item.type())
                  ? "이 단계 조합의 무기 수치를 아직 확인하지 않아 계산하지 않았습니다."
                  : "지금 재련 단계의 수치를 아직 확인하지 않아 계산하지 않았습니다.");
        else parts.put(index, new Part(index, item.type(), Family.UPGRADE, level, advanced));
        continue;
      }
      if (item.name() == null || !item.name().contains(JEONYUL))
        partReasons.put(index, "전율·업화 장비만 계산합니다(에스더 등 그 밖의 장비 단계표는 아직 확인하지 않음).");
      else if (advanced != 0)
        partReasons.put(index, "전율 장비의 상급 재련 수치는 아직 확인하지 않아 계산하지 않았습니다(업화 장비만).");
      else if (level == null
          || !c.jeonyulStatTable().getOrDefault(item.type(), Map.of()).containsKey(level))
        partReasons.put(index, "지금 재련 단계의 수치를 아직 확인하지 않아 계산하지 않았습니다(11~25단계만).");
      else parts.put(index, new Part(index, item.type(), Family.JEONYUL, level, 0));
    }
    String reason = null;
    var b = baseAttack(stats);
    if (b == null) reason = "공식 기본 공격력(공격력 Tooltip)을 읽지 못해 계산하지 않았습니다.";
    else if (baseAttackPct == null) reason = "기본 공격력 % 합(보석·스톤·팔 보호구)을 확정하지 못해 재련 변화는 계산하지 않았습니다.";
    BigDecimal f = BigDecimal.ZERO;
    BigDecimal flatWeapon = null;
    BigDecimal weaponPct = BigDecimal.ZERO;
    BigDecimal accessoryFlat = BigDecimal.ZERO;
    for (var item : loadout.equipment()) {
      if (item == null || item.detail() == null) continue;
      if ("무기".equals(item.type())) flatWeapon = flat(item.detail(), FLAT_WEAPON_ATTACK);
      if (ARM_GUARD.equals(item.type())) {
        var armGuardBase = flat(item.detail(), FLAT_BASE_ATTACK);
        if (armGuardBase == null && reason == null) reason = "팔 보호구의 기본 공격력(+N)을 읽지 못해 계산하지 않았습니다.";
        if (armGuardBase != null) f = armGuardBase;
        var armGuardWeapon = flat(item.detail(), FLAT_WEAPON_ATTACK);
        if (armGuardWeapon != null) accessoryFlat = accessoryFlat.add(armGuardWeapon);
      }
      for (var polish : item.detail().polishEffects()) {
        if (polish == null || !WEAPON_ATTACK.equals(polish.name()) || polish.value() == null)
          continue;
        var value = polish.value().strip();
        var pct = POLISH_PCT.matcher(value);
        var plain = POLISH_FLAT.matcher(value);
        if (pct.matches()) weaponPct = weaponPct.add(new BigDecimal(pct.group(1)).divide(HUNDRED));
        else if (plain.matches())
          accessoryFlat = accessoryFlat.add(new BigDecimal(plain.group(1).replace(",", "")));
        else if (reason == null) reason = "장신구 무기 공격력 연마 값(" + value + ")을 읽지 못해 계산하지 않았습니다.";
      }
    }
    // 팔찌 "무기 공격력 +N"·"무기 공격력이 N 증가한다"의 고정 N도 W에 넣는다(버프 부분은 빼고, 계산 검증 10-11 23:45).
    accessoryFlat = accessoryFlat.add(CombatPowerBraceletModel.weaponFlat(loadout));
    if (flatWeapon == null && reason == null)
      reason = "무기 공격력(무기 Tooltip 기본 효과)을 읽지 못해 계산하지 않았습니다.";
    var karma = karmaLevel(loadout);
    if (karma == null && reason == null) reason = "깨달음 아크 패시브 레벨을 읽지 못해 계산하지 않았습니다.";
    var statMultiplier = BigDecimal.ONE.add(c.petStatPct());
    for (var type : c.avatarStatTypes()) {
      BigDecimal best = BigDecimal.ZERO;
      for (var avatar : loadout.avatars()) {
        if (avatar == null || !type.equals(avatar.type()) || avatar.grade() == null) continue;
        var pct = c.avatarStatPctByGrade().get(avatar.grade());
        if (pct == null) {
          if (reason == null)
            reason = "아바타 등급(" + avatar.grade() + ")의 능력치 효과를 아직 확인하지 않아 계산하지 않았습니다.";
          continue;
        }
        if (pct.compareTo(best) > 0) best = pct;
      }
      statMultiplier = statMultiplier.add(best);
    }
    ArmGuard armGuard = null;
    String armGuardReason = "팔 보호구가 없어 계산하지 않았습니다.";
    for (var item : loadout.equipment()) {
      if (item == null || !ARM_GUARD.equals(item.type()) || item.detail() == null) continue;
      var level = item.detail().refineLevel();
      var row = level == null ? null : c.armGuardTable().get(level);
      if (item.name() == null || !item.name().contains(JEONYUL))
        armGuardReason = "전율 팔 보호구만 계산합니다.";
      else if (row == null || !row.confirmed())
        armGuardReason = "지금 팔 보호구 단계의 수치를 아직 확인하지 않아 계산하지 않았습니다.";
      else if (c.armGuardGradePct().get(item.grade()) == null)
        armGuardReason = "지금 팔 보호구 등급(" + item.grade() + ")의 효과는 아직 확인하지 않아 계산하지 않았습니다.";
      else if (BigDecimal.valueOf(row.baseAttack()).compareTo(f) != 0)
        armGuardReason = "지금 팔 보호구 수치가 단계표와 달라 계산하지 않았습니다.";
      else {
        armGuard = new ArmGuard(level, item.grade());
        armGuardReason = null;
      }
      break;
    }
    if (reason != null)
      return new Model(
          null,
          null,
          null,
          null,
          null,
          null,
          parts,
          partReasons,
          c,
          reason,
          armGuard,
          armGuardReason);
    weaponPct = weaponPct.add(c.karmaWeaponPctPerLevel().multiply(BigDecimal.valueOf(karma)));
    var q = baseAttackPct.divide(HUNDRED, MATH);
    var r = b.divide(BigDecimal.ONE.add(q), MATH).subtract(f, MATH);
    var w = flatWeapon.add(accessoryFlat).multiply(BigDecimal.ONE.add(weaponPct), MATH);
    if (r.signum() <= 0 || w.signum() <= 0)
      return new Model(
          null,
          null,
          null,
          null,
          null,
          null,
          parts,
          partReasons,
          c,
          "기본 공격력 분해 값이 맞지 않아 계산하지 않았습니다.",
          armGuard,
          armGuardReason);
    var s = SIX.multiply(r.multiply(r, MATH), MATH).divide(w, MATH);
    return new Model(
        r,
        f,
        s,
        w,
        weaponPct,
        statMultiplier,
        parts,
        partReasons,
        c,
        null,
        armGuard,
        armGuardReason);
  }

  private static BigDecimal baseAttack(List<CharacterProfile.Stat> stats) {
    if (stats == null) return null;
    for (var stat : stats) {
      if (stat == null || !"공격력".equals(stat.type())) continue;
      for (var line : stat.tooltip()) {
        var matcher = line == null ? null : BASE_ATTACK_STAT.matcher(line);
        if (matcher != null && matcher.find())
          return new BigDecimal(matcher.group(1).replace(",", ""));
      }
    }
    return null;
  }

  private static BigDecimal flat(CharacterLoadout.EquipmentDetail detail, Pattern pattern) {
    for (var group : detail.effects()) {
      if (group == null) continue;
      for (var line : group.lines()) {
        if (line == null) continue;
        var matcher = pattern.matcher(line.strip());
        if (matcher.matches()) return new BigDecimal(matcher.group(1).replace(",", ""));
      }
    }
    return null;
  }

  /** 깨달음 아크 패시브 포인트 설명("6랭크 29레벨")의 레벨. 아크 패시브가 없으면 0. */
  private static Integer karmaLevel(CharacterLoadout loadout) {
    if (loadout.arkPassive() == null) return 0;
    for (var point : loadout.arkPassive().points()) {
      if (point == null || !"깨달음".equals(point.name())) continue;
      var matcher = point.description() == null ? null : KARMA_LEVEL.matcher(point.description());
      return matcher != null && matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }
    return 0;
  }
}
