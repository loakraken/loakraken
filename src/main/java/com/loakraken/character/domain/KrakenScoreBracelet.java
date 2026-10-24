package com.loakraken.character.domain;

import static com.loakraken.character.domain.KrakenScoreEngine.jsRound;
import static com.loakraken.character.domain.KrakenScoreEngine.truthy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 크라켄 점수 팔찌 총배율(#539, 원본 모듈 12921과 의존 85041·28245·32674·20084·17468·78512·35231·71552·34006·35724).
 * 점수의 마지막 곱이다. 원본 연산 순서를 그대로 옮긴다(double).
 */
public final class KrakenScoreBracelet {
  private KrakenScoreBracelet() {}

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 원본 jN: 점수식에서 팔찌 총배율에 곱하는 상수. */
  public static final double SCORE_CONSTANT = 1.021;

  private static final double SPEED_UNIT = .017 / 6;

  /** 팔찌 총배율(원본 d6 = L(e).factors.totalMultiplier). */
  public static double total(JsonNode e, KrakenScoreEngine.Tables tables) {
    return total(e, tables, false);
  }

  /**
   * 팔찌 총배율. devil = 원본 정보 탭 설정 '악마 피해'(12921 sh의 bangleDevilDamageEnabled → 35231
   * devilDamageEnabled): 켜면 진피 비에 팔찌 옵션의 악마 피해 배율을 더 곱한다. 점수(d6)는 늘 꺼진 값이다.
   */
  public static double total(JsonNode e, KrakenScoreEngine.Tables tables, boolean devil) {
    var n = bangle(e);
    var l = n == null ? List.<String>of() : strings(n.path("option"));
    if (n == null || !truthy(n.path("tier")) || l.isEmpty()) return 1;
    var tier = n.path("tier");
    var entries = new ArrayList<JsonNode>();
    for (var option : l) entries.add(entry(tables, tier, option));
    var stats = optionStats(n);
    double g = attackRatio(e, tables, l, tier);
    double p = addDamageRatio(e, tables);
    double r = statRatio(e, stats);
    double w = critRatio(e, tables, entries);
    double speed = speedRatio(e, tables, entries);
    double i = finalDamage(e, tables, devil);
    double staggered = 1 + (product(entries, "staggeredTargetDmg") - 1) * .25;
    double x = fallbackRatio(e, tables, l, entries, tier);
    return g * p * r * w * speed * i * staggered * x;
  }

  // ---------------------------------------------------------------- 공격력 비(85041 ZQ·Gg·kj)

  private static double attackRatio(
      JsonNode e, KrakenScoreEngine.Tables tables, List<String> options, JsonNode tier) {
    boolean any = false;
    for (var option : options) if (isAttackOption(tables, tier, option)) any = true;
    if (!any) return 1;
    var i = withBangleOptions(e, tables, false);
    var u = withBangleOptions(e, tables, true);
    var uBangle = bangle(u);
    var v = uBangle == null ? List.<String>of() : strings(uBangle.path("option"));
    var d = finalAttack(i, tables);
    double c = d.finalAttack;
    if (c == 0) return 1;
    double s = 0;
    for (var option : v) {
      var entry = entry(tables, tier, option);
      if (entry != null && entry.has("weaponAtkPlus") && !entry.get("weaponAtkPlus").isNull())
        s += entry.get("weaponAtkPlus").asDouble();
    }
    double f = 0;
    for (var option : v) {
      var matcher = STAT_ANY.matcher(option);
      while (matcher.find()) {
        var number = PLUS_NUMBER.matcher(matcher.group());
        if (number.find()) f += Double.parseDouble(number.group(1).replace(",", ""));
      }
    }
    if (s == 0) {
      double other = finalAttack(u, tables).finalAttack;
      return other != 0 ? other / c : 1;
    }
    Double m = s > 0 ? weaponWithBonus(d, s) : null;
    double l = attackWith(i, d, f, m);
    return l != 0 ? l / c : 1;
  }

  private record FinalAttack(
      double finalAttack,
      double powerIndex,
      double weaponWithBracelet,
      double weaponBase,
      double weaponRatio,
      double attackBonus,
      double attackPercentRatio,
      double attackFlat,
      double baseAttackFlat) {}

  private static final Pattern STR = Pattern.compile("힘\\s*\\+(\\d+)");
  private static final Pattern DEX = Pattern.compile("민첩\\s*\\+(\\d+)");
  private static final Pattern INT = Pattern.compile("지능\\s*\\+(\\d+)");

  /** 85041 ZQ: 최종 공격력과 분해 값. */
  private static FinalAttack finalAttack(JsonNode e, KrakenScoreEngine.Tables tables) {
    double weapon = e.path("armory").path("equipment").path("weapon").path("stat").asDouble(0);
    var v = weaponOptions(e);
    double p = .1 * e.path("arkPassive").path("enlightenment").path("karmalevel").asDouble(0);
    double k = KrakenScoreEngine.braceletWeaponForBracelet(e, tables);
    var s =
        KrakenScoreEngine.coreBonus(e, tables, "혼돈의 별 코어 : 무기", "weaponAtkPlus", "weaponAtkPer");
    var y = KrakenScoreEngine.vambraceValues(e);
    double bonus =
        (e.path("armory").path("abilityStone").path("attackbonus").asDouble(0)
                    + e.path("gem").path("attackBonus").asDouble(0)
                    + y[2])
                / 100
            + 1;
    var r = attackOptions(e);
    double w = 0;
    for (var effect : e.path("arkGrid").path("gemEffects"))
      if ("공격력".equals(effect.path("name").asString(""))) {
        w = effect.path("effect").isNumber() ? effect.path("effect").asDouble() : 0;
        break;
      }
    var b = KrakenScoreEngine.coreBonus(e, tables, "혼돈의 별 코어 : 공격", "atkPlus", "atkPer");
    double f = engravingAttackPercent(e, tables);
    double str = 0;
    double dex = 0;
    double intl = 0;
    for (var option : strings(e.path("armory").path("accessory").path("bangle").path("option"))) {
      var a = STR.matcher(option);
      if (a.find()) str += Double.parseDouble(a.group(1));
      var b2 = DEX.matcher(option);
      if (b2.find()) dex += Double.parseDouble(b2.group(1));
      var c2 = INT.matcher(option);
      if (c2.find()) intl += Double.parseDouble(c2.group(1));
    }
    double percent = v[1] + p + s[1];
    double base = weapon + v[0] + s[0] + y[0];
    double ratio = percent / 100 + 1;
    double a = Math.floor(base * ratio);
    double withBracelet = Math.floor((base + k) * ratio);
    double m = r[0] + b[0];
    double u = r[1] + w + b[1];
    double h = u / 100 + 1;
    double g = (u + f) / 100 + 1;
    double bangleStats = str + dex + intl;
    double t = KrakenScoreEngine.attack(e, tables).attack();
    double o;
    if (withBracelet == 0 || h == 0 || bonus == 0) o = 0;
    else {
      double e1 = t / h - m;
      o = e1 <= 0 ? 0 : jsRound(6 / withBracelet * Math.pow(Math.max(0, e1 / bonus - y[1]), 2));
    }
    double finalAttack =
        withBracelet != 0 && bonus != 0 && g != 0 && !(o <= 0)
            ? Math.floor(((Math.sqrt(o * withBracelet / 6) + y[1]) * bonus + m + 0) * g)
            : 0;
    return new FinalAttack(finalAttack, o, withBracelet, base, ratio, bonus, g, m, y[1]);
  }

  /** 85041 Gg: (무기 공격력 기본합 + t) × 배율 내림. */
  private static double weaponWithBonus(FinalAttack d, double t) {
    return d.weaponBase != 0 && d.weaponRatio != 0
        ? Math.floor((d.weaponBase + t) * d.weaponRatio)
        : 0;
  }

  /** 85041 kj: 힘민지·무기 공격력을 바꾼 최종 공격력. */
  private static double attackWith(
      JsonNode e, FinalAttack t, double powerStatBonus, Double weaponAtk) {
    if (t.finalAttack == 0) return 0;
    double f = weaponAtk != null ? weaponAtk : t.weaponWithBracelet;
    double s = t.attackBonus;
    double d = t.attackPercentRatio + 0 / 100.0;
    double flat = t.attackFlat + 0;
    double v = t.baseAttackFlat;
    double p = 0;
    double k = t.powerIndex;
    double avatar = e.path("avatar").path("avatarStats").asDouble(0) / 100 + 1;
    if (f == 0 || s == 0 || d == 0 || avatar == 0) return 0;
    double y = (k / avatar + powerStatBonus) * avatar;
    return y <= 0 ? 0 : Math.floor(((Math.sqrt(y * f / 6) + v) * s + flat + p) * d);
  }

  /** 34006 E: 아드레날린·에테르 포식자 공격력 %(각인서 + 스톤). */
  private static double engravingAttackPercent(JsonNode e, KrakenScoreEngine.Tables tables) {
    var engraving = e.path("engraving");
    if (!truthy(engraving)) return 0;
    Double adrenaline = null;
    Double ether = null;
    for (var item : engraving) {
      var raw = item.path("name").asString("");
      var name = raw.replaceAll("\\s", "");
      var grade = grade(item);
      if (grade == null) continue;
      var level = KrakenScoreEngine.jsString(item.path("level"));
      if (name.equals("아드레날린")) {
        adrenaline =
            stone(
                s0Value(tables, raw, name, grade, level, "atkPer"),
                item.path("stone"),
                ADRENALINE_STONE);
        continue;
      }
      if (name.equals("에테르포식자"))
        ether =
            stone(
                s0Value(tables, raw, name, grade, level, "atkPer"),
                item.path("stone"),
                ETHER_STONE);
    }
    return (adrenaline != null ? adrenaline : 0) + (ether != null ? ether : 0);
  }

  private static final Map<String, Double> ADRENALINE_STONE =
      Map.of("1", 2.88, "2", 3.6, "3", 4.98, "4", 5.7);
  private static final Map<String, Double> ETHER_STONE =
      Map.of("1", 2.7, "2", 3.51, "3", 4.86, "4", 5.4);

  // ---------------------------------------------------------------- 추가 피해 비(28245)

  private static double addDamageRatio(JsonNode e, KrakenScoreEngine.Tables tables) {
    double t = KrakenScoreEngine.additionalDamageSum(e);
    double without = t / 100 + 1;
    double bangleAdd = 0;
    var bangle = e.path("armory").path("accessory").path("bangle");
    var tier = bangle.path("tier");
    var option = bangle.path("option");
    if (truthy(tier) && truthy(option)) {
      var row = tables.get("we").path(KrakenScoreEngine.jsString(tier));
      for (var line : option) {
        var entry = row.path(line.asString(""));
        if (truthy(entry) && truthy(entry.path("addDmg")))
          bangleAdd += entry.path("addDmg").asDouble();
      }
    }
    double with = (t + bangleAdd) / 100 + 1;
    return without != 0 && with != 0 ? with / without : 1;
  }

  // ---------------------------------------------------------------- 특성 비

  private static double statRatio(JsonNode e, double[] stats) {
    double u = stats[0] + stats[2] + stats[1];
    if (u == 0) return 1;
    double v =
        e.path("stats").path("critical").asDouble(0)
            + e.path("stats").path("haste").asDouble(0)
            + e.path("stats").path("special").asDouble(0);
    double d = statFactor(v);
    double c = statFactor(v + u);
    return d != 0 && c != 0 ? c / d : 1;
  }

  private static double statFactor(double e) {
    return e / 100 * 4.1 / 100 + 1.3;
  }

  private static final Pattern OPTION_STAT =
      Pattern.compile("(치명|특화|신속|제압|인내|숙련)\\s*\\+([\\d,]+(?:\\.\\d+)?)");

  /** 35724 r: 팔찌 특성 [치명, 특화, 신속]. */
  private static double[] optionStats(JsonNode bangle) {
    double critical = 0;
    double special = 0;
    double haste = 0;
    for (var option : strings(bangle.path("option"))) {
      var m = OPTION_STAT.matcher(option);
      if (!m.find()) continue;
      double value = Double.parseDouble(m.group(2).replace(",", ""));
      switch (m.group(1)) {
        case "치명" -> critical += value;
        case "특화" -> special += value;
        case "신속" -> haste += value;
        default -> {}
      }
    }
    return new double[] {critical, special, haste};
  }

  // ---------------------------------------------------------------- 치명 비(32674·20084·17468)

  private static double critRatio(
      JsonNode e, KrakenScoreEngine.Tables tables, List<JsonNode> entries) {
    double o = sum(entries, "critRate");
    double c = sum(entries, "critDmg");
    double s = 1;
    for (var entry : entries) {
      if (entry == null) continue;
      if (present(entry, "specCritFinalDmg")) s *= entry.get("specCritFinalDmg").asDouble();
      else if (present(entry, "critFinalDmg")) s *= entry.get("critFinalDmg").asDouble();
    }
    double f = product(entries, "atkBuffPlus");
    if (o == 0 && c == 0 && s == 1 && f == 1) return 1;
    double m = critRate(e, tables);
    double l = critDamage(e, tables);
    double g = critFinalDamage(e, tables);
    double p = 0;
    for (var node : e.path("arkPassive").path("evolution").path("nodes"))
      if ("뭉툭한 가시".equals(node.path("name").asString(""))) {
        p = node.path("level").isNumber() ? node.path("level").asDouble() : 0;
        break;
      }
    double k = p == 1 ? 116 : p >= 2 ? 120 : 100;
    double rate = Math.min(m, k);
    double b = critValue(rate, l, g, p);
    if (b <= 0) return s * f;
    double dmg = critValue(Math.min(rate + 1, k), l, g, p);
    double r = balance(dmg / b - 1, critValue(rate, l + 1, g, p) / b - 1);
    return (1 + .007 * (1 + .024 * r) * Math.max(0, Math.min(rate + o, k) - rate))
        * (1 + .0035 * (1 - .024 * r) * c)
        * s
        * f;
  }

  private static double balance(double e, double t) {
    double r = Math.abs(e) + Math.abs(t);
    return r == 0 ? 0 : Math.min(Math.max((e - t) / r, -1), 1);
  }

  private static double critValue(double e, double t, double r, double n) {
    double i = Math.min(e, 100) / 100;
    return (1 - i + t / 100 * i * r)
        * (n > 0
            ? 1 + (n == 1 ? linear(e, 7.5, 1.25, 52.5) : n >= 2 ? linear(e, 15, 1.5, 75) : 0) / 100
            : 1);
  }

  private static double linear(double e, double t, double r, double n) {
    return Math.min(t + Math.max(0, Math.min(e, 120) - 80) * r, n);
  }

  private static final Pattern RING_CRIT = Pattern.compile("치명타 적중률\\s*\\+(\\d+(\\.\\d+)?)%");

  /** 32674 k: 최종 치적(팔찌 제외). */
  private static double critRate(JsonNode e, KrakenScoreEngine.Tables tables) {
    double k = e.path("stats").path("critical").asDouble(0);
    double p = ringSum(e, RING_CRIT);
    double r = Math.floor(k / .2794) / 100;
    double f = 0;
    for (var core : e.path("arkGrid").path("core")) {
      var value = coreTable(tables, "o9", core).path("critRate");
      if (!value.isMissingNode()) f += value.asDouble();
    }
    double h = 0;
    for (var node : e.path("arkPassive").path("evolution").path("nodes")) {
      double level = node.path("level").asDouble();
      switch (node.path("name").asString("")) {
        case "예리한 감각" -> h += 4 * level;
        case "혼신의 강타" -> h += 12 * level;
        case "일격" -> h += 10 * level;
        case "달인" -> h += 7 * level;
        default -> {}
      }
    }
    double enlightenment = 0;
    for (var node : e.path("arkPassive").path("enlightenment").path("nodes")) {
      double level = node.path("level").asDouble();
      switch (node.path("name").asString("")) {
        case "전술 훈련" -> enlightenment += 1.6 * level;
        case "전환난무" -> enlightenment += .8 * level;
        case "체술 강화" -> enlightenment += 1.2 * level;
        case "퀵 드로우" -> enlightenment += level;
        case "교감 강화" -> enlightenment += 1.4 * level;
        case "단련" -> {
          if (!"이슬비".equals(e.path("profile").path("secondClass").asString("")))
            enlightenment += level;
        }
        case "자연체" -> {
          boolean fist = false;
          for (var core : e.path("arkGrid").path("core"))
            if ("질서의 해 코어 : 적수공권".equals(core.path("name").asString(""))) fist = true;
          if (fist) enlightenment += 3 * level;
        }
        default -> {}
      }
    }
    double move = speeds(e, tables).withBraceletMove;
    double special = e.path("stats").path("special").asDouble(0);
    double stream = streamOfEdge(e) ? 27.6 : 0;
    double classRate = classCritRate(e, tables, move, special) + stream;
    double engraving = 0;
    var adrenalineLegend = new double[] {0, 1.5, 3, 4.5};
    var adrenalineRelic = new double[] {6, 7.5, 9, 10.5, 12};
    var daggerLegend = new double[] {0, .75, 1.5, 2.25};
    var daggerRelic = new double[] {3, 3.75, 4.5, 5.25, 6};
    var stone = Map.of("1", 3.0, "2", 3.75, "3", 5.25, "4", 6.0);
    for (var item : e.path("engraving")) {
      var name = item.path("name").asString("").replaceAll("\\s", "");
      var grade = item.path("grade").asString("");
      int level = item.path("level").asInt();
      if (name.equals("아드레날린")) {
        engraving += 8;
        if (grade.equals("전설")) engraving += at(adrenalineLegend, level);
        else if (grade.equals("유물")) engraving += at(adrenalineRelic, level);
      } else if (name.equals("정밀단도")) {
        engraving += 18;
        if (grade.equals("전설")) engraving += at(daggerLegend, level);
        else if (grade.equals("유물")) engraving += at(daggerRelic, level);
        if (truthy(item.path("stone")))
          engraving += stone.getOrDefault(KrakenScoreEngine.jsString(item.path("stone")), 0.0);
      }
    }
    double total = r + p + f + h + enlightenment + classRate + engraving + 0 + 0;
    return jsRound(100 * total) / 100;
  }

  private static double at(double[] values, int index) {
    return index >= 0 && index < values.length ? values[index] : 0;
  }

  private static boolean streamOfEdge(JsonNode e) {
    var profile = e.path("profile");
    boolean arcana =
        "아르카나".equals(e.path("baseClass").asString(""))
            || "아르카나".equals(profile.path("baseClass").asString(""))
            || "아르카나".equals(profile.path("class").asString(""));
    if (!arcana) return false;
    for (var skill : e.path("skills"))
      if ("스트림 오브 엣지".equals(skill.path("name").asString("")))
        for (var tripod : skill.path("tripods"))
          if ("다크니스 엣지".equals(tripod.path("name").asString(""))) return true;
    return false;
  }

  private static boolean incompleteCombustion(JsonNode e) {
    if (!"소서리스".equals(e.path("profile").path("class").asString(""))) return false;
    for (var core : e.path("arkGrid").path("core"))
      if (core.path("name").asString("").contains("불완전 연소")) return true;
    return false;
  }

  private static double classCritRate(
      JsonNode e, KrakenScoreEngine.Tables tables, double move, double special) {
    if (incompleteCombustion(e)) return 0;
    var secondClass = e.path("profile").path("secondClass").asString("");
    var c = tables.get("W").path(secondClass).path("critRate");
    double m = c.isNumber() ? c.asDouble() : 0;
    if (secondClass.equals("광전사의 비기"))
      return Math.floor(m * (1 + Math.floor(special / .0699 * .26) / 1e4) * 100) / 100;
    if (secondClass.equals("질풍노도"))
      return m + Math.floor(.3 * Math.min(Math.max(move, 0), 40) * 100) / 100;
    return m;
  }

  private static final Pattern RING_CRIT_DMG = Pattern.compile("치명타 피해\\s*\\+(\\d+(\\.\\d+)?)%");

  /** 20084 s: 팔찌 제외 치명타 피해. */
  private static double critDamage(JsonNode e, KrakenScoreEngine.Tables tables) {
    double a = engravingCritDamage(e, tables);
    double c = 0;
    for (var node : e.path("arkPassive").path("enlightenment").path("nodes")) {
      double level = node.path("level").asDouble();
      switch (node.path("name").asString("")) {
        case "분노 자극", "치명적인 체술", "곡예사" -> c += 3 * level;
        case "날카로운 기공", "치명적인 오의", "페일 노트", "격노의 악장" -> c += 4 * level;
        case "오버히트" -> c += (level - 1) * 3;
        case "단련" -> {
          if ("이슬비".equals(e.path("profile").path("secondClass").asString(""))) c += 4 * level;
        }
        case "할버드의 대가" -> c += 4 + 4 * Math.floor(level / 2) - ((level % 2 == 0) ? 1 : 0);
        default -> {}
      }
    }
    double m;
    if (incompleteCombustion(e)) m = 0;
    else {
      var secondClass = e.path("profile").path("secondClass").asString("");
      var l = tables.get("W").path(secondClass).path("critDamage");
      double base = l.isNumber() ? l.asDouble() : 0;
      m =
          secondClass.equals("질풍노도")
              ? base
                  + Math.floor(
                          1.2 * Math.min(Math.max(speeds(e, tables).withBraceletMove, 0), 40) * 100)
                      / 100
              : base;
    }
    double g = ringSum(e, RING_CRIT_DMG);
    double o = 0;
    for (var core : e.path("arkGrid").path("core")) {
      var value = coreTable(tables, "kC", core).path("critDmg");
      if (!value.isMissingNode()) o += value.asDouble();
    }
    double u = 0;
    for (var skill : e.path("skills")) {
      var row = tables.get("ur").path(skill.path("name").asString(""));
      if (!truthy(row.path("critDmg"))) continue;
      for (var tripod : skill.path("tripods")) {
        var value = row.path("critDmg").path(tripod.path("name").asString(""));
        if (present(row.path("critDmg"), tripod.path("name").asString(""))) u += value.asDouble();
      }
    }
    return 200 + a + c + m + g + o + u;
  }

  /** 71552 o: 치명타 피해량 각인합(예리한 둔기 + 스톤, 정밀 단도). */
  private static double engravingCritDamage(JsonNode e, KrakenScoreEngine.Tables tables) {
    if (!truthy(e.path("engraving"))) return 0;
    var bluntStone = Map.of("1", 7.5, "2", 9.4, "3", 13.2, "4", 15.0);
    double u = 0;
    double f = 0;
    for (var item : e.path("engraving")) {
      var raw = item.path("name").asString("");
      var name = raw.replaceAll("\\s", "");
      var grade = grade(item);
      if (grade == null) continue;
      var level = KrakenScoreEngine.jsString(item.path("level"));
      if (name.equals("예리한둔기")) {
        var value = s0Value(tables, "예리한 둔기", name, grade, level, "critDmg");
        double stone = 0;
        if (truthy(item.path("stone"))) {
          var add = bluntStone.get(KrakenScoreEngine.jsString(item.path("stone")));
          if (add != null) stone = add;
        }
        u += (value != null ? value : 0) + stone;
      } else if (name.equals("정밀단도")) {
        var value = s0Value(tables, "정밀 단도", name, grade, level, "critDmg");
        f += value != null ? value : 0;
      }
    }
    return u + f;
  }

  /** 17468 W: 팔찌 제외 치명타 주피(회심 × 코어). */
  private static double critFinalDamage(JsonNode e, KrakenScoreEngine.Tables tables) {
    boolean heart = false;
    for (var node : e.path("arkPassive").path("evolution").path("nodes"))
      if ("회심".equals(node.path("name").asString(""))) heart = true;
    double l = 1;
    var cores = e.path("arkGrid").path("core");
    if (truthy(cores))
      for (var core : cores) {
        var value = coreTable(tables, "Uk", core).path("critFinalDmg");
        l *= value.isMissingNode() || value.isNull() ? 1 : value.asDouble();
      }
    return (heart ? 1.12 : 1) * l;
  }

  // ---------------------------------------------------------------- 속도 비(78512)

  private static double speedRatio(
      JsonNode e, KrakenScoreEngine.Tables tables, List<JsonNode> entries) {
    double r = sum(entries, "atkSpeed");
    if (r == 0) return 1;
    double n = speeds(e, tables).withoutBraceletAttack;
    double l = Math.max(0, Math.min(n + r, 140) - n);
    return l == 0 ? 1 : 1 + l / r * (1 + r * SPEED_UNIT - 1);
  }

  private record Speeds(double withoutBraceletAttack, double withBraceletMove) {}

  private static Speeds speeds(JsonNode e, KrakenScoreEngine.Tables tables) {
    double haste = e.path("stats").path("haste").asDouble(0);
    var bangleStats = optionStats(e.path("armory").path("accessory").path("bangle"));
    double p = bangleStats[2];
    double k = e.path("stats").path("special").asDouble(0);
    double specialWith = e.path("stats").path("special").asDouble(0) + bangleStats[1];
    var secondClass = e.path("profile").path("secondClass").asString("");
    double atkClass = classSpeed(tables, secondClass, "atkSpeed", k);
    double moveClassWith = classSpeed(tables, secondClass, "moveSpeed", specialWith);
    double enlightenmentMove = 0;
    double enlightenmentAtk = 0;
    for (var node : e.path("arkPassive").path("enlightenment").path("nodes"))
      switch (node.path("name").asString("")) {
        case "고대의 바람" -> enlightenmentMove += 10;
        case "징벌의 서막" -> enlightenmentAtk += 5;
        case "고속 진입" -> {
          if (node.path("level").asDouble() >= 1) enlightenmentAtk += 10;
        }
        default -> {}
      }
    double evolutionAtk = 0;
    for (var node : e.path("arkPassive").path("evolution").path("nodes"))
      if ("파괴 전차".equals(node.path("name").asString("")))
        evolutionAtk += 4 * node.path("level").asDouble();
    double stoneMove = 0;
    double stoneAtk = 0;
    for (var option : strings(e.path("armory").path("abilityStone").path("option"))) {
      var m = MOVE_DOWN.matcher(option);
      if (m.find()) stoneMove += 2 * Double.parseDouble(m.group(1));
      var a = ATTACK_DOWN.matcher(option);
      if (a.find()) stoneAtk += 2 * Double.parseDouble(a.group(1));
    }
    double coreMove = 0;
    double coreAtk = 0;
    for (var core : e.path("arkGrid").path("core")) {
      var row = tables.get("HZ").path(core.path("name").asString(""));
      if (!truthy(row)) continue;
      Double best = null;
      var keys = new ArrayList<Double>();
      for (var entry : row.properties()) {
        try {
          keys.add(Double.parseDouble(entry.getKey()));
        } catch (NumberFormatException ignored) {
          // 숫자가 아닌 키는 원본도 건너뛴다.
        }
      }
      keys.sort(Double::compare);
      for (var key : keys) if (key <= core.path("point").asDouble()) best = key;
      if (best == null) continue;
      var m = row.path(KrakenScoreEngine.jsNumber(best));
      var gradeRow = m.path("고대".equals(core.path("grade").asString("")) ? "고대" : "유물");
      var g = gradeRow.isMissingNode() || gradeRow.isNull() ? m : gradeRow;
      coreMove += g.path("moveSpeed").asDouble(0);
      coreAtk += g.path("atkSpeed").asDouble(0);
    }
    double skillMove = 0;
    double skillAtk = 0;
    for (var skill : e.path("skills")) {
      var row = tables.get("i_").path(skill.path("name").asString(""));
      if (!truthy(row)) continue;
      double move = 0;
      double atk = 0;
      for (var tripod : skill.path("tripods")) {
        var name = tripod.path("name").asString("");
        move += row.path("moveSpeed").path(name).asDouble(0);
        atk += row.path("atkSpeed").path(name).asDouble(0);
      }
      skillMove += move;
      skillAtk += atk;
    }
    double engravingAtk = 0;
    for (var item : e.path("engraving")) {
      var raw = item.path("name").asString("");
      var grade = grade(item);
      if (grade == null) continue;
      var level = KrakenScoreEngine.jsString(item.path("level"));
      var value = s0Value(tables, raw, raw.replaceAll("\\s", ""), grade, level, "atkSpeed");
      engravingAtk += value != null ? value : 0;
    }
    double bangleMove = 0;
    var bangle = e.path("armory").path("accessory").path("bangle");
    if (truthy(bangle.path("option")) && truthy(bangle.path("tier"))) {
      var row = tables.get("we").path(KrakenScoreEngine.jsString(bangle.path("tier")));
      if (truthy(row))
        for (var option : strings(bangle.path("option")))
          bangleMove += row.path(option).path("moveSpeed").asDouble(0);
    }
    double attackWithout =
        14
            + speedStat(haste)
            + atkClass
            + skillAtk
            + enlightenmentAtk
            + evolutionAtk
            + engravingAtk
            + coreAtk
            - stoneAtk
            + 0;
    double moveWith =
        14
            + speedStat(haste + p)
            + moveClassWith
            + skillMove
            + enlightenmentMove
            + bangleMove
            + coreMove
            - stoneMove
            + 0;
    return new Speeds(Math.floor(100 * attackWithout) / 100, Math.floor(100 * moveWith) / 100);
  }

  private static final Pattern MOVE_DOWN = Pattern.compile("이동속도 감소\\s*Lv\\.(\\d+)");
  private static final Pattern ATTACK_DOWN = Pattern.compile("공격속도 감소\\s*Lv\\.(\\d+)");

  private static double speedStat(double e) {
    return Math.floor(e / .5821) / 100;
  }

  private static double classSpeed(
      KrakenScoreEngine.Tables tables, String secondClass, String key, double special) {
    var l = tables.get("W").path(secondClass).path(key);
    double n = l.isNumber() ? l.asDouble() : 0;
    return secondClass.equals("광전사의 비기")
        ? Math.floor(n * (1 + Math.floor(special / .0699 * .26) / 1e4) * 100) / 100
        : n;
  }

  // ---------------------------------------------------------------- 진피 비(35231)

  private static double finalDamage(JsonNode e, KrakenScoreEngine.Tables tables, boolean devil) {
    var bangle = e.path("armory").path("accessory").path("bangle");
    if (!truthy(bangle.path("option")) || !truthy(bangle.path("tier"))) return 1;
    var row = tables.get("we").path(KrakenScoreEngine.jsString(bangle.path("tier")));
    if (!truthy(row)) return 1;
    double finalDmg = 1;
    double devilDmg = 1;
    for (var option : strings(bangle.path("option"))) {
      var i = row.path(option.replaceAll("\\s", ""));
      if (!truthy(i)) continue;
      if (truthy(i.path("devilDmg"))) devilDmg *= i.path("devilDmg").asDouble();
      if (truthy(i.path("finalDmg"))
          && !i.has("critRate")
          && !i.has("critRateBuff")
          && !i.has("critDmg")
          && !i.has("critFinalDmg")
          && !i.has("addDmg")
          && !i.has("devilDmg")) {
        double t =
            truthy(i.path("cdr"))
                ? i.path("finalDmg").asDouble() / (1 + .7 * i.path("cdr").asDouble())
                : i.path("finalDmg").asDouble();
        finalDmg *= t;
      }
    }
    return devil ? finalDmg * devilDmg : finalDmg;
  }

  // ---------------------------------------------------------------- 대체 비(12921 x)

  private static final Pattern MAIN_STAT = Pattern.compile("(?:힘|민첩|지능)\\s*\\+[\\d,]+");
  private static final Pattern SUB_STAT = Pattern.compile("(?:치명|특화|신속)\\s*\\+[\\d,]+");

  /** 피해에 영향이 없는 스탯 줄(체력·제압·인내·숙련). 원본도 배율 1로 지나가며, 실제 원문 33명에서 알아보지 못한 줄은 체력 +N뿐이었다(10-25). */
  private static final Pattern ZERO_EFFECT_STAT = Pattern.compile("(?:체력|제압|인내|숙련)\\s*\\+[\\d,]+");

  private static double fallbackRatio(
      JsonNode e,
      KrakenScoreEngine.Tables tables,
      List<String> options,
      List<JsonNode> entries,
      JsonNode tier) {
    var names = new ArrayList<String>();
    for (var item : e.path("engraving")) names.add(item.path("name").asString(""));
    String style =
        names.contains("기습의 대가")
            ? "back"
            : names.contains("결투의 대가") ? "head" : names.contains("타격의 대가") ? "normal" : "all";
    double result = 1;
    for (int index = 0; index < options.size(); index++) {
      var option = options.get(index);
      var o = entries.get(index);
      if (MAIN_STAT.matcher(option).find()
          || SUB_STAT.matcher(option).find()
          || o != null
              && (o.has("weaponAtkPlus")
                  || o.has("addDmg")
                  || o.has("critRate")
                  || o.has("critDmg")
                  || o.has("critFinalDmg")
                  || o.has("atkSpeed")
                  || o.has("moveSpeed")
                  || o.has("finalDmg")
                  || o.has("staggeredTargetDmg"))) continue;
      var c = o;
      if (c != null
          && (c.has("backDmg") || c.has("headDmg") || c.has("normalDmg"))
          && !style.equals("all")
          && (style.equals("back")
              ? !c.has("backDmg")
              : style.equals("head") ? !c.has("headDmg") : !c.has("normalDmg"))) continue;
      if (c != null && c.has("backDmg")) {
        result *= c.get("backDmg").asDouble();
        continue;
      }
      if (c != null && c.has("headDmg")) {
        result *= c.get("headDmg").asDouble();
        continue;
      }
      if (c != null && c.has("normalDmg")) {
        result *= c.get("normalDmg").asDouble();
        continue;
      }
      var s = entry(tables, tier, option);
      if (s != null && s.has("finalDmg")) {
        result *= s.get("finalDmg").asDouble();
        continue;
      }
      var addon = tables.get("braceletAddonAttack").path(option);
      double f = truthy(addon) ? addon.asDouble() / 1e4 + 1 : 1;
      if (f != 1) result *= f;
    }
    return result;
  }

  // ---------------------------------------------------------------- 공통

  private static final Pattern STAT_ANY = Pattern.compile("(?:힘|민첩|지능)\\s*\\+[\\d,]+");
  private static final Pattern PLUS_NUMBER = Pattern.compile("\\+([\\d,]+)");

  private static JsonNode bangle(JsonNode e) {
    var b = e.path("armory").path("accessory").path("bangle");
    return b.isMissingNode() || b.isNull() ? null : b;
  }

  /**
   * 팔찌 옵션 가운데 총배율 계산식이 알아보지 못하는 줄(주 스탯·치명·특화·신속 줄도 아니고 옵션 표에도 없음). 이런 줄은 원본처럼 배율 1로 지나가므로, 효율을 내기
   * 전에 거른다(코드 리뷰 10-25).
   */
  public static List<String> unparsedOptions(JsonNode e, KrakenScoreEngine.Tables tables) {
    var bangle = bangle(e);
    if (bangle == null) return List.of();
    var tier = bangle.path("tier");
    var unparsed = new ArrayList<String>();
    for (var option : strings(bangle.path("option")))
      if (!MAIN_STAT.matcher(option).find()
          && !SUB_STAT.matcher(option).find()
          && !ZERO_EFFECT_STAT.matcher(option).find()
          && entry(tables, tier, option) == null) unparsed.add(option);
    return List.copyOf(unparsed);
  }

  /** 원본 y(tier, option): we[티어][옵션] 또는 공백을 뺀 옵션. 없으면 null. */
  static JsonNode entry(KrakenScoreEngine.Tables tables, JsonNode tier, String option) {
    if (!truthy(tier)) return null;
    var row = tables.get("we").path(KrakenScoreEngine.jsString(tier));
    if (!truthy(row)) return null;
    var direct = row.path(option);
    if (!direct.isMissingNode() && !direct.isNull()) return direct;
    var compact = row.path(option.replaceAll("\\s", ""));
    return compact.isMissingNode() || compact.isNull() ? null : compact;
  }

  /** 원본 h: 힘민지 줄이거나 무기 공격력 옵션. */
  private static boolean isAttackOption(
      KrakenScoreEngine.Tables tables, JsonNode tier, String option) {
    if (MAIN_STAT.matcher(option).find()) return true;
    var entry = entry(tables, tier, option);
    return entry != null && entry.has("weaponAtkPlus");
  }

  /** 원본 _: 팔찌 옵션을 거른 입력(남는 옵션이 없으면 팔찌 null). keep이면 h인 옵션만, 아니면 h가 아닌 옵션만. */
  private static JsonNode withBangleOptions(
      JsonNode e, KrakenScoreEngine.Tables tables, boolean keep) {
    var bangle = bangle(e);
    if (bangle == null || !truthy(bangle.path("option"))) return e;
    var tier = bangle.path("tier");
    var copy = (ObjectNode) e.deepCopy();
    var accessory = (ObjectNode) copy.path("armory").path("accessory");
    var kept = MAPPER.createArrayNode();
    for (var option : strings(bangle.path("option")))
      if (isAttackOption(tables, tier, option) == keep) kept.add(option);
    if (kept.isEmpty()) accessory.putNull("bangle");
    else ((ObjectNode) accessory.path("bangle")).set("option", kept);
    return copy;
  }

  private static double[] weaponOptions(JsonNode e) {
    double flat = 0;
    double percent = 0;
    for (var entry : e.path("armory").path("accessory").properties()) {
      if (entry.getKey().equals("bangle")) continue;
      for (var line : strings(entry.getValue().path("option"))) {
        if (!line.contains("무기 공격력")) continue;
        if (line.contains("%")) {
          var m = WEAPON_PERCENT.matcher(line);
          if (m.find()) percent += Double.parseDouble(m.group(1));
          continue;
        }
        var m = WEAPON_FLAT.matcher(line);
        if (m.find()) flat += Double.parseDouble(m.group(1));
      }
    }
    return new double[] {flat, percent};
  }

  private static double[] attackOptions(JsonNode e) {
    double flat = 0;
    double percent = 0;
    for (var entry : e.path("armory").path("accessory").properties()) {
      if (entry.getKey().equals("bangle")) continue;
      for (var line : strings(entry.getValue().path("option"))) {
        if (!line.contains("공격력") || line.contains("무기 공격력")) continue;
        if (line.contains("%")) {
          var m = ATK_PERCENT.matcher(line);
          if (m.find()) percent += Double.parseDouble(m.group(1));
          continue;
        }
        var m = ATK_FLAT.matcher(line);
        if (m.find()) flat += Double.parseDouble(m.group(1));
      }
    }
    return new double[] {flat, percent};
  }

  private static final Pattern WEAPON_PERCENT = Pattern.compile("무기 공격력\\s*\\+(\\d+(\\.\\d+)?)%");
  private static final Pattern WEAPON_FLAT = Pattern.compile("무기 공격력\\s*\\+(\\d+(\\.\\d+)?)");
  private static final Pattern ATK_PERCENT = Pattern.compile("공격력\\s*\\+(\\d+(\\.\\d+)?)%");
  private static final Pattern ATK_FLAT = Pattern.compile("공격력\\s*\\+(\\d+(\\.\\d+)?)");

  private static double ringSum(JsonNode e, Pattern pattern) {
    double sum = 0;
    for (var slot : List.of("ring1", "ring2")) {
      double ring = 0;
      for (var line : strings(e.path("armory").path("accessory").path(slot).path("option"))) {
        var m = pattern.matcher(line);
        if (m.find()) ring += Double.parseDouble(m.group(1));
      }
      sum += ring;
    }
    return sum;
  }

  private static JsonNode coreTable(KrakenScoreEngine.Tables tables, String table, JsonNode core) {
    var grade = "고대".equals(core.path("grade").asString("")) ? "고대" : "유물";
    return tables
        .get(table)
        .path(core.path("name").asString(""))
        .path(KrakenScoreEngine.jsString(core.path("point")))
        .path(grade);
  }

  private static String grade(JsonNode item) {
    var grade = item.path("grade").asString("");
    return List.of("영웅", "전설", "유물", "고대").contains(grade) ? grade : null;
  }

  /** s0[원래 이름 또는 공백 뺀 이름][등급][레벨][키], 숫자가 아니면 null. */
  private static Double s0Value(
      KrakenScoreEngine.Tables tables,
      String raw,
      String compact,
      String grade,
      String level,
      String key) {
    var a = tables.get("s0").path(raw).path(grade).path(level).path(key);
    var b = tables.get("s0").path(compact).path(grade).path(level).path(key);
    var d = !a.isMissingNode() && !a.isNull() ? a : b;
    return d.isNumber() ? d.asDouble() : null;
  }

  private static Double stone(Double value, JsonNode stone, Map<String, Double> table) {
    if (value == null) return null;
    if (!truthy(stone)) return value;
    return value + table.getOrDefault(KrakenScoreEngine.jsString(stone), 0.0);
  }

  private static boolean present(JsonNode node, String key) {
    return node.has(key) && !node.get(key).isNull();
  }

  private static double sum(List<JsonNode> entries, String key) {
    double s = 0;
    for (var entry : entries)
      s += entry != null && present(entry, key) ? entry.get(key).asDouble() : 0;
    return s;
  }

  private static double product(List<JsonNode> entries, String key) {
    double p = 1;
    for (var entry : entries)
      p *= entry != null && present(entry, key) ? entry.get(key).asDouble() : 1;
    return p;
  }

  private static List<String> strings(JsonNode array) {
    var list = new ArrayList<String>();
    for (var item : array) list.add(item.asString(""));
    return list;
  }
}
