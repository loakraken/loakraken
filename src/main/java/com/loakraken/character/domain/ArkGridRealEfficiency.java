package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 아크 그리드 젬 옵션 실 효율(비교 기준 A 모델, #520). 전투력 계수로 낸 '전투력 기여'(CombatPowerArkGridModel)와 다른 정의다.
 *
 * <ul>
 *   <li>젬 옵션 효과 % = floor(레벨 × M ÷ 120) ÷ 100(옵션별 M, 데이터 파일).
 *   <li>공격력은 공격력 % 풀(100 + 장신구 '공격력 +x%' + 혼돈의 별 : 공격 코어 + 젬)에, 추가 피해는 추가 피해 풀(100 + 무기 품질 + 목걸이 '추가 피해
 *       +x%' + 혼돈의 해 : 안정적인 공격 코어 + 팔찌 추가 피해 줄 + 젬)에 더해진다. 보스 피해는 (1 + x ÷ 100)으로 따로 곱한다. 낙인력·아군
 *       효과는 딜러에 0이다.
 *   <li>효율 % = floor((뺀 전 ÷ 뺀 뒤 − 1) × 10000) ÷ 100(0.01 단위 내림). 옵션 줄은 그 옵션 전부를, 옵션 합은 젬 옵션 전부를, 계열 줄은 그
 *       계열(질서·혼돈) 젬의 레벨만 뺀다. 뺀 뒤 남는 레벨의 %는 레벨 식으로 다시 구한다.
 * </ul>
 *
 * 코어 포인트(17P 초과) 효율은 코어 효과 전체 점수가 필요해 여기서 내지 않는다. 계열별 젬 레벨 합이 공식 효과 레벨과 다르면(데이터 불일치) 계산하지
 * 않는다. 공식 호출이 없다.
 */
public final class ArkGridRealEfficiency {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Pattern CORE = Pattern.compile("^(질서|혼돈)의 (해|달|별) 코어");
  private static final Pattern ATTACK_PERCENT = Pattern.compile("^공격력 \\+([\\d.]+)%$");
  private static final Pattern ADD_DAMAGE_PERCENT = Pattern.compile("^추가 피해 \\+([\\d.]+)%$");
  private static final Pattern BRACELET_ADD_DAMAGE =
      Pattern.compile("추가 피해 \\+([\\d.]+)%|추가 피해가 ([\\d.]+)% 증가");
  static final String ATTACK = "공격력";
  static final String ADD_DAMAGE = "추가 피해";
  static final String BOSS = "보스 피해";
  public static final List<String> SERIES = List.of("질서", "혼돈");

  private ArkGridRealEfficiency() {}

  /** 데이터 파일(combat-power/ark-grid-real-table.json). 코어 표 키는 포인트 문자열("14"·"17"~"20"). */
  public record Table(
      String ruleVersion,
      String source,
      Map<String, Integer> levelMultiplier,
      Map<String, Map<String, BigDecimal>> chaosStarAttack,
      Map<String, Map<String, BigDecimal>> chaosSunStableAttack) {
    /** 레벨의 인게임 %. 레벨이 0 이하면 0. 모르는 옵션이면 null. */
    public BigDecimal percent(String option, int level) {
      var multiplier = levelMultiplier.get(option);
      if (multiplier == null) return null;
      if (level <= 0) return BigDecimal.ZERO;
      return BigDecimal.valueOf((long) level * multiplier / 120).movePointLeft(2);
    }
  }

  /** 옵션 한 줄. percent는 딜러 실 효율(낙인력·아군 효과는 0). */
  public record Option(String name, int level, BigDecimal inGamePercent, BigDecimal percent) {}

  /** 계열 한 줄: 그 계열 젬의 옵션 레벨만 뺐을 때. options는 그 계열이 가진 옵션마다 그 옵션만 뺐을 때. */
  public record Series(String series, BigDecimal optionsPercent, List<Option> options) {}

  /**
   * reason이 null이 아니면 계산하지 못한 것(다른 값은 null·빈 목록).
   *
   * @param attackPool 공격력 % 풀(100 + …, 젬 포함)
   * @param addDamagePool 추가 피해 % 풀(100 + …, 젬 포함)
   */
  public record Result(
      BigDecimal optionsPercent,
      BigDecimal attackPool,
      BigDecimal addDamagePool,
      List<Option> options,
      List<Series> series,
      String reason) {
    static Result unsupported(String reason) {
      return new Result(null, null, null, List.of(), List.of(), reason);
    }
  }

  public static Result compute(Table table, CharacterLoadout loadout) {
    var grid = loadout.arkGrid();
    if (grid == null || grid.effects().isEmpty())
      return Result.unsupported("아크 그리드 젬 효과가 없어 계산하지 않았습니다.");
    // 지금 효과 레벨(공식 효과 값의 레벨).
    var levels = new LinkedHashMap<String, Integer>();
    for (var effect : grid.effects()) {
      if (effect == null || effect.name() == null || effect.level() == null)
        return Result.unsupported("아크 그리드 젬 효과를 읽지 못해 계산하지 않았습니다.");
      var name = effect.name().strip();
      if (!table.levelMultiplier().containsKey(name))
        return Result.unsupported("확인하지 않은 젬 옵션(" + name + ")이 있어 계산하지 않았습니다.");
      levels.merge(name, effect.level(), Integer::sum);
    }
    // 계열별 젬 옵션 레벨 합. 공식 효과 레벨과 같아야 한다.
    var seriesLevels = new LinkedHashMap<String, Map<String, Integer>>();
    for (var series : SERIES) seriesLevels.put(series, new LinkedHashMap<>());
    for (var slot : grid.slots()) {
      if (slot == null || slot.name() == null) continue;
      var matcher = CORE.matcher(slot.name().strip());
      if (!matcher.find()) continue;
      var bucket = seriesLevels.get(matcher.group(1));
      for (var gem : slot.gems()) {
        if (gem == null || Boolean.FALSE.equals(gem.active()) || gem.detail() == null) continue;
        for (var option : gem.detail().options()) {
          if (option == null || option.name() == null || option.level() == null) continue;
          var name = option.name().strip();
          if (table.levelMultiplier().containsKey(name))
            bucket.merge(name, option.level(), Integer::sum);
        }
      }
    }
    for (var entry : levels.entrySet()) {
      int sum = 0;
      for (var series : SERIES) sum += seriesLevels.get(series).getOrDefault(entry.getKey(), 0);
      if (sum != entry.getValue())
        return Result.unsupported("젬 옵션 레벨이 효과 레벨과 맞지 않아 계산하지 않았습니다.");
    }

    var accessories = accessoryLines(loadout);
    var otherAttack = BigDecimal.ZERO;
    var otherAddDamage = BigDecimal.ZERO;
    for (var line : accessories.lines()) {
      var attack = decimal(ATTACK_PERCENT, line.text());
      if (attack != null) otherAttack = otherAttack.add(attack);
      var addDamage = decimal(ADD_DAMAGE_PERCENT, line.text());
      if (addDamage != null && "목걸이".equals(line.slot()))
        otherAddDamage = otherAddDamage.add(addDamage);
    }
    var quality = weaponQuality(loadout);
    var weapon = quality == null ? null : CombatPowerEquipmentModel.weaponAddDamage(quality);
    if (weapon == null) return Result.unsupported("무기 품질을 읽지 못해 계산하지 않았습니다.");
    otherAddDamage = otherAddDamage.add(weapon);
    for (var line : braceletLines(loadout)) {
      var matcher = BRACELET_ADD_DAMAGE.matcher(line);
      while (matcher.find())
        otherAddDamage =
            otherAddDamage.add(
                new BigDecimal(matcher.group(1) != null ? matcher.group(1) : matcher.group(2)));
    }
    for (var slot : grid.slots()) {
      var core = core(table, slot);
      if (core.reason() != null) return Result.unsupported(core.reason());
      otherAttack = otherAttack.add(core.attack());
      otherAddDamage = otherAddDamage.add(core.addDamage());
    }

    var now = Factors.of(table, levels, otherAttack, otherAddDamage);
    var options = new ArrayList<Option>();
    for (var entry : levels.entrySet()) {
      var without = new LinkedHashMap<>(levels);
      without.put(entry.getKey(), 0);
      options.add(
          new Option(
              entry.getKey(),
              entry.getValue(),
              table.percent(entry.getKey(), entry.getValue()),
              efficiency(now, Factors.of(table, without, otherAttack, otherAddDamage))));
    }
    var none = new LinkedHashMap<String, Integer>();
    for (var name : levels.keySet()) none.put(name, 0);
    var series = new ArrayList<Series>();
    for (var name : SERIES) {
      var own = seriesLevels.get(name);
      if (own.values().stream().allMatch(level -> level == 0)) continue;
      var remaining = new LinkedHashMap<String, Integer>();
      for (var entry : levels.entrySet())
        remaining.put(entry.getKey(), entry.getValue() - own.getOrDefault(entry.getKey(), 0));
      var lines = new ArrayList<Option>();
      for (var entry : own.entrySet()) {
        if (entry.getValue() == 0) continue;
        var one = new LinkedHashMap<>(levels);
        one.put(entry.getKey(), levels.get(entry.getKey()) - entry.getValue());
        lines.add(
            new Option(
                entry.getKey(),
                entry.getValue(),
                table.percent(entry.getKey(), entry.getValue()),
                efficiency(now, Factors.of(table, one, otherAttack, otherAddDamage))));
      }
      series.add(
          new Series(
              name,
              efficiency(now, Factors.of(table, remaining, otherAttack, otherAddDamage)),
              List.copyOf(lines)));
    }
    return new Result(
        efficiency(now, Factors.of(table, none, otherAttack, otherAddDamage)),
        now.attackPool(),
        now.addDamagePool(),
        List.copyOf(options),
        List.copyOf(series),
        null);
  }

  /** 젬 효과가 들어가는 세 배수의 풀(%). 점수는 세 풀의 곱에 비례한다. */
  private record Factors(BigDecimal attackPool, BigDecimal addDamagePool, BigDecimal bossPool) {
    static Factors of(
        Table table, Map<String, Integer> levels, BigDecimal otherAttack, BigDecimal otherAdd) {
      return new Factors(
          HUNDRED.add(otherAttack).add(table.percent(ATTACK, levels.getOrDefault(ATTACK, 0))),
          HUNDRED.add(otherAdd).add(table.percent(ADD_DAMAGE, levels.getOrDefault(ADD_DAMAGE, 0))),
          HUNDRED.add(table.percent(BOSS, levels.getOrDefault(BOSS, 0))));
    }

    BigDecimal product() {
      return attackPool.multiply(addDamagePool, MATH).multiply(bossPool, MATH);
    }
  }

  /** floor((지금 ÷ 뺀 뒤 − 1) × 10000) ÷ 100. */
  private static BigDecimal efficiency(Factors now, Factors without) {
    return now.product()
        .divide(without.product(), MATH)
        .subtract(BigDecimal.ONE)
        .multiply(BigDecimal.valueOf(10000))
        .setScale(0, RoundingMode.FLOOR)
        .movePointLeft(2);
  }

  private record CoreValue(BigDecimal attack, BigDecimal addDamage, String reason) {}

  /** 혼돈의 별 : 공격은 공격력 풀, 혼돈의 해 : 안정적인 공격은 추가 피해 풀에 더한다. 10~13P 0, 14~16P는 14 값, 17~20P는 그 값. */
  private static CoreValue core(Table table, CharacterLoadout.GridSlot slot) {
    var zero = new CoreValue(BigDecimal.ZERO, BigDecimal.ZERO, null);
    if (slot == null || slot.name() == null) return zero;
    var name = slot.name().strip();
    var matcher = CORE.matcher(name);
    if (!matcher.find() || !"혼돈".equals(matcher.group(1))) return zero;
    int colon = name.indexOf(':');
    var coreName = colon < 0 ? "" : name.substring(colon + 1).strip();
    Map<String, Map<String, BigDecimal>> source;
    boolean attack;
    if ("별".equals(matcher.group(2)) && "공격".equals(coreName)) {
      source = table.chaosStarAttack();
      attack = true;
    } else if ("해".equals(matcher.group(2)) && "안정적인 공격".equals(coreName)) {
      source = table.chaosSunStableAttack();
      attack = false;
    } else return zero;
    if (slot.point() == null)
      return new CoreValue(null, null, "코어 포인트를 읽지 못해 계산하지 않았습니다.");
    int point = slot.point();
    if (point < 14) return zero;
    var key = point < 17 ? "14" : String.valueOf(Math.min(point, 20));
    var byGrade = slot.grade() == null ? null : source.get(slot.grade().strip());
    var value = byGrade == null ? null : byGrade.get(key);
    if (value == null && point < 17) value = source.get("유물").get("14");
    if (value == null)
      return new CoreValue(
          null, null, "확인하지 않은 코어 등급(" + slot.grade() + ")이 있어 계산하지 않았습니다.");
    return attack
        ? new CoreValue(value, BigDecimal.ZERO, null)
        : new CoreValue(BigDecimal.ZERO, value, null);
  }

  private record AccessoryLine(String slot, String text) {}

  private record AccessoryLines(List<AccessoryLine> lines) {}

  private static AccessoryLines accessoryLines(CharacterLoadout loadout) {
    var lines = new ArrayList<AccessoryLine>();
    for (var item : loadout.equipment()) {
      if (item == null || item.detail() == null) continue;
      if (!List.of("목걸이", "귀걸이", "반지").contains(item.type())) continue;
      for (var effect : item.detail().polishEffects()) {
        if (effect == null || effect.name() == null || effect.value() == null) continue;
        lines.add(new AccessoryLine(item.type(), effect.name().strip() + " " + effect.value().strip()));
      }
    }
    return new AccessoryLines(lines);
  }

  private static Integer weaponQuality(CharacterLoadout loadout) {
    for (var item : loadout.equipment())
      if (item != null && "무기".equals(item.type()) && item.detail() != null)
        return item.detail().quality();
    return null;
  }

  private static List<String> braceletLines(CharacterLoadout loadout) {
    var lines = new ArrayList<String>();
    for (var item : loadout.equipment()) {
      if (item == null || !"팔찌".equals(item.type()) || item.detail() == null) continue;
      for (var effect : item.detail().braceletEffects()) {
        if (effect == null || effect.text() == null) continue;
        for (var line : effect.text().split("\n")) if (!line.isBlank()) lines.add(line.strip());
      }
    }
    return lines;
  }

  private static BigDecimal decimal(Pattern pattern, String text) {
    var matcher = pattern.matcher(text);
    return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
  }
}
