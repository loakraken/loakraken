package com.loakraken.character.domain;

import static com.loakraken.character.domain.KrakenScoreEngine.truthy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 크라켄 점수 서폿(#539, 원본 모듈 21818 Y와 38564 PR·GT). 서폿 점수 = 총 버프력^4.24 × 질서 코어 × 120. 젬 효율에 쓰는 서폿 점수 함수는
 * GT(총 버프력 × 질서 코어^0.2358…)다 — 점수(PR)와 섞지 않는다.
 */
public final class KrakenScoreSupport {
  private KrakenScoreSupport() {}

  /** PR 결과(원본 이름: allTimeBuffPower·doubleBuffPower·totalBuffPower·supportSpecPoint). */
  public record Buff(double allTime, double doubleBuff, double total, double score) {}

  /** 서폿 요소(원본 Y의 값 중 PR이 읽는 것). */
  public record Factors(
      double attack,
      double haste,
      double specialization,
      double specializationDamage,
      double engravingCooldown,
      double engravingAwakenCooldown,
      double arkPassiveCooldown,
      double coreCooldown,
      double gemCooldownAverage,
      double buffUptime,
      double buffUptimeA,
      double atkBuffA,
      double atkBuffB,
      double damageBuff,
      double damageBuffAwakenOnly,
      double evolutionBuff,
      double stigma,
      double identityAccessory,
      double identityCore,
      double constantBuff,
      double finalDamageBuff,
      double orderCore,
      double enlightenment) {}

  public static double score(JsonNode e, KrakenScoreEngine.Tables tables) {
    return buff(factors(e, tables)).score();
  }

  /** 원본 GT(젬 효율의 서폿 점수 함수). */
  public static double gemScore(JsonNode e, KrakenScoreEngine.Tables tables) {
    var t = factors(e, tables);
    return buff(t).total() * KrakenScoreEngine.pow(t.orderCore(), .23584905660377356);
  }

  /** 원본 38564 l(Y). */
  public static Buff buff(Factors e) {
    double t = e.haste();
    double a = e.buffUptimeA() / 100;
    double i =
        Math.floor(
            .22 * e.attack() * a * (e.atkBuffA() / 100 + 1)
                + .22 * e.attack() * (1 - a) * (e.atkBuffB() / 100 + 1));
    double r =
        (1
                        / ((1 - e.engravingAwakenCooldown()) * t * (1 - e.engravingCooldown()))
                        * (1 - e.coreCooldown())
                    - 1)
                * .15
            + 1;
    double l = e.specialization() / 30.2 / 100 + 1;
    double n =
        t
            * (1 - e.gemCooldownAverage() / 100)
            * (1 - e.engravingCooldown())
            * (1 - e.arkPassiveCooldown())
            * (1 - e.coreCooldown());
    double c =
        (1 - e.arkPassiveCooldown()) * (1 - e.engravingCooldown()) * (1 - e.coreCooldown()) * t;
    double m = Math.min(1, 20.05 * ((e.identityAccessory() + e.identityCore()) * l) * r / n / 100);
    double g = Math.min(1, 24.45 / c / 100);
    double o = (17e4 + i * e.buffUptime()) / 17e4;
    double u = e.damageBuff() / 100 + 1;
    double p = e.finalDamageBuff();
    double f =
        o * (e.stigma() / 100 + 1) * ((1.45 + e.evolutionBuff()) / 1.45) * e.constantBuff() * p;
    double s = 13 * e.enlightenment() * u * e.specializationDamage() / 100 + p;
    double d = (10 * (e.damageBuffAwakenOnly() / 100 + 1) / 100 + 1) * p;
    double doubleBuff = f / p * (s / p) * (d / p) * p;
    double v =
        m * g * doubleBuff
            + m * (1 - g) * (f / p * (s / p) * p)
            + g * (1 - m) * (f / p * (d / p) * p)
            + (1 - m) * (1 - g) * f;
    double score = KrakenScoreEngine.pow(v, 4.24) * e.orderCore() * 120;
    return new Buff(f, doubleBuff, v, score);
  }

  // ---------------------------------------------------------------- 요소(원본 21818 Y)

  private static final List<String> ARMOR =
      List.of("armor", "gloves", "helmet", "pants", "shoulder");
  private static final List<String> JEWELRY =
      List.of("earing1", "earing2", "necklace", "ring1", "ring2");
  private static final Pattern BANGLE_MAIN = Pattern.compile("^(힘|민첩|지능)\\+(\\d+)$");
  private static final Pattern WEAPON_FLAT = Pattern.compile("^무기공격력\\+(\\d+)(?!%)$");
  private static final Pattern WEAPON_PERCENT = Pattern.compile("^무기공격력\\+(\\d+(?:\\.\\d+)?)%$");
  private static final int[] GEM_CD = {2, 4, 6, 8, 10, 12, 14, 16, 18, 20};
  private static final int[] GEM_CD_T4 = {6, 8, 10, 12, 14, 16, 18, 20, 22, 24};

  public static Factors factors(JsonNode e, KrakenScoreEngine.Tables tables) {
    double attack = Math.floor(attack(e, tables));
    // 특성
    var bangleStats = bangleStats(e.path("armory").path("accessory").path("bangle"));
    double r = e.path("stats").path("haste").asDouble(0) + bangleStats[2];
    double n = Math.min(e.path("stats").path("special").asDouble(0) + bangleStats[1], 1e4);
    double special = (r + n) * .25;
    double hasteStat = (r + n) * .75;
    double specialDamage = special / 20.791 / 100 + 1;
    // 쿨감
    double haste = Math.max(Math.ulp(1.0), Math.min(1, 1 - .0214739 * hasteStat / 100));
    double cooldown = 0;
    double awaken = 0;
    for (var item : e.path("engraving")) {
      var name = item.path("name").asString("");
      var grade = item.path("grade").asString("");
      var level = KrakenScoreEngine.jsString(item.path("level"));
      if (name.equals("마나의 흐름"))
        cooldown =
            tables.get("s0").path(name).path(grade).path(level).path("cdrPercent").asDouble(0);
      else if (name.equals("각성")) {
        double stone =
            truthy(item.path("stone"))
                ? Map.of("1", .06, "2", .075, "3", .105, "4", .12)
                    .getOrDefault(KrakenScoreEngine.jsString(item.path("stone")), 0.0)
                : 0;
        awaken =
            tables.get("s0").path(name).path(grade).path(level).path("awakenCdrPercent").asDouble(0)
                + stone;
      }
    }
    boolean pioneer = false;
    for (var node : e.path("arkPassive").path("evolution").path("nodes"))
      if ("선각자".equals(node.path("name").asString(""))) pioneer = true;
    double arkPassive = .05 * (pioneer ? 1 : 0);
    double core = 0;
    var flowing = core(e, "혼돈의 해 코어 : 흐르는 마나");
    if (flowing != null) {
      int key = 10;
      for (int step : new int[] {10, 14, 17, 18, 19, 20})
        if (flowing.path("point").asDouble() >= step) key = step;
      boolean ancient = "고대".equals(flowing.path("grade").asString(""));
      core = FLOWING.get(key)[ancient ? 1 : 0];
    }
    double gemA = 0;
    double gemB = 0;
    var cooldownGems = new ArrayList<JsonNode>();
    for (var gem : e.path("gem").path("gems"))
      if ("cooldown".equals(gem.path("type").asString("")) && gem.path("valid").asBoolean(false))
        cooldownGems.add(gem);
    if (e.path("gem").path("gems").isArray()) {
      var a = first(cooldownGems, List.of("천상의 연주", "신의 분노", "묵법 : 해그리기", "숭고한 맹세"));
      var b = first(cooldownGems, List.of("음파 진동", "천상의 축복", "묵법 : 해우물", "숭고한 도약"));
      if (a != null) gemA = gemCooldown(a);
      if (b != null) gemB = gemCooldown(b);
    }
    var excluded = List.of("수호의 연주", "신성한 보호", "빛의 광시곡", "구원의 은총", "구원의 터");
    double average = 0;
    var others = new ArrayList<JsonNode>();
    for (var gem : e.path("gem").path("gems"))
      if ("cooldown".equals(gem.path("type").asString(""))
          && !excluded.contains(gem.path("skill").asString(""))
          && gem.path("valid").asBoolean(false)) others.add(gem);
    if (!others.isEmpty()) {
      double sum = 0;
      for (var gem : others) sum += gemCooldown(gem);
      average = sum / others.size();
    }
    // 공증 가동률
    double durationA = 8;
    double durationB = 0;
    double cdA = 0;
    double cdB = 0;
    var cls = e.path("profile").path("class").asString("");
    switch (cls) {
      case "도화가" -> {
        cdA = 27;
        cdB = 30;
        if (hasSkill(e, "묵법 : 해우물") && hasTripod(e, "빠른 준비")) cdB = 24;
        durationB = 6;
      }
      case "홀리나이트" -> {
        cdA = 27;
        cdB = 35;
        durationB = 8;
      }
      case "바드" -> {
        cdA = 30;
        if (hasSkill(e, "천상의 연주") && hasTripod(e, "빠른 준비")) cdA = 24;
        cdB = 24;
        durationB = 5;
      }
      case "발키리" -> {
        cdA = 27;
        cdB = 36;
        durationB = 8;
      }
      default -> {}
    }
    double ea = cdA * haste * (1 - cooldown) * (1 - arkPassive) * (1 - core) * (1 - gemA / 100);
    double eb = cdB * haste * (1 - cooldown) * (1 - arkPassive) * (1 - core) * (1 - gemB / 100);
    double uptime = (durationA + durationB) / Math.max(durationA + durationB, Math.max(ea, eb));
    double uptimeA = Math.min(durationA / ea * 100, 100);
    // 아공강
    double ringsAtk = ringSum(e, "ring1", RING_ATK) + ringSum(e, "ring2", RING_ATK);
    double bangleAtk = bangleSum(e, tables, "atkBuff");
    double evolutionAtk = 0;
    for (var node : e.path("arkPassive").path("evolution").path("nodes")) {
      var name = node.path("name").asString("");
      if (name.matches(".*(선각자|기원).*")) evolutionAtk += 22;
      else if (name.contains("진군")) evolutionAtk += 21;
    }
    double gemAtk = gemEffect(e, "아군 공격 강화");
    double atkA =
        ringSum(e, "ring1", RING_ATK)
            + ringSum(e, "ring2", RING_ATK)
            + bangleAtk
            + evolutionAtk
            + maxDamageGem(e, List.of("신의 분노", "천상의 연주", "묵법 : 해그리기", "숭고한 맹세"))
            + gemAtk;
    double atkB =
        ringSum(e, "ring1", RING_ATK)
            + ringSum(e, "ring2", RING_ATK)
            + bangleAtk
            + evolutionAtk
            + maxDamageGem(e, List.of("천상의 축복", "음파 진동", "묵법 : 해우물", "숭고한 도약"))
            + gemAtk;
    // 아피강
    double rd = ringSum(e, "ring1", RING_DMG);
    double ld = ringSum(e, "ring2", RING_DMG);
    double bangleDmg = bangleSum(e, tables, "damageBuff");
    double gemDmg = gemEffect(e, "아군 피해 강화");
    double coreDmg = 0;
    var faith = core(e, "혼돈의 해 코어 : 신념의 강화");
    if (faith != null) coreDmg = afValue(tables, faith, "damageBuff", 0);
    double damage =
        rd
            + ld
            + bangleDmg
            + maxDamageGem(e, List.of("신앙 스킬", "세레나데 스킬", "음양 스킬"))
            + gemDmg
            + coreDmg;
    double damageAwaken = rd + ld + bangleDmg + gemDmg + coreDmg;
    // 진화 버프
    double evolutionBuff = 0;
    for (var node : e.path("arkPassive").path("evolution").path("nodes"))
      if (node.path("name").asString("").contains("정열의 춤사위"))
        evolutionBuff += 7 * node.path("level").asDouble();
    evolutionBuff = evolutionBuff / 100;
    // 낙인력
    double necklace = 0;
    for (var line : e.path("armory").path("accessory").path("necklace").path("option")) {
      var m = STIGMA.matcher(line.asString(""));
      if (m.find()) necklace += Double.parseDouble(m.group(1));
    }
    double evolutionStigma = 0;
    for (var node : e.path("arkPassive").path("evolution").path("nodes")) {
      var name = node.path("name").asString("");
      double level = node.path("level").asDouble();
      if (name.contains("기원")) evolutionStigma += 4;
      else if (name.contains("입식 타격가") || name.contains("마나 용광로") || name.contains("안정된 관리자"))
        evolutionStigma += 10 * level;
    }
    double gemStigma = gemEffect(e, "낙인력");
    var karmaRank = e.path("arkPassive").path("evolution").path("karmaRank");
    double karma = truthy(karmaRank) ? karmaRank.asDouble() : 0;
    double enlightenmentStigma = 0;
    var list =
        List.of("빠른 구원", "빛의 흔적", "포용의 세레나데", "낙인의 세레나데", "오누이", "낙인 강화", "해방자의 흔적", "빛의 검기");
    for (var node : e.path("arkPassive").path("enlightenment").path("nodes"))
      if (list.contains(node.path("name").asString("")))
        enlightenmentStigma += node.path("level").asDouble() - 1;
    double coreStigma = 0;
    var mark = core(e, "혼돈의 달 코어 : 낙인의 흔적");
    if (mark != null) {
      var v = afNode(tables, mark).path("stigmaPer");
      coreStigma = truthy(v) ? v.asDouble() : 0;
    }
    double stigma =
        10
            * ((necklace + evolutionStigma + gemStigma + karma + enlightenmentStigma + coreStigma)
                    / 100
                + 1);
    // 아덴
    double identityAccessory = 1;
    for (var line : e.path("armory").path("accessory").path("necklace").path("option")) {
      var text = line.asString("");
      if (!text.contains("세레나데, 신앙, 조화 게이지 획득량")) continue;
      var m = PERCENT.matcher(text);
      identityAccessory = m.find() ? Double.parseDouble(m.group(1)) / 100 + 1 : 1;
      break;
    }
    double identityCore = 0;
    if (e.path("arkGrid").path("core").size() > 0 && faith != null)
      identityCore = afValue(tables, faith, "identityUptime", 0);
    // 뎀증
    double constant = 1;
    var bangle = e.path("armory").path("accessory").path("bangle");
    if (truthy(bangle)
        && truthy(bangle.path("option"))
        && truthy(bangle.path("grade"))
        && truthy(bangle.path("tier"))) {
      var row = tables.get("we").path(KrakenScoreEngine.jsString(bangle.path("tier")));
      for (var line : bangle.path("option")) {
        var v = row.path(line.asString("")).path("atkBuffPlus");
        if (truthy(v)) constant *= v.asDouble();
      }
    }
    double coreBuff = 1;
    for (var c : e.path("arkGrid").path("core")) {
      var name = c.path("name").asString("");
      if (!name.matches(".*(혼돈의 달 코어 : 강철의 흔적|혼돈의 달 코어 : 치명적인 흔적).*")) continue;
      var v = afNode(tables, c).path("atkBuffPlus");
      if (truthy(v)) coreBuff *= v.asDouble();
    }
    constant = constant * coreBuff;
    double finalBuff = 1;
    if (mark != null) {
      var v = afNode(tables, mark).path("finalDamageBuff");
      finalBuff = v.isMissingNode() || v.isNull() ? 1 : v.asDouble();
    }
    double orderCore = orderCore(e, tables);
    double enlightenment = 1;
    var awakenings = List.of("신성 해방", "세레나데 코드", "묵법 : 접무", "해방의 날개");
    for (var node : e.path("arkPassive").path("enlightenment").path("nodes"))
      if (awakenings.contains(node.path("name").asString(""))) {
        int level = node.path("level").asInt();
        double[] values = {1.1, 1.1, 1.2};
        enlightenment = level >= 1 && level <= 3 ? values[level - 1] : 1;
        break;
      }
    return new Factors(
        attack,
        haste,
        special,
        specialDamage,
        cooldown,
        awaken,
        arkPassive,
        core,
        average,
        uptime,
        uptimeA,
        atkA,
        atkB,
        damage,
        damageAwaken,
        evolutionBuff,
        stigma,
        identityAccessory,
        identityCore,
        constant,
        finalBuff,
        orderCore,
        enlightenment);
  }

  private static final Map<Integer, double[]> FLOWING =
      Map.of(
          10, new double[] {0, 0},
          14, new double[] {.004, .004},
          17, new double[] {.012, .02},
          18, new double[] {.0133, .0213},
          19, new double[] {.0146, .0226},
          20, new double[] {.0159, .0239});

  private static final Pattern RING_ATK = Pattern.compile("아군 공격력 강화 효과\\s*\\+(\\d+(\\.\\d+)?)");
  private static final Pattern RING_DMG = Pattern.compile("아군 피해량 강화 효과\\s*\\+(\\d+(\\.\\d+)?)");
  private static final Pattern STIGMA = Pattern.compile("낙인력\\s*\\+(\\d+(\\.\\d+)?)");
  private static final Pattern PERCENT = Pattern.compile("\\+(\\d+(?:\\.\\d+)?)%");

  /** 원본 Y의 n: 서폿 공격력(내림 전). */
  private static double attack(JsonNode e, KrakenScoreEngine.Tables tables) {
    var v = KrakenScoreEngine.vambraceValues(e);
    double bonus =
        (e.path("gem").path("attackBonus").asDouble()
                    + e.path("armory").path("abilityStone").path("attackbonus").asDouble(0)
                    + v[2])
                / 100
            + 1;
    double armor = 0;
    for (var slot : ARMOR) {
      var stat = e.path("armory").path("equipment").path(slot).path("stat");
      if (truthy(stat)) armor += stat.asDouble();
    }
    double jewelry = 0;
    for (var slot : JEWELRY) {
      var stat = e.path("armory").path("accessory").path(slot).path("stat");
      if (truthy(stat)) jewelry += stat.asDouble();
    }
    double bangleMain = 0;
    for (var line : e.path("armory").path("accessory").path("bangle").path("option")) {
      var m = BANGLE_MAIN.matcher(line.asString(""));
      bangleMain += m.find() ? Double.parseDouble(m.group(2)) : 0;
    }
    double avatar = e.path("avatar").path("avatarStats").asDouble() / 100 + 1;
    double vambraceStat =
        e.path("armory").path("equipment").path("vambrace").path("stat").asDouble(0);
    double stats = (armor + jewelry + bangleMain + vambraceStat) * avatar;
    double weapon = e.path("armory").path("equipment").path("weapon").path("stat").asDouble(0);
    double flat = 0;
    double percent = 0;
    for (var slot : JEWELRY) {
      var item = e.path("armory").path("accessory").path(slot);
      if (!truthy(item)) continue;
      double f = 0;
      for (var line : item.path("option")) {
        var m = WEAPON_FLAT.matcher(line.asString("").replaceAll("\\s", ""));
        if (m.find()) f += Double.parseDouble(m.group(1));
      }
      flat += f;
      double p = 0;
      for (var line : item.path("option")) {
        var m = WEAPON_PERCENT.matcher(line.asString("").replaceAll("\\s", ""));
        if (m.find()) p += Double.parseDouble(m.group(1));
      }
      percent += p;
    }
    double karma = e.path("arkPassive").path("enlightenment").path("karmalevel").asDouble(0) * .001;
    double bangleWeapon = 0;
    var bangle = e.path("armory").path("accessory").path("bangle");
    if (truthy(bangle) && truthy(bangle.path("tier")) && truthy(bangle.path("option"))) {
      var row = tables.get("we").path(KrakenScoreEngine.jsString(bangle.path("tier")));
      for (var line : bangle.path("option")) {
        var w = row.path(line.asString("")).path("weaponAtkPlus");
        if (truthy(w)) bangleWeapon += w.asDouble();
      }
    }
    double coreFlat = 0;
    double corePercent = 0;
    var weaponCore = core(e, "혼돈의 별 코어 : 무기");
    if (weaponCore != null) {
      var row = WEAPON_CORE.get(KrakenScoreEngine.jsString(weaponCore.path("point")));
      if (row != null) {
        var grade = "고대".equals(weaponCore.path("grade").asString("")) ? 1 : 0;
        coreFlat = row[grade][0];
        corePercent = row[grade][1];
      }
    }
    double weaponTotal =
        (weapon + flat + bangleWeapon + coreFlat + v[0])
            * (1 + (karma + percent / 100 + corePercent / 100));
    return (KrakenScoreEngine.pow(stats * weaponTotal / 6, .5) + v[1]) * bonus;
  }

  /** 원본 Y 안의 혼돈의 별 코어 : 무기 표 [유물|고대][무공+, 무공%]. */
  private static final Map<String, double[][]> WEAPON_CORE =
      Map.of(
          "10", new double[][] {{1300, 0}, {1300, 0}},
          "14", new double[][] {{1300, .75}, {1300, .75}},
          "17", new double[][] {{3900, 2.25}, {5200, 3}},
          "18", new double[][] {{3900, 2.48}, {5200, 3.23}},
          "19", new double[][] {{3900, 2.71}, {5200, 3.46}},
          "20", new double[][] {{3900, 2.94}, {5200, 3.69}});

  private static double[] bangleStats(JsonNode bangle) {
    double critical = 0;
    double special = 0;
    double haste = 0;
    for (var line : bangle.path("option")) {
      var m = OPTION_STAT.matcher(line.asString(""));
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

  private static final Pattern OPTION_STAT =
      Pattern.compile("(치명|특화|신속|제압|인내|숙련)\\s*\\+([\\d,]+(?:\\.\\d+)?)");

  private static JsonNode core(JsonNode e, String name) {
    for (var core : e.path("arkGrid").path("core"))
      if (name.equals(core.path("name").asString(""))) return core;
    return null;
  }

  private static JsonNode afNode(KrakenScoreEngine.Tables tables, JsonNode core) {
    var grade = "고대".equals(core.path("grade").asString("")) ? "고대" : "유물";
    return tables
        .get("af")
        .path(core.path("name").asString(""))
        .path(KrakenScoreEngine.jsString(core.path("point")))
        .path(grade);
  }

  private static double afValue(
      KrakenScoreEngine.Tables tables, JsonNode core, String key, double fallback) {
    var v = afNode(tables, core).path(key);
    return v.isMissingNode() || v.isNull() ? fallback : v.asDouble();
  }

  private static JsonNode first(List<JsonNode> gems, List<String> skills) {
    for (var gem : gems) if (skills.contains(gem.path("skill").asString(""))) return gem;
    return null;
  }

  private static double gemCooldown(JsonNode gem) {
    int level = gem.path("level").asInt();
    return truthy(gem.path("attackBonus")) ? GEM_CD_T4[level - 1] : GEM_CD[level - 1];
  }

  private static boolean hasSkill(JsonNode e, String name) {
    for (var skill : e.path("skills"))
      if (name.equals(skill.path("name").asString(""))) return true;
    return false;
  }

  private static boolean hasTripod(JsonNode e, String name) {
    for (var skill : e.path("skills"))
      for (var tripod : skill.path("tripods"))
        if (name.equals(tripod.path("name").asString(""))) return true;
    return false;
  }

  private static double ringSum(JsonNode e, String slot, Pattern pattern) {
    double sum = 0;
    for (var line : e.path("armory").path("accessory").path(slot).path("option")) {
      var m = pattern.matcher(line.asString(""));
      if (m.find()) sum += Double.parseDouble(m.group(1));
    }
    return sum;
  }

  private static double bangleSum(JsonNode e, KrakenScoreEngine.Tables tables, String key) {
    var bangle = e.path("armory").path("accessory").path("bangle");
    if (!truthy(bangle.path("option"))
        || !truthy(bangle.path("grade"))
        || !truthy(bangle.path("tier"))) return 0;
    var row = tables.get("we").path(KrakenScoreEngine.jsString(bangle.path("tier")));
    double sum = 0;
    for (var line : bangle.path("option")) {
      var v = row.path(line.asString("")).path(key);
      if (truthy(v)) sum += v.asDouble();
    }
    return sum;
  }

  private static double gemEffect(JsonNode e, String name) {
    for (var effect : e.path("arkGrid").path("gemEffects"))
      if (name.equals(effect.path("name").asString("")))
        return effect.path("effect").isNumber() ? effect.path("effect").asDouble() : 0;
    return 0;
  }

  private static double maxDamageGem(JsonNode e, List<String> skills) {
    double max = 0;
    for (var gem : e.path("gem").path("gems"))
      if ("damage".equals(gem.path("type").asString(""))
          && gem.path("valid").asBoolean(false)
          && skills.contains(gem.path("skill").asString("")))
        max = Math.max(max, gem.path("level").asDouble());
    return max;
  }

  /** 질서 코어 배수(원본 Y: MU[서폿|딜러], Um[support|dealer]). */
  private static double orderCore(JsonNode e, KrakenScoreEngine.Tables tables) {
    boolean support = e.path("profile").path("supportCheck").asBoolean(false);
    var mu = tables.get("MU").path(support ? "서폿" : "딜러");
    var role = support ? "support" : "dealer";
    var cores = e.path("arkGrid").path("core");
    if (!(truthy(mu) && truthy(e.path("arkGrid")) && cores.isArray())) return 1;
    var allowed = new ArrayList<String>();
    for (var name : mu.path("mainCore")) allowed.add(name.asString(""));
    for (var name : mu.path("starCore")) allowed.add(name.asString(""));
    var present = new ArrayList<String>();
    for (var c : cores) {
      var name = c.path("name").asString("").trim();
      if (!name.isEmpty() && allowed.contains(name)) present.add(name);
    }
    boolean both =
        present.stream().anyMatch(x -> x.startsWith("질서의 해"))
            && present.stream().anyMatch(x -> x.startsWith("질서의 달"));
    double o = 1;
    for (var c : cores) {
      if (!truthy(c.path("name"))) continue;
      var name = c.path("name").asString("").trim();
      if (!allowed.contains(name)) continue;
      String group = null;
      String key = KrakenScoreEngine.jsString(c.path("point"));
      if (name.startsWith("질서의 해") || name.startsWith("질서의 달")) {
        group = "group1";
        if (c.path("point").asDouble() >= 14 && !both) key = "10";
      } else if (name.startsWith("질서의 별")) group = "group2";
      if (group != null) {
        var grade = "고대".equals(c.path("grade").asString("")) ? "고대" : "유물";
        var v = tables.get("Um").path(role).path(group).path(grade).path(key);
        if (!v.isMissingNode() && !v.isNull()) o *= v.asDouble();
      }
    }
    return o;
  }
}
