package com.loakraken.character.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 크라켄 점수 엔진(#539, 비교 기준 B의 점수식). 원본 식을 연산 순서·내림·반올림 위치까지 그대로 옮긴다(double). 입력은 비교 기준 B의 현재 상태 입력과 같은
 * 구조의 JSON(우리 해석이 같은 구조로 만든다), 표는 {@link Tables}.
 *
 * <p>이 단계는 딜러 요소 11개·공격력 중간값·딜 지분 프리셋까지다. 점수의 마지막 곱(팔찌 총배율)은 다음 단계다.
 */
public final class KrakenScoreEngine {
  private KrakenScoreEngine() {}

  /** 원본 표(엔진이 읽는 것만, 키 이름은 원본 그대로). */
  public record Tables(String savedAt, JsonNode tables) {
    public Tables {
      Objects.requireNonNull(tables, "tables");
      for (var key :
          List.of(
              "RK", "P_", "s0", "af", "MU", "Um", "j1", "WR", "xV", "we", "Uk", "kC", "o9", "ur", "i_", "HZ",
              "W", "braceletAddonAttack"))
        if (tables.path(key).isMissingNode())
          throw new IllegalArgumentException("크라켄 점수 표가 없습니다: " + key);
    }

    JsonNode get(String key) {
      return tables.path(key);
    }
  }

  /** 공격력 중간값(원본 이름: 기본_공격력·공격력·기본_공격력_전투력·공격력_전투력·공격력_팔찌제외). */
  public record Attack(
      double base, double attack, double baseCombat, double attackCombat, double attackNoBracelet) {}

  /** 딜 지분 프리셋. value는 스킬 이름 → 지분(원본 순서). */
  public record Preset(String tag, Map<String, Double> value, boolean fallback) {}

  /** 딜러 요소 11개(원본 이름). */
  public record Factors(
      double attack,
      double evolution,
      double enlightenment,
      double leap,
      double additionalDamage,
      double enemyDamage,
      double gemDamage,
      double gemCooldown,
      double stats,
      double esther,
      double orb) {}

  // ---------------------------------------------------------------- 공격력(원본 모듈 65224)

  public static Attack attack(JsonNode e, Tables tables) {
    var vambrace = vambrace(e);
    var bonus = attackBonus(e, tables, vambrace);
    double m =
        Math.floor(
            (pow(stat(e, false, true) * weapon(e, tables, true) / 6, 0.5) + vambrace.baseAtkFlat)
                * bonus.baseAtkBonus);
    double g = (m + 0 + bonus.atkPlus) * bonus.atkPer;
    double o =
        (pow(stat(e, true, true) * weapon(e, tables, true) / 6, 0.5) + vambrace.baseAtkFlat)
            * bonus.baseAtkBonus;
    double u = (o + 0 + bonus.atkPlus) * bonus.atkPer;
    double noBracelet =
        jsRound(
            (Math.floor(
                        (pow(stat(e, false, false) * weapon(e, tables, false) / 6, 0.5)
                                + vambrace.baseAtkFlat)
                            * bonus.baseAtkBonus)
                    + 0
                    + bonus.atkPlus)
                * bonus.atkPer);
    return new Attack(m, jsRound(g), o, jsRound(u), noBracelet);
  }

  private record Vambrace(double weaponAtkFlat, double baseAtkFlat, double baseAtkPercent) {}

  private record Bonus(double baseAtkBonus, double atkPlus, double atkPer) {}

  /** 모듈 45748: 완갑의 무기 공격력·기본 공격력 보너스. */
  private static Vambrace vambrace(JsonNode e) {
    var t = e.path("armory").path("equipment").path("vambrace");
    if (!t.isObject()) return new Vambrace(0, 0, 0);
    return new Vambrace(
        t.path("weaponAtkFlat").asDouble(), t.path("baseAtkFlat").asDouble(), t.path("baseAtkPercent").asDouble());
  }

  private static final Pattern ATK_PERCENT = Pattern.compile("공격력\\s*\\+(\\d+(\\.\\d+)?)%");
  private static final Pattern ATK_FLAT = Pattern.compile("공격력\\s*\\+(\\d+(\\.\\d+)?)");
  private static final Pattern WEAPON_ATK_PERCENT = Pattern.compile("무기 공격력\\s*\\+(\\d+(\\.\\d+)?)%");
  private static final Pattern WEAPON_ATK_FLAT = Pattern.compile("무기 공격력\\s*\\+(\\d+(\\.\\d+)?)");

  private static Bonus attackBonus(JsonNode e, Tables tables, Vambrace t) {
    // 장신구(팔찌 제외)의 '공격력 +' 줄(무기 공격력 제외)
    double cFlat = 0;
    double cPercent = 0;
    var accessory = e.path("armory").path("accessory");
    for (var entry : accessory.properties()) {
      if (entry.getKey().equals("bangle")) continue;
      for (var line : entry.getValue().path("option")) {
        var text = line.asString("");
        if (!text.contains("공격력") || text.contains("무기 공격력")) continue;
        if (text.contains("%")) {
          var match = ATK_PERCENT.matcher(text);
          if (match.find()) cPercent += Double.parseDouble(match.group(1));
          continue;
        }
        var match = ATK_FLAT.matcher(text);
        if (match.find()) cFlat += Double.parseDouble(match.group(1));
      }
    }
    // 아크 그리드 젬 '공격력' 효과
    double gemAtk = 0;
    for (var effect : e.path("arkGrid").path("gemEffects"))
      if ("공격력".equals(effect.path("name").asString(""))) {
        gemAtk = effect.path("effect").isNumber() ? effect.path("effect").asDouble() : 0;
        break;
      }
    // 혼돈의 별 코어 : 공격
    double gFlat = 0;
    double gPercent = 0;
    var core = findCore(e, "혼돈의 별 코어 : 공격");
    if (core != null && core.path("point").asDouble() != 0) {
      var row = coreRow(tables, core);
      if (row != null) {
        gFlat = row.path("atkPlus").asDouble(0);
        gPercent = row.path("atkPer").asDouble(0);
      }
    }
    double stone = e.path("armory").path("abilityStone").path("attackbonus").asDouble(0);
    double u = (e.path("gem").path("attackBonus").asDouble(0) + stone + t.baseAtkPercent) / 100 + 1;
    return new Bonus(u, cFlat + gFlat, (cPercent + gPercent + gemAtk) / 100 + 1);
  }

  /** 원본 d(t)·팔찌 제외 경로: 힘민지 합 × (1 + 아바타 % + 펫 %). combat이면 전투력 경로(powerIndex_combat·펫 스탯). */
  private static double stat(JsonNode e, boolean combat, boolean withBracelet) {
    double equipment = 0;
    for (var entry : e.path("armory").path("equipment").properties())
      if (!entry.getKey().equals("weapon")) equipment += entry.getValue().path("stat").asDouble(0);
    double accessories = 0;
    for (var entry : e.path("armory").path("accessory").properties())
      if (!entry.getKey().equals("bangle")) accessories += entry.getValue().path("stat").asDouble(0);
    double bracelet = withBracelet ? braceletStats(e) : 0;
    double index = combat ? e.path("stats").path("powerIndex_combat").asDouble() : 0;
    double pet = combat ? e.path("baseEffect").path("petStat").asDouble() : 1;
    double avatar = e.path("avatar").path("avatarStats").asDouble();
    return Math.floor((equipment + accessories + bracelet + index) * (1 + avatar / 100 + pet / 100));
  }

  private static final Pattern BRACELET_STAT = Pattern.compile("(?:힘|민첩|지능)\\s*\\+([\\d,]+)");

  private static double braceletStats(JsonNode e) {
    double sum = 0;
    for (var line : e.path("armory").path("accessory").path("bangle").path("option")) {
      var match = BRACELET_STAT.matcher(line.asString(""));
      while (match.find()) sum += Double.parseDouble(match.group(1).replace(",", ""));
    }
    return sum;
  }

  /** 원본 D(): 무기 공격력 = (무기 + 장신구 무공 + 팔찌 무공 + 코어 무공 + 완갑 무공) × (1 + %·카르마). */
  private static double weapon(JsonNode e, Tables tables, boolean withBracelet) {
    double weapon = e.path("armory").path("equipment").path("weapon").path("stat").asDouble(0);
    double oFlat = 0;
    double oPercent = 0;
    for (var entry : e.path("armory").path("accessory").properties()) {
      if (entry.getKey().equals("bangle")) continue;
      for (var line : entry.getValue().path("option")) {
        var text = line.asString("");
        if (!text.contains("무기 공격력")) continue;
        if (text.contains("%")) {
          var match = WEAPON_ATK_PERCENT.matcher(text);
          if (match.find()) oPercent += Double.parseDouble(match.group(1));
          continue;
        }
        var match = WEAPON_ATK_FLAT.matcher(text);
        if (match.find()) oFlat += Double.parseDouble(match.group(1));
      }
    }
    double karma = e.path("arkPassive").path("enlightenment").path("karmalevel").asDouble(0) * .1;
    double bracelet = withBracelet ? braceletWeapon(e, tables) : 0;
    double sFlat = 0;
    double sPercent = 0;
    var core = findCore(e, "혼돈의 별 코어 : 무기");
    if (core != null && core.path("point").asDouble() != 0) {
      var row = coreRow(tables, core);
      if (row != null) {
        sFlat = row.path("weaponAtkPlus").asDouble(0);
        sPercent = row.path("weaponAtkPer").asDouble(0);
      }
    }
    var t = vambrace(e);
    return Math.floor(
        (weapon + oFlat + bracelet + sFlat + t.weaponAtkFlat)
            * (1 + oPercent / 100 + karma / 100 + sPercent / 100));
  }

  /** 모듈 92194: 팔찌 옵션의 무기 공격력(표 we[티어][옵션].weaponAtkOrigin). */
  private static double braceletWeapon(JsonNode e, Tables tables) {
    var bangle = e.path("armory").path("accessory").path("bangle");
    if (!bangle.path("option").isArray() || !truthy(bangle.path("tier"))) return 0;
    var row = tables.get("we").path(bangle.path("tier").asString());
    if (!row.isObject()) return 0;
    double sum = 0;
    for (var line : bangle.path("option"))
      sum += row.path(line.asString("")).path("weaponAtkOrigin").asDouble(0);
    return sum;
  }

  // ---------------------------------------------------------------- 요소(원본 모듈 70015)

  public static Factors factors(JsonNode e, Tables tables) {
    return new Factors(
        attack(e, tables).attackNoBracelet(),
        evolution(e),
        enlightenment(e),
        leap(e),
        additionalDamage(e),
        enemyDamage(e, tables),
        gems(e, tables)[0],
        gems(e, tables)[1],
        (e.path("stats").path("critical").asDouble()
                    + e.path("stats").path("haste").asDouble()
                    + e.path("stats").path("special").asDouble())
                / 100
                * 2
                / 100
            + 1.3,
        e.path("armory").path("equipment").path("weapon").path("grade").asString("").contains("에스더")
            ? 1.01
            : 1,
        orb(e));
  }

  private static double evolution(JsonNode e) {
    var evolution = e.path("arkPassive").path("evolution");
    if (!truthy(evolution.path("points"))) return 1;
    double n = evolution.path("points").asDouble();
    double l = 1;
    if (n >= 140) l = 1.79;
    else if (n >= 120) l = 1.65;
    else if (n >= 105) l = 1.45;
    else if (n >= 90) l = 1.4;
    else if (n >= 80) l = 1.35;
    else if (n >= 70) l = 1.3;
    else if (n >= 60) l = 1.25;
    else if (n >= 50) l = 1.2;
    else if (n >= 40) l = 1;
    return l + evolution.path("karmaRank").asDouble(0) * .01;
  }

  private static final Pattern NECKLACE_ADD = Pattern.compile("^추가 피해 \\+([\\d.]+)");

  /** 추피: (무기 품질 + 목걸이 첫 '추가 피해' 줄 + 젬 추가 피해 + 혼돈의 해 코어 : 안정적인 공격) ÷ 100 + 1. */
  private static double additionalDamage(JsonNode e) {
    return additionalDamageRaw(e) / 100 + 1;
  }

  private static double additionalDamageRaw(JsonNode e) {
    double quality = 0;
    var q = e.path("armory").path("equipment").path("weapon").path("quality");
    if (truthy(q)) quality = 10 + .002 * Math.pow(q.asDouble(), 2);
    double necklace = 0;
    for (var line : e.path("armory").path("accessory").path("necklace").path("option")) {
      var match = NECKLACE_ADD.matcher(line.asString(""));
      if (match.find()) {
        necklace = Double.parseDouble(match.group(1));
        break;
      }
    }
    double gem = 0;
    for (var effect : e.path("arkGrid").path("gemEffects"))
      if ("추가 피해".equals(effect.path("name").asString(""))) {
        if (effect.path("effect").isNumber()) gem = effect.path("effect").asDouble();
        break;
      }
    double core = 0;
    var stable = findCore(e, "혼돈의 해 코어 : 안정적인 공격");
    if (stable != null) {
      String key = "10";
      for (var step : List.of("10", "14", "17", "18", "19", "20"))
        if (stable.path("point").asDouble() >= Double.parseDouble(step)) key = step;
      boolean ancient = "고대".equals(stable.path("grade").asString(""));
      core = STABLE_ATTACK.get(key)[ancient ? 1 : 0];
    }
    return quality + necklace + gem + core;
  }

  private static final Map<String, double[]> STABLE_ATTACK =
      Map.of(
          "10", new double[] {0, 0},
          "14", new double[] {.7, .7},
          "17", new double[] {2.1, 3.5},
          "18", new double[] {2.33, 3.73},
          "19", new double[] {2.56, 3.96},
          "20", new double[] {2.78, 4.19});

  private static final Map<String, Double> STONE_ADD =
      Map.of("1", 3.0, "2", 3.75, "3", 5.25, "4", 6.0);

  /** 적주피: 장신구 옵션 배수 × 각인 × 코어(+젬 보스 피해) × 직업 코어 묶음. */
  private static double enemyDamage(JsonNode e, Tables tables) {
    double t = 1;
    var accessory = e.path("armory").path("accessory");
    var rk = tables.get("RK");
    for (var slot : List.of("necklace", "ring1", "ring2")) {
      var item = accessory.path(slot);
      if (!truthy(item)) continue;
      double product = 1;
      for (var line : item.path("option")) {
        var factor = rk.path(line.asString(""));
        if (truthy(factor)) product = factor.asDouble() * product;
      }
      t *= product;
    }
    double r = 1;
    var engraving = e.path("engraving");
    if (engraving.isArray()) {
      var excluded = new ArrayList<String>();
      for (var name : tables.get("P_").path(e.path("profile").path("secondClass").asString("")))
        excluded.add(name.asString(""));
      for (var item : engraving) {
        var name = item.path("name").asString("");
        var grade = item.path("grade").asString("");
        if (!List.of("영웅", "전설", "유물", "고대").contains(grade)) continue;
        var c = tables.get("s0").path(name).path(grade).path(jsString(item.path("level"))).path("finalDmg");
        if (excluded.contains(name) || !truthy(c)) continue;
        if (truthy(item.path("stone")))
          r *= (c.asDouble() + STONE_ADD.get(jsString(item.path("stone")))) / 100 + 1;
        else r *= c.asDouble() / 100 + 1;
      }
    }
    double coreFactor = 1;
    var cores = e.path("arkGrid").path("core");
    if (truthy(cores)) {
      double product = 1;
      for (var core : cores) {
        // 원본은 여기서 등급 칸만 본다(af[이름][포인트][등급].finalDmg, 등급 칸이 없을 때 대체 없음).
        var grade = "고대".equals(core.path("grade").asString("")) ? "고대" : "유물";
        var dmg =
            tables
                .get("af")
                .path(core.path("name").asString(""))
                .path(jsString(core.path("point")))
                .path(grade)
                .path("finalDmg");
        if (truthy(dmg)) product *= dmg.asDouble();
      }
      double boss = 0;
      boolean bossFound = false;
      for (var effect : e.path("arkGrid").path("gemEffects"))
        if ("보스 피해".equals(effect.path("name").asString(""))) {
          bossFound = effect.path("effect").isNumber();
          boss = effect.path("effect").asDouble();
          break;
        }
      coreFactor = product * (bossFound && boss != 0 ? boss / 100 + 1 : 1);
    }
    return t * r * coreFactor * classCores(e, tables);
  }

  /** 직업 코어 묶음 배수(표 MU·Um): 질서의 해·달은 group1(둘 다 없으면 14P 이상도 10으로), 질서의 별은 group2. */
  private static double classCores(JsonNode e, Tables tables) {
    var secondClass = e.path("profile").path("secondClass").asString("");
    var role = e.path("profile").path("supportCheck").asBoolean(false) ? "support" : "dealer";
    var l = tables.get("MU").path(secondClass);
    var cores = e.path("arkGrid").path("core");
    if (!(truthy(l) && truthy(e.path("arkGrid")) && cores.isArray())) return 1;
    var allowed = new ArrayList<String>();
    for (var name : l.path("mainCore")) allowed.add(name.asString(""));
    for (var name : l.path("starCore")) allowed.add(name.asString(""));
    var present = new ArrayList<String>();
    for (var core : cores) {
      var name = core.path("name").asString("").trim();
      if (!name.isEmpty() && allowed.contains(name)) present.add(name);
    }
    boolean sun = present.stream().anyMatch(n -> n.startsWith("질서의 해"));
    boolean moon = present.stream().anyMatch(n -> n.startsWith("질서의 달"));
    boolean both = sun && moon;
    double d = 1;
    for (var core : cores) {
      var raw = core.path("name");
      if (!truthy(raw)) continue;
      var name = raw.asString("").trim();
      if (!allowed.contains(name)) continue;
      String group = null;
      var point = core.path("point");
      String key = jsString(point);
      if (name.startsWith("질서의 해") || name.startsWith("질서의 달")) {
        group = "group1";
        if (point.asDouble() >= 14 && !both) key = "10";
      } else if (name.startsWith("질서의 별")) group = "group2";
      if (group != null) {
        var grade = "고대".equals(core.path("grade").asString("")) ? "고대" : "유물";
        var value = tables.get("Um").path(role).path(group).path(grade).path(key);
        if (!value.isMissingNode() && !value.isNull()) d *= value.asDouble();
      }
    }
    return d;
  }

  private static double enlightenment(JsonNode e) {
    var p = e.path("arkPassive").path("enlightenment").path("points");
    if (!truthy(p)) return 1;
    double r = p.asDouble();
    if (r >= 100) return 1.42;
    if (r >= 98) return 1.4;
    if (r >= 96) return 1.37;
    if (r >= 94) return 1.36;
    if (r >= 92) return 1.35;
    if (r >= 90) return 1.34;
    if (r >= 88) return 1.33;
    if (r >= 86) return 1.28;
    if (r >= 84) return 1.27;
    if (r >= 82) return 1.26;
    if (r >= 80) return 1.25;
    if (r >= 78) return 1.18;
    if (r >= 76) return 1.17;
    if (r >= 74) return 1.16;
    if (r >= 72) return 1.15;
    if (r >= 64) return 1.13;
    if (r >= 56) return 1.125;
    if (r >= 48) return 1.12;
    if (r >= 40) return 1.115;
    if (r >= 32) return 1.11;
    if (r >= 24) return 1.1;
    return 1;
  }

  private static double leap(JsonNode e) {
    var p = e.path("arkPassive").path("leap").path("points");
    if (!truthy(p)) return 1;
    double r = p.asDouble();
    if (r >= 70) return 1.15;
    if (r >= 68) return 1.14;
    if (r >= 66) return 1.13;
    if (r >= 64) return 1.12;
    if (r >= 62) return 1.11;
    if (r >= 60) return 1.1;
    if (r >= 50) return 1.05;
    if (r >= 40) return 1.03;
    return 1;
  }

  private static final int[] GEM_DAMAGE = {3, 6, 9, 12, 15, 18, 21, 24, 30, 40};
  private static final int[] GEM_DAMAGE_T4 = {8, 12, 16, 20, 24, 28, 32, 36, 40, 44};
  private static final int[] GEM_COOLDOWN = {2, 4, 6, 8, 10, 12, 14, 16, 18, 20};
  private static final int[] GEM_COOLDOWN_T4 = {6, 8, 10, 12, 14, 16, 18, 20, 22, 24};

  /** 보석 [데미지, 쿨감]: 프리셋 딜 지분 가중 피해 보석 % + 쿨감 보석 가중 평균. */
  private static double[] gems(JsonNode e, Tables tables) {
    var gems = e.path("gem").path("gems");
    var preset = preset(e, tables);
    var excluded = new java.util.HashSet<String>();
    for (var v : tables.get("j1")) excluded.add(v.asString(""));
    var shares = new ArrayList<Map.Entry<String, Double>>();
    for (var entry : preset.value().entrySet())
      if (!excluded.contains(entry.getKey())) shares.add(entry);
    double m = 0;
    for (var share : shares) {
      JsonNode gem = null;
      for (var g : gems)
        if (share.getKey().equals(g.path("skill").asString(null))
            && "damage".equals(g.path("type").asString(""))
            && g.path("valid").asBoolean(false)) {
          gem = g;
          break;
        }
      if (gem == null) continue;
      int level = gem.path("level").asInt();
      m +=
          truthy(gem.path("attackBonus"))
              ? GEM_DAMAGE_T4[level - 1] * share.getValue()
              : GEM_DAMAGE[level - 1] * share.getValue();
    }
    double sum = 0;
    for (var share : shares) sum += share.getValue();
    double penalty = preset.fallback() ? .7 : 1;
    double damage = sum > 0 ? m * (sum > 0 ? 1 / sum : 0) * penalty / 100 + 1 : 1;
    double cooldown = 1 / (1 - .9 * cooldownAverage(gems) / 100) - 1 + 1;
    return new double[] {damage, cooldown};
  }

  private static double cooldownAverage(JsonNode gems) {
    var list = new ArrayList<JsonNode>();
    for (var g : gems)
      if ("cooldown".equals(g.path("type").asString("")) && g.path("valid").asBoolean(false)) list.add(g);
    if (list.isEmpty()) return 0;
    double weights = 0;
    double sum = 0;
    for (var g : list) {
      int level = g.path("level").asInt();
      double n = Math.pow(2, level - 1);
      weights += n;
      sum += truthy(g.path("attackBonus")) ? GEM_COOLDOWN_T4[level - 1] * n : GEM_COOLDOWN[level - 1] * n;
    }
    return sum / weights;
  }

  /** 보주: 이름별 기본·계수, 효과의 25%만, 두 번 내림. */
  private static double orb(JsonNode e) {
    var orb = e.path("armory").path("orb");
    if (!truthy(orb)) return 1;
    var name = orb.path("name").asString("");
    double trinity = orb.path("trinity").asDouble();
    if (name.equals("운율의 파도 보주")) return orbFactor(25, 1e3, trinity);
    if (name.equals("확산하는 안개의 보주")) return orbFactor(30, 1200, trinity);
    if (name.equals("타오르는 태양의 보주")) return orbFactor(18, 720, trinity);
    if (Pattern.compile("영험한 비전의 보주|신비로운 투영의 보주|응축된 자연의 보주|눈부신 비전의 보주").matcher(name).find())
      return orbFactor(20, 800, trinity);
    if (Pattern.compile("신성한 자연의 보주|온화한 투영의 보주|생명의 대지 보주").matcher(name).find())
      return orbFactor(14, 544, trinity);
    return 1;
  }

  private static double orbFactor(double base, double coefficient, double trinity) {
    return quarter(floor6((base + coefficient * trinity / 1e8) / 1e4 + 1));
  }

  private static double floor6(double x) {
    return Math.floor(1e6 * x) / 1e6;
  }

  private static double quarter(double x) {
    return floor6(1 + (x - 1) * .25);
  }

  // ---------------------------------------------------------------- 딜 지분 프리셋(원본 모듈 67405)

  public static Preset preset(JsonNode e, Tables tables) {
    var cores = e.path("arkGrid").path("core");
    var gems = e.path("gem").path("gems");
    var skills = e.path("skills");
    var secondClass = e.path("profile").path("secondClass").asString("");
    boolean support = e.path("profile").path("supportCheck").asBoolean(false);
    // 원본은 코어·보석이 없으면 빈 목록을 넘기므로 늘 표를 훑는다(빈 노드를 돌면 항목 0개).
    {
      for (var preset : tables.get("WR")) {
        boolean hasCores = preset.path("cores").size() > 0;
        boolean coresMatch = true;
        for (var want : preset.path("cores")) {
          var entry = want.properties().iterator().next();
          var wantName = coreName(entry.getKey());
          double min = entry.getValue().asDouble();
          boolean found = false;
          for (var core : cores)
            if (coreName(core.path("name").asString("")).equals(wantName)
                && core.path("point").asDouble() >= min) found = true;
          if (!found) coresMatch = false;
        }
        boolean hasGems = preset.path("gems").size() > 0;
        boolean gemsMatch = true;
        for (var want : preset.path("gems")) {
          var entry = want.properties().iterator().next();
          boolean found = false;
          for (var gem : gems)
            if (entry.getKey().equals(gem.path("skill").asString(null))
                && entry.getValue().asString("").equals(gem.path("type").asString(null))) found = true;
          if (!found) gemsMatch = false;
        }
        boolean matches =
            hasCores && hasGems && coresMatch && gemsMatch
                || hasCores && !hasGems && coresMatch
                || !hasCores && hasGems && gemsMatch;
        var tag = preset.path("tag").asString("");
        boolean tagOk = tag.equals("333 전탄") ? quickReady(skills) : !tag.equals("333 전탄B") || !quickReady(skills);
        if (matches && tagOk) return new Preset(tag, shares(preset.path("value")), false);
      }
    }
    var byClass = tables.get("xV").path(secondClass);
    if (truthy(byClass)) return new Preset(secondClass, shares(byClass), false);
    if (support) return new Preset("서폿", Map.of(), false);
    var value = new LinkedHashMap<String, Double>();
    var damage = new ArrayList<JsonNode>();
    for (var gem : gems)
      if ("damage".equals(gem.path("type").asString(""))
          && gem.path("valid").asBoolean(false)
          && !"없음".equals(gem.path("skill").asString(""))) damage.add(gem);
    if (!damage.isEmpty()) {
      double a = Math.floor(1.0 / damage.size() * 100) / 100;
      for (var gem : damage) value.merge(gem.path("skill").asString(""), a, Double::sum);
    }
    return new Preset("데이터 없음", value, true);
  }

  private static String coreName(String name) {
    int t = name.indexOf("코어 :");
    return (t == -1 ? name : name.substring(t + 4)).trim();
  }

  private static boolean quickReady(JsonNode skills) {
    for (var skill : skills)
      if ("종말의 전조".equals(skill.path("name").asString("")))
        for (var tripod : skill.path("tripods"))
          if ("빠른 준비".equals(tripod.path("name").asString(""))) return true;
    return false;
  }

  /** 프리셋 값: 객체 그대로, 배열이면 항목들을 차례로 합침(원본 순서 유지). */
  private static Map<String, Double> shares(JsonNode value) {
    var result = new LinkedHashMap<String, Double>();
    if (value.isArray())
      for (var item : value) for (var entry : item.properties()) result.put(entry.getKey(), entry.getValue().asDouble());
    else for (var entry : value.properties()) result.put(entry.getKey(), entry.getValue().asDouble());
    return result;
  }

  // ---------------------------------------------------------------- 팔찌 계산이 함께 쓰는 값(같은 원본 식)

  /** 모듈 92194 값(팔찌 옵션 무기 공격력). */
  static double braceletWeaponForBracelet(JsonNode e, Tables tables) {
    return braceletWeapon(e, tables);
  }

  /** 모듈 50080·65224의 코어 보너스 [flat, percent](포인트가 없거나 표에 없으면 0). */
  static double[] coreBonus(JsonNode e, Tables tables, String coreName, String flatKey, String percentKey) {
    var core = findCore(e, coreName);
    if (core == null || core.path("point").asDouble() == 0) return new double[] {0, 0};
    var row = coreRow(tables, core);
    if (row == null) return new double[] {0, 0};
    return new double[] {row.path(flatKey).asDouble(0), row.path(percentKey).asDouble(0)};
  }

  /** 모듈 45748 값 [weaponAtkFlat, baseAtkFlat, baseAtkPercent]. */
  static double[] vambraceValues(JsonNode e) {
    var v = vambrace(e);
    return new double[] {v.weaponAtkFlat, v.baseAtkFlat, v.baseAtkPercent};
  }

  /** 추피 합(÷100 + 1 하기 전): 무기 품질 + 목걸이 + 젬 + 혼돈의 해 코어 : 안정적인 공격. 모듈 28245와 70015가 같은 식이다. */
  static double additionalDamageSum(JsonNode e) {
    return additionalDamageRaw(e);
  }

  // ---------------------------------------------------------------- 공통

  private static JsonNode findCore(JsonNode e, String name) {
    for (var core : e.path("arkGrid").path("core")) if (name.equals(core.path("name").asString(""))) return core;
    return null;
  }

  /** 코어 표 af[이름][포인트][고대|유물](없으면 그 포인트 칸 자체). */
  private static JsonNode coreRow(Tables tables, JsonNode core) {
    var byPoint = tables.get("af").path(core.path("name").asString("")).path(jsString(core.path("point")));
    if (!truthy(byPoint)) return null;
    var row = byPoint.path("고대".equals(core.path("grade").asString("")) ? "고대" : "유물");
    return truthy(row) ? row : byPoint;
  }

  /** JS의 참 같은 값: 없음·null·false·0·빈 문자열이 아님. */
  static boolean truthy(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return false;
    if (node.isBoolean()) return node.asBoolean();
    if (node.isNumber()) return node.asDouble() != 0 && !Double.isNaN(node.asDouble());
    if (node.isString()) return !node.asString().isEmpty();
    return true;
  }

  /** JS ""+값 — 정수 값은 소수점 없이. */
  static String jsString(JsonNode node) {
    if (node.isNumber()) {
      double d = node.asDouble();
      if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
      return Double.toString(d);
    }
    return node.asString("");
  }

  /** JS String(수) — 정수는 소수점 없이. */
  static String jsNumber(double d) {
    if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
    return Double.toString(d);
  }

  /**
   * 딜러 점수(원본 32142 xV = i(l(e), d6(e))): 요소 11개 × (팔찌 총배율 × 1.021) ÷ 1930. 곱하는 순서도 원본 그대로다.
   */
  public static double dealerScore(JsonNode e, Tables tables) {
    var f = factors(e, tables);
    double t = KrakenScoreBracelet.total(e, tables);
    return f.attack()
        * f.evolution()
        * f.enlightenment()
        * f.leap()
        * f.enemyDamage()
        * f.additionalDamage()
        * f.orb()
        * f.gemDamage()
        * f.gemCooldown()
        * f.stats()
        * f.esther()
        * (t * KrakenScoreBracelet.SCORE_CONSTANT)
        / 1930;
  }
  /** JS Math.round(양수 입력): floor(x + 0.5). */
  static double jsRound(double x) {
    return Math.floor(x + 0.5);
  }

  /** JS x ** y. V8과 끝자리를 맞추려고 fdlibm(StrictMath)을 쓴다 — 고정 테스트가 확인한다. */
  static double pow(double x, double y) {
    return StrictMath.pow(x, y);
  }
}
