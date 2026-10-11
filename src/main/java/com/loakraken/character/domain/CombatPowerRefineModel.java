package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
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
      Map<String, Map<Integer, Long>> jeonyulStatTable) {
    public Coefficients {
      avatarStatPctByGrade = Map.copyOf(avatarStatPctByGrade);
      avatarStatTypes = List.copyOf(avatarStatTypes);
      var table = new HashMap<String, Map<Integer, Long>>();
      jeonyulStatTable.forEach((part, levels) -> table.put(part, Map.copyOf(levels)));
      jeonyulStatTable = Map.copyOf(table);
    }
  }

  /** 계산할 수 있는 부위 하나(전율 장비): 장비 순번, 부위 이름, 지금 단계. */
  public record Part(int index, String type, int level) {}

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
      String unsupportedReason) {
    public Model {
      parts = parts == null ? Map.of() : Map.copyOf(parts);
      partReasons = partReasons == null ? Map.of() : Map.copyOf(partReasons);
    }

    /** 장비 index를 level 단계로 바꿀 수 있는지. 안 되면 이유 문장. */
    public String cannotSet(int index, int level) {
      var reason = partReasons.get(index);
      if (reason != null) return reason;
      if (unsupportedReason != null) return unsupportedReason;
      var part = parts.get(index);
      if (part == null) return "이 장비의 재련 단계 계산식을 아직 확인하지 않아 계산하지 않았습니다.";
      if (stat(part.type(), level) == null)
        return "전율 장비 " + level + "단계의 수치를 아직 확인하지 않아 계산하지 않았습니다(11~25단계만).";
      return null;
    }

    /** 장비 index → 새 단계로 바꾼 상태의 전투력 비. 계산할 수 없으면 null. */
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
      if (unsupportedReason != null) return null;
      var nextS = s.add(mainStatDelta.multiply(statMultiplier, MATH), MATH);
      var nextW = w.add(weaponFlatDelta.multiply(BigDecimal.ONE.add(weaponPct), MATH), MATH);
      for (var entry : levels.entrySet()) {
        var part = parts.get(entry.getKey());
        if (part == null) return null;
        var from = stat(part.type(), part.level());
        var to = stat(part.type(), entry.getValue());
        if (from == null || to == null) return null;
        var delta = BigDecimal.valueOf(to - from);
        if ("무기".equals(part.type()))
          nextW = nextW.add(delta.multiply(BigDecimal.ONE.add(weaponPct), MATH), MATH);
        else nextS = nextS.add(delta.multiply(statMultiplier, MATH), MATH);
      }
      if (nextS.signum() <= 0 || nextW.signum() <= 0) return null;
      var scale = nextS.divide(s, MATH).multiply(nextW.divide(w, MATH), MATH).sqrt(MATH);
      return r.multiply(scale, MATH).add(f, MATH).divide(r.add(f, MATH), MATH);
    }

    private Long stat(String type, int level) {
      var table = coefficients.jeonyulStatTable().get(type);
      return table == null ? null : table.get(level);
    }
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
      if (item.name() == null || !item.name().contains(JEONYUL))
        partReasons.put(index, "전율 장비만 계산합니다(업화·에스더 장비 단계표는 아직 확인하지 않음).");
      else if (level == null
          || !c.jeonyulStatTable().getOrDefault(item.type(), Map.of()).containsKey(level))
        partReasons.put(index, "지금 재련 단계의 수치를 아직 확인하지 않아 계산하지 않았습니다(11~25단계만).");
      else parts.put(index, new Part(index, item.type(), level));
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
    if (reason != null)
      return new Model(null, null, null, null, null, null, parts, partReasons, c, reason);
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
          "기본 공격력 분해 값이 맞지 않아 계산하지 않았습니다.");
    var s = SIX.multiply(r.multiply(r, MATH), MATH).divide(w, MATH);
    return new Model(r, f, s, w, weaponPct, statMultiplier, parts, partReasons, c, null);
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
