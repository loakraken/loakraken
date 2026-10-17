package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전투력 시뮬레이터 팔찌(#327). 계산 검증 2026-10-11 21:00 모델 확정, 표본 verification/combat-power/의 팔찌
 * 표본(2026-10-11), 계수 리소스 combat-power/bracelet-coefficients.json.
 *
 * <p>팔찌 효과 줄마다 독립 배수다. 특수 옵션은 (1 + c/100), 전투 특성(치명·특화·신속)은 캐릭터 합 T에 대해 (1 + 0.0003·T), 팔찌 도약 포인트는
 * 도약 합 P에 대해 (1 + 0.002·P), "무기 공격력 +N"은 재련 모델의 W 항이다. 옵션은 공식 문구 속 숫자로 판단한다("3%"와 "3.0%"는 같은 옵션).
 * 바꾸는 줄만 비율에 들어가므로 모르는 줄이 있어도 그 줄을 건드리지 않으면 계산한다.
 */
public final class CombatPowerBraceletModel {
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final Pattern STAT = Pattern.compile("^(치명|특화|신속|제압|인내|숙련) \\+(\\d+)$");
  private static final Pattern MAIN_STAT = Pattern.compile("^(힘|민첩|지능) \\+(\\d+)$");
  private static final Pattern WEAPON_FLAT = Pattern.compile("^무기 공격력 \\+(\\d+)$");
  private static final Pattern WEAPON_BUFF = Pattern.compile("^무기 공격력이 (\\d+) 증가한다");
  private static final Pattern NUMBER = Pattern.compile("[\\d.]+");
  public static final String REMOVE = "없음";

  private CombatPowerBraceletModel() {}

  public record Coefficients(
      String ruleVersion,
      String source,
      BigDecimal combatStatPerPoint,
      List<String> combatStats,
      BigDecimal leapPerPoint,
      List<String> zeroKeywords,
      List<Family> families) {
    public Coefficients {
      combatStats = List.copyOf(combatStats);
      zeroKeywords = List.copyOf(zeroKeywords);
      families = List.copyOf(families);
    }

    public Family family(String name) {
      for (var family : families) if (family.name().equals(name)) return family;
      return null;
    }
  }

  /** 특수 옵션 계열: 공식 문구(숫자 자리 {x}·{y})와 등급별 숫자·전투력 %. */
  public record Family(String name, String pattern, Map<String, Grade> grades) {
    public Family {
      grades = Map.copyOf(grades);
    }

    /** 등급 grade의 공식 문구. */
    public String text(String grade) {
      var value = grades.get(grade);
      if (value == null) return null;
      var text = pattern.replace("{x}", plain(value.x()));
      return value.y() == null ? text : text.replace("{y}", plain(value.y()));
    }

    private Pattern regex() {
      var normalized = normalize(pattern);
      var builder = new StringBuilder("^");
      int at = 0;
      var holes = Pattern.compile("\\{[xy]\\}").matcher(normalized);
      while (holes.find()) {
        builder.append(Pattern.quote(normalized.substring(at, holes.start()))).append("([\\d.]+)");
        at = holes.end();
      }
      return Pattern.compile(
          builder.append(Pattern.quote(normalized.substring(at))).append('$').toString());
    }
  }

  public record Grade(BigDecimal x, BigDecimal y, BigDecimal pct) {}

  public enum Kind {
    SPECIAL,
    STAT,
    MAIN_STAT,
    WEAPON_FLAT,
    ZERO,
    UNKNOWN
  }

  /** 팔찌 효과 줄 하나의 해석. weaponFlat은 재련 W 항에 들어가는 고정 무기 공격력(없으면 0). */
  public record Line(
      String text,
      Kind kind,
      BigDecimal pct,
      String stat,
      int statPoints,
      BigDecimal weaponFlat,
      String reason) {
    static final Line REMOVED = new Line(REMOVE, Kind.ZERO, null, null, 0, BigDecimal.ZERO, null);

    boolean combatStat(Coefficients c) {
      return kind == Kind.STAT && c.combatStats().contains(stat);
    }
  }

  /** 비율과 재련 모델에 넘기는 W 항 고정 무기 공격력·S 항 주스탯(힘·민첩·지능) 변화. */
  public record Factor(BigDecimal ratio, BigDecimal weaponFlatDelta, BigDecimal mainStatDelta) {}

  public record Model(
      List<Line> lines,
      BigDecimal combatStatTotal,
      BigDecimal leapTotal,
      int braceletLeap,
      Coefficients coefficients) {
    public Model {
      lines = List.copyOf(lines);
    }

    /** 줄 line을 이 옵션으로 바꿀 수 있는지(지금 줄이 계산 가능한지 포함). 안 되면 이유 문장. */
    public String cannotReplace(int line, Line next) {
      var current = lines.get(line);
      if (current.kind() == Kind.UNKNOWN) return current.reason();
      if (next.kind() == Kind.UNKNOWN) return next.reason();
      if ((current.combatStat(coefficients) || next.combatStat(coefficients))
          && combatStatTotal == null) return "전투 특성 합(치명·특화·신속)을 읽지 못해 계산하지 않았습니다.";
      return null;
    }

    /** 줄 번호 → 새 줄(REMOVED면 빼기)로 바꾼 상태의 비율. 계산할 수 없으면 null. */
    public Factor factor(Map<Integer, Line> replacements) {
      var ratio = BigDecimal.ONE;
      var statDelta = 0;
      var weaponDelta = BigDecimal.ZERO;
      var mainStatDelta = BigDecimal.ZERO;
      for (var entry : replacements.entrySet()) {
        var current = lines.get(entry.getKey());
        var next = entry.getValue();
        if (cannotReplace(entry.getKey(), next) != null) return null;
        ratio = ratio.multiply(special(next), MATH).divide(special(current), MATH);
        if (current.combatStat(coefficients)) statDelta -= current.statPoints();
        if (next.combatStat(coefficients)) statDelta += next.statPoints();
        weaponDelta = weaponDelta.add(next.weaponFlat()).subtract(current.weaponFlat());
        if (next.kind() == Kind.MAIN_STAT)
          mainStatDelta = mainStatDelta.add(BigDecimal.valueOf(next.statPoints()));
        if (current.kind() == Kind.MAIN_STAT)
          mainStatDelta = mainStatDelta.subtract(BigDecimal.valueOf(current.statPoints()));
      }
      if (statDelta != 0) ratio = ratio.multiply(statRatio(statDelta), MATH);
      return new Factor(ratio, weaponDelta, mainStatDelta);
    }

    /** 팔찌를 뺐을 때(모든 줄 + 팔찌 도약 포인트)의 비율. 계산할 수 없으면 null. */
    public Factor without() {
      var all = new HashMap<Integer, Line>();
      for (int i = 0; i < lines.size(); i++) all.put(i, Line.REMOVED);
      var factor = factor(all);
      if (factor == null) return null;
      var leap = leapRatio();
      if (leap == null) return null;
      return new Factor(
          factor.ratio().multiply(leap, MATH), factor.weaponFlatDelta(), factor.mainStatDelta());
    }

    /** 팔찌 도약 포인트만 뺐을 때의 비율. 도약 합을 읽지 못하면 null. */
    public BigDecimal leapRatio() {
      if (braceletLeap == 0) return BigDecimal.ONE;
      if (leapTotal == null) return null;
      var leap = coefficients.leapPerPoint();
      var now = BigDecimal.ONE.add(leap.multiply(leapTotal, MATH), MATH);
      var next =
          BigDecimal.ONE.add(
              leap.multiply(leapTotal.subtract(BigDecimal.valueOf(braceletLeap)), MATH), MATH);
      return next.divide(now, MATH);
    }

    /** 계산할 수 없는 첫 줄의 이유(빼기 계산용). */
    public String firstUnknown() {
      for (var line : lines) if (line.kind() == Kind.UNKNOWN) return line.reason();
      return null;
    }

    private BigDecimal statRatio(int delta) {
      var per = coefficients.combatStatPerPoint();
      var now = BigDecimal.ONE.add(per.multiply(combatStatTotal, MATH), MATH);
      var next =
          BigDecimal.ONE.add(
              per.multiply(combatStatTotal.add(BigDecimal.valueOf(delta)), MATH), MATH);
      return next.divide(now, MATH);
    }

    private static BigDecimal special(Line line) {
      return line.kind() == Kind.SPECIAL
          ? BigDecimal.ONE.add(line.pct().divide(HUNDRED, MATH), MATH)
          : BigDecimal.ONE;
    }
  }

  /** 지금 팔찌로 모델을 만든다. 팔찌가 없으면 null. */
  public static Model of(
      Coefficients c, CharacterLoadout loadout, List<CharacterProfile.Stat> stats) {
    var bracelet = bracelet(loadout);
    if (bracelet == null) return null;
    var lines = new ArrayList<Line>();
    for (var effect : bracelet.detail().braceletEffects())
      lines.add(parse(c, effect == null ? null : effect.text()));
    var leapBonus = bracelet.detail().arkPassivePoint();
    int braceletLeap =
        leapBonus != null && "도약".equals(leapBonus.name()) && leapBonus.value() != null
            ? leapBonus.value()
            : 0;
    return new Model(lines, combatStatTotal(c, stats), leapTotal(loadout), braceletLeap, c);
  }

  /** 계열 이름·등급으로 새 줄을 만든다(빼기는 REMOVE). 모르는 이름이면 UNKNOWN 줄. */
  public static Line option(Coefficients c, String name, String grade) {
    if (REMOVE.equals(name)) return Line.REMOVED;
    var family = c.family(name);
    if (family == null || family.grades().get(grade) == null)
      return unknown(name, "이 팔찌 옵션(" + name + ")은 아직 계산하지 않습니다.");
    var value = family.grades().get(grade);
    return new Line(family.text(grade), Kind.SPECIAL, value.pct(), null, 0, BigDecimal.ZERO, null);
  }

  /** 팔찌의 W 항 고정 무기 공격력 합("무기 공격력 +N"과 "무기 공격력이 N 증가한다"의 N, 계산 검증 23:45). */
  public static BigDecimal weaponFlat(CharacterLoadout loadout) {
    var bracelet = bracelet(loadout);
    if (bracelet == null) return BigDecimal.ZERO;
    var total = BigDecimal.ZERO;
    for (var effect : bracelet.detail().braceletEffects()) {
      if (effect == null || effect.text() == null) continue;
      total = total.add(fixedWeapon(normalize(effect.text())));
    }
    return total;
  }

  static Line parse(Coefficients c, String raw) {
    if (raw == null || raw.isBlank()) return unknown(raw, "팔찌 효과 줄을 읽지 못해 계산하지 않았습니다.");
    var text = normalize(raw);
    var stat = STAT.matcher(text);
    if (stat.matches())
      return new Line(
          text,
          Kind.STAT,
          null,
          stat.group(1),
          Integer.parseInt(stat.group(2)),
          BigDecimal.ZERO,
          null);
    // 힘·민첩·지능 +N은 재련 모델 S 항(S' = S + N × mS, 계산 검증 #332 회신: 목걸이 같은 수치와 같은 배수).
    var main = MAIN_STAT.matcher(text);
    if (main.matches())
      return new Line(
          text,
          Kind.MAIN_STAT,
          null,
          main.group(1),
          Integer.parseInt(main.group(2)),
          BigDecimal.ZERO,
          null);
    var flat = WEAPON_FLAT.matcher(text);
    if (flat.matches())
      return new Line(text, Kind.WEAPON_FLAT, null, null, 0, new BigDecimal(flat.group(1)), null);
    if (WEAPON_BUFF.matcher(text).find())
      return new Line(
          text,
          Kind.UNKNOWN,
          null,
          null,
          0,
          fixedWeapon(text),
          "이 팔찌 옵션의 버프 부분 계수를 아직 확인하지 않아 계산하지 않았습니다.");
    for (var family : c.families()) {
      var matcher = family.regex().matcher(text);
      if (!matcher.matches()) continue;
      for (var grade : family.grades().values())
        if (same(matcher, 1, grade.x()) && (grade.y() == null || same(matcher, 2, grade.y())))
          return new Line(text, Kind.SPECIAL, grade.pct(), null, 0, BigDecimal.ZERO, null);
      return unknown(text, "이 팔찌 옵션의 등급 수치를 아직 확인하지 않아 계산하지 않았습니다.");
    }
    for (var keyword : c.zeroKeywords())
      if (text.contains(keyword))
        return new Line(text, Kind.ZERO, null, null, 0, BigDecimal.ZERO, null);
    return unknown(text, "이 팔찌 옵션의 전투력 계수를 아직 확인하지 않아 계산하지 않았습니다.");
  }

  private static Line unknown(String text, String reason) {
    return new Line(text, Kind.UNKNOWN, null, null, 0, BigDecimal.ZERO, reason);
  }

  private static BigDecimal fixedWeapon(String text) {
    var flat = WEAPON_FLAT.matcher(text);
    if (flat.matches()) return new BigDecimal(flat.group(1));
    var buff = WEAPON_BUFF.matcher(text);
    return buff.find() ? new BigDecimal(buff.group(1)) : BigDecimal.ZERO;
  }

  private static boolean same(Matcher matcher, int group, BigDecimal expected) {
    if (matcher.groupCount() < group) return false;
    var value = matcher.group(group);
    return NUMBER.matcher(value).matches() && new BigDecimal(value).compareTo(expected) == 0;
  }

  private static CharacterLoadout.Equipment bracelet(CharacterLoadout loadout) {
    for (var item : loadout.equipment())
      if (item != null && "팔찌".equals(item.type()) && item.detail() != null) return item;
    return null;
  }

  private static BigDecimal combatStatTotal(Coefficients c, List<CharacterProfile.Stat> stats) {
    if (stats == null) return null;
    var total = BigDecimal.ZERO;
    int found = 0;
    for (var stat : stats) {
      if (stat == null || !c.combatStats().contains(stat.type()) || stat.value() == null) continue;
      try {
        total = total.add(new BigDecimal(stat.value().replace(",", "").strip()));
        found++;
      } catch (NumberFormatException exception) {
        return null;
      }
    }
    return found == c.combatStats().size() ? total : null;
  }

  private static BigDecimal leapTotal(CharacterLoadout loadout) {
    if (loadout.arkPassive() == null) return null;
    for (var point : loadout.arkPassive().points())
      if (point != null && "도약".equals(point.name()) && point.value() != null)
        return BigDecimal.valueOf(point.value());
    return null;
  }

  static String normalize(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  private static String plain(BigDecimal value) {
    return value.toPlainString();
  }
}
