package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 장비로 지은 딜러 전투력(절대값, 계산 검증 11-21 'PR2 formula'). 점수 입력({@link KrakenScoreInputMapper}과 같은 구조)에서 비교
 * 기준 B의 딜러 전투력 식을 연산 순서·내림·반올림 위치까지 그대로 계산한다(double). 공격력은 {@link KrakenScoreEngine#attack}의 전투력 경로
 * 값이고, 주 스탯 보정값은 {@link #OFFSET}이다.
 *
 * <p>쓰임: 공식 전투력과의 차이 r = 공식 ÷ 이 값 − 1이 작은 캐릭터만 방어구 재련 전투력을 보인다(3단계). 표는 리소스
 * kraken-score/combat-power-tables.json(출처 줄은 표 파일에).
 */
public final class DealerCombatPower {
  /**
   * 주 스탯 보정값(powerIndex_combat). 재련 전투력 모델의 mainStatOffset과 같은 값·출처(비교 기준 B의 캐릭터별 보정값 중 잘 맞는 딜러 4명의
   * 가운데, 표본은 약 2,000~3,000 범위만 가름).
   */
  public static final int OFFSET = 2400;

  private DealerCombatPower() {}

  /** 리소스 combat-power-tables.json의 tables. */
  public record Tables(JsonNode tables) {
    public Tables {
      Objects.requireNonNull(tables, "tables");
      for (var key :
          List.of(
              "level",
              "weapon_quality",
              "ability_attack",
              "gem",
              "esther_weapon",
              "card_set",
              "arkgrid_core",
              "orb",
              "petDamage"))
        if (tables.path(key).isMissingNode())
          throw new IllegalArgumentException("딜러 전투력 표가 없습니다: " + key);
    }

    JsonNode get(String key) {
      return tables.path(key);
    }
  }

  /**
   * 공식 전투력처럼 소수 둘째 자리(JS Math.round, 반올림). 서포터(입력 profile.supportCheck, 서비스 전체와 같은 판정)이거나 입력이 모자라
   * 계산할 수 없으면 null(딜러 식이라 서포터에는 쓰지 않는다).
   */
  public static BigDecimal of(JsonNode input, KrakenScoreEngine.Tables engine, Tables tables) {
    if (input.path("profile").path("supportCheck").asBoolean(false)) return null;
    if (!input.path("profile").path("level").isNumber()) return null;
    var e = input.deepCopy();
    ((ObjectNode) e.path("stats")).put("powerIndex_combat", OFFSET);
    double a = KrakenScoreEngine.attack(e, engine).baseCombat();
    double cp =
        288
            * a
            / 1e6
            * engraving(e, tables)
            * level(e, tables)
            * weaponQuality(e, tables)
            * gem(e, tables)
            * orb(e, tables)
            * stats(e)
            * arkGridGem(e)
            * arkGridCore(e, tables)
            * arkPassive(e)
            * accessory(e)
            * card(e, tables)
            * karma(e)
            * bracelet(e, engine)
            * esther(e, tables)
            * pet(e, tables);
    if (!Double.isFinite(cp)) return null;
    return BigDecimal.valueOf(Math.round(100 * cp)).movePointLeft(2);
  }

  private static double pct(double v) {
    return v / 1e4 + 1;
  }

  /** JS String(number): 정수면 소수점 없이. */
  static String key(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "null";
    if (node.isNumber()) {
      double v = node.asDouble();
      return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
    }
    return node.asString("");
  }

  private static double lookup(JsonNode table, String key) {
    var v = table.path(key);
    return v.isNumber() ? v.asDouble() : Double.NaN;
  }

  static double level(JsonNode e, Tables t) {
    double v = lookup(t.get("level"), key(e.path("profile").path("level")));
    return Double.isNaN(v) ? 1 : pct(v);
  }

  static double weaponQuality(JsonNode e, Tables t) {
    var q = e.path("armory").path("equipment").path("weapon").path("quality");
    double v = lookup(t.get("weapon_quality"), q.isNumber() ? key(q) : "0");
    return Double.isNaN(v) ? 1 : pct(v);
  }

  /** 아크 패시브: 세 항의 곱을 소수 넷째 자리로(JS toFixed(4)). */
  static double arkPassive(JsonNode e) {
    var ap = e.path("arkPassive");
    double evo = ap.path("evolution").path("points").asDouble(0);
    double enl = ap.path("enlightenment").path("points").asDouble(0);
    double leap = ap.path("leap").path("points").asDouble(0);
    double product =
        (75 * Math.max(evo - 40, 0) / 1e4 + 1)
            * (Math.min(70 * Math.max(enl, 0), 7000) / 1e4 + 1)
            * (20 * Math.max(leap, 0) / 1e4 + 1);
    return toFixed(product, 4);
  }

  /** JS Number(x.toFixed(digits)): 이진 값 그대로의 십진 값을 반올림(같으면 큰 쪽). */
  static double toFixed(double x, int digits) {
    return new BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).doubleValue();
  }

  /** 카르마: 진화 랭크와 도약 레벨만(깨달음 카르마는 공격력 쪽, #1004). */
  static double karma(JsonNode e) {
    var ap = e.path("arkPassive");
    double rank = ap.path("evolution").path("karmaRank").asDouble(0);
    double leapLevel = ap.path("leap").path("karmalevel").asDouble(0);
    return (rank != 0 ? 0.6 * rank / 100 + 1 : 1)
        * (leapLevel != 0 ? 0.02 * leapLevel / 100 + 1 : 1);
  }

  static double engraving(JsonNode e, Tables t) {
    double product = 1;
    for (var row : e.path("engraving")) {
      var byName = t.get("ability_attack").path(row.path("name").asString(""));
      if (!byName.isObject()) continue;
      int stone = row.path("stone").isNumber() ? row.path("stone").asInt() : 0;
      int index =
          20 * stone
              + row.path("level").asInt(0)
              + ("유물".equals(row.path("grade").asString("")) ? 9 : 5);
      double v = lookup(byName, Integer.toString(index));
      if (Double.isNaN(v) || v == 1) continue;
      product *= pct(v);
    }
    return product;
  }

  private record Line(Pattern pattern, double factor) {}

  /** 장신구 연마 줄: 처음 맞는 식의 수 × 계수(비교 기준 상수). */
  private static final List<Line> ACCESSORY_LINES =
      List.of(
          new Line(Pattern.compile("^공격력 \\+(\\d+\\.?\\d+)$"), 0.000007),
          new Line(Pattern.compile("^공격력 \\+(\\d+\\.?\\d+)%$"), 0.01),
          new Line(Pattern.compile("^추가 피해 \\+(\\d+\\.?\\d+)%$"), 0.007692),
          new Line(Pattern.compile("^치명타 적중률 \\+(\\d+\\.?\\d+)%$"), 0.007742),
          new Line(Pattern.compile("^치명타 피해 \\+(\\d+\\.?\\d+)%$"), 0.003),
          new Line(Pattern.compile("^적에게 주는 피해 \\+(\\d+\\.?\\d*)%$"), 0.01));

  static double accessory(JsonNode e) {
    double product = 1;
    var accessory = e.path("armory").path("accessory");
    for (var slot : List.of("earing1", "earing2", "necklace", "ring1", "ring2"))
      for (var option : accessory.path(slot).path("option")) {
        var text = option.asString("");
        for (var line : ACCESSORY_LINES) {
          var m = line.pattern().matcher(text);
          if (m.find()) {
            product *= 1 + Double.parseDouble(m.group(1)) * line.factor();
            break;
          }
        }
      }
    return product;
  }

  static double bracelet(JsonNode e, KrakenScoreEngine.Tables engine) {
    double product = 1;
    var table = engine.get("braceletAddonAttack");
    for (var option : e.path("armory").path("accessory").path("bangle").path("option")) {
      double v = lookup(table, option.asString(""));
      if (!Double.isNaN(v)) product *= pct(v);
    }
    return product;
  }

  static double gem(JsonNode e, Tables t) {
    double product = 1;
    for (var g : e.path("gem").path("gems")) {
      boolean attackBonus = g.path("attackBonus").asDouble(0) != 0;
      double v = lookup(t.get("gem").path(attackBonus ? "4" : "3"), key(g.path("level")));
      if (!Double.isNaN(v)) product *= pct(v);
    }
    return product;
  }

  static double esther(JsonNode e, Tables t) {
    var weapon = e.path("armory").path("equipment").path("weapon");
    var grade = weapon.path("grade").asString("");
    if (!grade.contains("에스더")) return 1;
    var parts = grade.split(" - ");
    String ella = parts.length > 1 ? key(numberNode(parts[1])) : "NaN";
    double v = lookup(t.get("esther_weapon").path(key(weapon.path("reforge"))), ella);
    return pct(Double.isNaN(v) ? 0 : v);
  }

  private static JsonNode numberNode(String text) {
    try {
      return tools.jackson.databind.node.JsonNodeFactory.instance.numberNode(
          Double.parseDouble(text.strip()));
    } catch (NumberFormatException exception) {
      return tools.jackson.databind.node.JsonNodeFactory.instance.stringNode("NaN");
    }
  }

  /** 카드: 문자열 전체가 표에 있으면 그 값, 없으면 '+'로 나눈 조각마다(모르는 조각은 ×1). */
  static double card(JsonNode e, Tables t) {
    var card = e.path("card");
    if (!card.isString()) return 1;
    var table = t.get("card_set");
    double whole = lookup(table, card.asString());
    if (!Double.isNaN(whole)) return pct(whole);
    double product = 1;
    for (var part : card.asString().split("\\+")) {
      double v = lookup(table, part.strip());
      if (!Double.isNaN(v)) product *= pct(v);
    }
    return product;
  }

  static double arkGridGem(JsonNode e) {
    double product = 1;
    for (var effect : e.path("arkGrid").path("gemEffects")) {
      double lv = effect.path("level").asDouble(0);
      product *=
          switch (effect.path("name").asString("")) {
            case "공격력" -> Math.floor(10 * lv / 3) / 1e4 + 1;
            case "추가 피해" -> Math.floor(35 * lv / 6) / 1e4 + 1;
            case "보스 피해" -> Math.floor(25 * lv / 3) / 1e4 + 1;
            default -> 1;
          };
    }
    return product;
  }

  /** 아크 그리드 코어: 표 값을 그대로 곱한다(/1e4 없음). */
  static double arkGridCore(JsonNode e, Tables t) {
    double product = 1;
    for (var core : e.path("arkGrid").path("core")) {
      var name = core.path("name").asString("");
      String coreKey;
      if (name.contains("질서")) coreKey = name.split(":")[0].strip();
      else if (name.contains("혼돈")) coreKey = name;
      else continue;
      var byGrade =
          t.get("arkgrid_core").path("고대".equals(core.path("grade").asString("")) ? "고대" : "유물");
      double v = lookup(byGrade.path(coreKey), key(core.path("point")));
      if (!Double.isNaN(v)) product *= v;
    }
    return product;
  }

  static double orb(JsonNode e, Tables t) {
    var orb = e.path("armory").path("orb");
    if (!orb.isObject()) return 1;
    var row = t.get("orb").path(orb.path("name").asString(""));
    if (!row.isArray() || row.size() < 2) return 1;
    double base = row.get(0).asDouble();
    double perTenMillion = row.get(1).asDouble();
    double trinity = orb.path("trinity").asDouble(0);
    return Math.floor(1e6 * ((base + perTenMillion * trinity / 1e7) / 100 + 1)) / 1e6;
  }

  /** 팔찌 줄의 스탯(비교 기준 팔찌 파싱 규칙: 줄마다 처음 맞는 것, 고정되지 않은 식, 쉼표 뺌). */
  private static final Pattern BRACELET_STAT =
      Pattern.compile("(치명|특화|신속|제압|인내|숙련)\\s*\\+([\\d,]+(?:\\.\\d+)?)");

  /**
   * 스탯: 입력 스탯(공식 프로필 − 팔찌 고정값)에 팔찌 줄의 치명·특화·신속을 되더해(합은 공식 프로필과 같다) × 3 / 1e4 + 1. 팔찌 줄은 비교 기준처럼 옵션
   * 문장에서 다시 읽는다(계산 검증 11-21).
   */
  static double stats(JsonNode e) {
    var s = e.path("stats");
    double sum =
        s.path("critical").asDouble(0)
            + s.path("haste").asDouble(0)
            + s.path("special").asDouble(0);
    for (var option : e.path("armory").path("accessory").path("bangle").path("option")) {
      var m = BRACELET_STAT.matcher(option.asString(""));
      if (m.find() && List.of("치명", "특화", "신속").contains(m.group(1)))
        sum += Double.parseDouble(m.group(2).replace(",", ""));
    }
    return sum * 3 / 1e4 + 1;
  }

  static double pet(JsonNode e, Tables t) {
    var damage = e.path("baseEffect").path("petDamage");
    if (!damage.isNumber()) return 1;
    double v = lookup(t.get("petDamage"), key(damage));
    return Double.isNaN(v) ? 1 : v;
  }

  /** 표 순서대로 낸 요소들(검증·디버그용). */
  static Map<String, Double> factors(JsonNode input, KrakenScoreEngine.Tables engine, Tables t) {
    var e = input.deepCopy();
    ((ObjectNode) e.path("stats")).put("powerIndex_combat", OFFSET);
    var out = new java.util.LinkedHashMap<String, Double>();
    out.put("A", KrakenScoreEngine.attack(e, engine).baseCombat());
    out.put("engraving", engraving(e, t));
    out.put("level", level(e, t));
    out.put("weaponQuality", weaponQuality(e, t));
    out.put("gem", gem(e, t));
    out.put("orb", orb(e, t));
    out.put("stats", stats(e));
    out.put("arkGridGem", arkGridGem(e));
    out.put("arkGridCore", arkGridCore(e, t));
    out.put("arkPassive", arkPassive(e));
    out.put("accessory", accessory(e));
    out.put("card", card(e, t));
    out.put("karma", karma(e));
    out.put("bracelet", bracelet(e, engine));
    out.put("esther", esther(e, t));
    out.put("pet", pet(e, t));
    return out;
  }
}
