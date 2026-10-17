package com.loakraken.character.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 크라켄 점수(#559): 공식 armories 응답 → 점수 엔진 입력. 엔진이 실제로 읽는 필드만 만든다(1인당 약 7KB, 원문은 남기지 않음).
 *
 * <p>대응 규칙은 같은 시각에 받은 기준 캐릭터 6명의 공식 원문과 비교 기준 B 입력을 필드마다 맞춰 정했다(6명 모두 엔진 점수 double 끝자리까지 같음).
 *
 * <ul>
 *   <li>스탯(치명·특화·신속) = 공식 프로필 값 − 팔찌의 같은 고정 스탯
 *   <li>아크 그리드 코어 순서 = 질서 해·달·별 → 혼돈 해·달·별, 젬 포인트·효과는 젬 툴팁
 *   <li>보석 순서 = 딜 보석 → 쿨 보석, 같은 종류는 레벨 높은 순, 같으면 공식 슬롯 순서(기본 공격력 합은 이 순서로 더함)
 *   <li>아바타 = 부위마다 안쪽 아바타(없으면 그 부위 아바타)의 주 스탯 % 합(덧입기 바깥 스탯은 적용 안 됨)
 *   <li>서포터 = 서포터 각인 직업, 에스더 무기 = 엘라 부여 단계, 팔찌 옵션 = 옵션 아이콘 단위(여러 줄 옵션 한 줄로)
 *   <li>점수·젬 효율에 쓰이지 않는 전투력 경로 값(보정 지수·펫 스탯)은 엔진 기본값
 * </ul>
 *
 * 객체 키 순서도 원본과 같게 둔다(엔진이 키 순서대로 더하는 곳이 있음). 읽을 수 없는 값은 null이고 근사하지 않는다.
 */
public final class KrakenScoreInputMapper {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;
  private static final Pattern TAG = Pattern.compile("<[^>]*>");
  private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
  private static final Pattern GRAY = Pattern.compile("#686660", Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
  private static final Pattern KARMA = Pattern.compile("(\\d+)랭크\\s*(\\d+)레벨");
  private static final Pattern NODE = Pattern.compile("(\\d+)티어\\s+(.+?)\\s+Lv\\.(\\d+)");
  private static final Pattern GEM_POINT = Pattern.compile("(?:질서|혼돈) 포인트 : (\\d+)");
  private static final Pattern GEM_EFFECT = Pattern.compile("\\[([^\\]]+)\\] Lv\\.(\\d+)");
  private static final Pattern CORE = Pattern.compile("의 (해|달|별) 코어");
  private static final Pattern MAIN_STAT = Pattern.compile("^(힘|민첩|지능) \\+(\\d+)");
  private static final Pattern WEAPON_ATK = Pattern.compile("^무기 공격력 \\+(\\d+)");
  private static final Pattern BASE_ATK_FLAT = Pattern.compile("^기본 공격력 \\+(\\d+)$");
  private static final Pattern BASE_ATK_PERCENT = Pattern.compile("^기본 공격력 \\+(\\d+(?:\\.\\d+)?)%");
  private static final Pattern TIER = Pattern.compile("티어 (\\d+)");
  private static final Pattern ELLA = Pattern.compile("엘라 부여 (\\d+) 단계");
  private static final Pattern STONE_BONUS =
      Pattern.compile("\\[레벨 보너스\\] 기본 공격력 \\+(\\d+(?:\\.\\d+)?)%");
  private static final Pattern ORB = Pattern.compile("달성 최대 낙원력 : (\\d+)");
  private static final Pattern BRACELET_ICON =
      Pattern.compile(
          "<img[^>]*emoticon_tooltip_bracelet[^>]*>(?:\\s*</img>)?", Pattern.CASE_INSENSITIVE);
  private static final Pattern BRACELET_STAT = Pattern.compile("^(치명|특화|신속)\\+(\\d+)$");
  private static final Pattern GEM_BONUS = Pattern.compile("기본 공격력 (\\d+(?:\\.\\d+)?)% 증가");
  private static final Pattern AVATAR_STAT =
      Pattern.compile("(?:힘|민첩|지능)\\s*\\+\\s*(\\d+(?:\\.\\d+)?)%");
  private static final Map<String, String> ARK_PASSIVE =
      Map.of("진화", "evolution", "깨달음", "enlightenment", "도약", "leap");
  private static final Map<String, String> EQUIPMENT =
      Map.of(
          "무기",
          "weapon",
          "투구",
          "helmet",
          "상의",
          "armor",
          "하의",
          "pants",
          "장갑",
          "gloves",
          "어깨",
          "shoulder",
          "완갑",
          "vambrace");
  private static final Map<String, String> BRACELET_STATS =
      Map.of("치명", "critical", "특화", "special", "신속", "haste");

  /** 서포터 각인 직업(공식 아크 패시브 제목). */
  static final Set<String> SUPPORT_TITLES = Set.of("절실한 구원", "축복의 오라", "만개", "빛의 기사");

  private KrakenScoreInputMapper() {}

  /** 공식 armories 응답 본문(JSON 트리) → 엔진 입력. */
  public static ObjectNode from(JsonNode raw) {
    var out = F.objectNode();
    var armory = armory(raw);
    var secondClass = text(raw.path("ArkPassive").path("Title"));
    var profile = out.putObject("profile");
    profile.set("class", textNode(raw.path("ArmoryProfile").path("CharacterClassName")));
    profile.put("secondClass", secondClass);
    profile.put("supportCheck", secondClass != null && SUPPORT_TITLES.contains(secondClass));
    out.putObject("avatar").set("avatarStats", number(avatarStats(raw)));
    out.set("skills", skills(raw));
    // 점수·젬 효율에 쓰이지 않는 전투력 경로 값 — 엔진 기본값.
    out.putObject("baseEffect").set("petStat", number(1));
    out.set("gem", gem(raw));
    out.set("armory", armory);
    out.set("stats", stats(raw, braceletStats(armory)));
    out.set("arkPassive", arkPassive(raw));
    out.set("engraving", engraving(raw));
    out.set("arkGrid", arkGrid(raw));
    return out;
  }

  /**
   * 서폿 공증 가동률이 스킬 이름과 고른 트라이포드 이름을 본다(예: 특정 스킬 + '빠른 준비'면 공증 재사용 대기시간이 줄어듦). 레벨 2 이상이거나 트라이포드를 고른
   * 스킬, 공식 순서.
   */
  private static ArrayNode skills(JsonNode raw) {
    var out = F.arrayNode();
    for (var s : raw.path("ArmorySkills")) {
      var selected = new ArrayList<JsonNode>();
      for (var t : s.path("Tripods")) if (t.path("IsSelected").asBoolean(false)) selected.add(t);
      if (s.path("Level").asDouble() <= 1 && selected.isEmpty()) continue;
      var skill = out.addObject();
      skill.set("name", copy(s.path("Name")));
      var tripods = skill.putArray("tripods");
      for (var t : selected) tripods.addObject().set("name", copy(t.path("Name")));
    }
    return out;
  }

  private static ObjectNode stats(JsonNode raw, Map<String, Double> bracelet) {
    var byType = new LinkedHashMap<String, Double>();
    for (var s : raw.path("ArmoryProfile").path("Stats"))
      byType.put(s.path("Type").asString(""), firstNumber(s.path("Value")));
    var out = F.objectNode();
    out.set("critical", minus(byType, "치명", bracelet.get("critical")));
    out.set("special", minus(byType, "특화", bracelet.get("special")));
    out.set("haste", minus(byType, "신속", bracelet.get("haste")));
    return out;
  }

  private static JsonNode minus(Map<String, Double> byType, String type, Double bracelet) {
    var value = byType.get(type);
    return number((value == null ? 0 : value) - (bracelet == null ? 0 : bracelet));
  }

  private static ObjectNode arkPassive(JsonNode raw) {
    var out = F.objectNode();
    for (var p : raw.path("ArkPassive").path("Points")) {
      var key = ARK_PASSIVE.get(p.path("Name").asString(""));
      if (key == null) continue;
      var m = KARMA.matcher(p.path("Description").asString(""));
      boolean found = m.find();
      var group = out.putObject(key);
      group.set("points", p.path("Value").isMissingNode() ? F.nullNode() : p.get("Value"));
      group.set("karmaRank", found ? number(Integer.parseInt(m.group(1))) : F.nullNode());
      group.set("karmalevel", found ? number(Integer.parseInt(m.group(2))) : F.nullNode());
      group.putArray("nodes");
    }
    for (var e : raw.path("ArkPassive").path("Effects")) {
      var key = ARK_PASSIVE.get(e.path("Name").asString(""));
      if (key == null || !out.has(key)) continue;
      var m = NODE.matcher(strip(e.path("Description").asString("")));
      if (!m.find()) continue;
      var node = ((ArrayNode) out.get(key).get("nodes")).addObject();
      node.put("name", m.group(2));
      node.set("level", number(Integer.parseInt(m.group(3))));
      node.set("tier", number(Integer.parseInt(m.group(1))));
    }
    return out;
  }

  private static ArrayNode engraving(JsonNode raw) {
    var out = F.arrayNode();
    for (var e : raw.path("ArmoryEngraving").path("ArkPassiveEffects")) {
      var row = out.addObject();
      row.set("name", copy(e.path("Name")));
      row.set("grade", copy(e.path("Grade")));
      row.set("level", copy(e.path("Level")));
      row.set("stone", copy(e.path("AbilityStoneLevel")));
    }
    return out;
  }

  private static ObjectNode arkGrid(JsonNode raw) {
    var cores = new ArrayList<ObjectNode>();
    for (var s : raw.path("ArkGrid").path("Slots")) {
      var core = F.objectNode();
      core.set("name", copy(s.path("Name")));
      core.set("grade", copy(s.path("Grade")));
      core.set("point", copy(s.path("Point")));
      var gems = core.putArray("gem");
      for (var g : s.path("Gems")) {
        var text = new StringBuilder();
        var tooltip = tooltip(g.path("Tooltip"));
        for (var e : elements(tooltip))
          if ("ItemPartBox".equals(e.path("type").asString("")))
            text.append(strip(write(e.path("value")))).append('\n');
        var gem = gems.addObject();
        var point = GEM_POINT.matcher(text);
        gem.set(
            "orderChaosPoint",
            point.find() ? number(Integer.parseInt(point.group(1))) : F.nullNode());
        var effects = gem.putArray("effects");
        var m = GEM_EFFECT.matcher(text);
        while (m.find()) {
          var effect = effects.addObject();
          effect.put("name", m.group(1));
          effect.set("level", number(Integer.parseInt(m.group(2))));
        }
      }
      cores.add(core);
    }
    // 원본 입력은 질서 해·달·별 → 혼돈 해·달·별 순서다(공식 슬롯 순서와 다를 수 있음). 같은 순위는 원래 순서(안정 정렬).
    cores.sort(
        (a, b) ->
            Integer.compare(
                coreRank(a.path("name").asString("")), coreRank(b.path("name").asString(""))));
    var out = F.objectNode();
    var core = out.putArray("core");
    cores.forEach(core::add);
    var effects = out.putArray("gemEffects");
    for (var e : raw.path("ArkGrid").path("Effects")) {
      var effect = effects.addObject();
      effect.set("name", copy(e.path("Name")));
      effect.set("level", copy(e.path("Level")));
      // 원본: 태그를 지운 문장에서 마지막 '+' 뒤 수.
      var value = firstNumber(strip(e.path("Tooltip").asString("")).replaceFirst(".*\\+", ""));
      effect.set("effect", value == null ? F.nullNode() : number(value));
    }
    return out;
  }

  private static int coreRank(String name) {
    int series = name.startsWith("혼돈") ? 3 : 0;
    var m = CORE.matcher(name);
    if (!m.find()) return series + 9;
    return series
        + switch (m.group(1)) {
          case "해" -> 0;
          case "달" -> 1;
          default -> 2;
        };
  }

  private static ObjectNode armory(JsonNode raw) {
    var out = F.objectNode();
    var equipment = out.putObject("equipment");
    var accessory = out.putObject("accessory");
    int earing = 0;
    int ring = 0;
    for (var item : raw.path("ArmoryEquipment")) {
      var type = item.path("Type").asString("");
      var tooltip = tooltip(item.path("Tooltip"));
      var base = part(tooltip, "기본 효과");
      var key = EQUIPMENT.get(type);
      if ("weapon".equals(key)) {
        JsonNode grade = copy(item.path("Grade"));
        if ("에스더".equals(grade.asString(""))) {
          var ella = ELLA.matcher(strip(write(tooltip)));
          if (ella.find()) grade = F.stringNode("에스더 - " + ella.group(1));
        }
        var weapon = equipment.putObject("weapon");
        weapon.set("grade", grade);
        weapon.set("quality", copy(itemTitle(tooltip).path("qualityValue")));
        weapon.set("stat", lineValue(base, WEAPON_ATK));
      } else if ("vambrace".equals(key)) {
        var vambrace = equipment.putObject("vambrace");
        vambrace.set("stat", mainStat(base));
        vambrace.set("weaponAtkFlat", lineValue(base, WEAPON_ATK));
        vambrace.set("baseAtkFlat", lineValue(base, BASE_ATK_FLAT));
        vambrace.set("baseAtkPercent", lineValue(part(tooltip, "추가 효과"), BASE_ATK_PERCENT));
      } else if (key != null) {
        equipment.putObject(key).set("stat", mainStat(base));
      } else if (type.equals("목걸이") || type.equals("귀걸이") || type.equals("반지")) {
        var name =
            switch (type) {
              case "목걸이" -> "necklace";
              case "귀걸이" -> "earing" + ++earing;
              default -> "ring" + ++ring;
            };
        var acc = accessory.putObject(name);
        acc.set("stat", mainStat(base));
        var option = acc.putArray("option");
        lines(part(tooltip, "연마 효과"), true).forEach(option::add);
      } else if (type.equals("팔찌")) {
        var bangle = accessory.putObject("bangle");
        bangle.set("grade", copy(item.path("Grade")));
        var tier = TIER.matcher(strip(itemTitle(tooltip).path("leftStr2").asString("")));
        bangle.set("tier", tier.find() ? number(Integer.parseInt(tier.group(1))) : F.nullNode());
        var option = bangle.putArray("option");
        // 옵션 하나가 여러 줄일 수 있어 줄이 아니라 옵션 앞 아이콘으로 자른다.
        var html = part(tooltip, "팔찌 효과");
        for (var chunk : BRACELET_ICON.split(html == null ? "" : html, -1)) {
          var line = strip(BR.matcher(chunk).replaceAll("")).replaceAll("\\s+", "");
          if (!line.isEmpty()) option.add(line);
        }
      } else if (type.equals("어빌리티 스톤")) {
        var m = STONE_BONUS.matcher(strip(write(tooltip)));
        out.putObject("abilityStone")
            .set("attackbonus", m.find() ? number(Double.parseDouble(m.group(1))) : F.nullNode());
      } else if (type.equals("보주")) {
        var m = ORB.matcher(strip(write(tooltip)));
        var orb = out.putObject("orb");
        orb.set("name", copy(item.path("Name")));
        orb.set("trinity", m.find() ? number(Long.parseLong(m.group(1))) : F.nullNode());
      }
    }
    return out;
  }

  /** 팔찌 옵션의 치명·특화·신속 고정값(공식 프로필 스탯에서 빼서 입력 스탯을 만든다). */
  private static Map<String, Double> braceletStats(ObjectNode armory) {
    var out = new LinkedHashMap<String, Double>();
    for (var option : armory.path("accessory").path("bangle").path("option")) {
      var m = BRACELET_STAT.matcher(option.asString(""));
      if (m.find())
        out.merge(BRACELET_STATS.get(m.group(1)), Double.parseDouble(m.group(2)), Double::sum);
    }
    return out;
  }

  private static ObjectNode gem(JsonNode raw) {
    var bySlot = new LinkedHashMap<String, JsonNode>();
    for (var s : raw.path("ArmoryGem").path("Effects").path("Skills"))
      bySlot.put(s.path("GemSlot").asString(""), s);
    var ordered = new ArrayList<JsonNode>();
    raw.path("ArmoryGem").path("Gems").forEach(ordered::add);
    // 원본 순서: 딜 보석 → 쿨 보석, 같은 종류는 레벨 높은 순, 같으면 공식 슬롯 순서.
    ordered.sort(
        (a, b) -> {
          int byType =
              Integer.compare(
                  cooldown(bySlot.get(a.path("Slot").asString(""))) ? 1 : 0,
                  cooldown(bySlot.get(b.path("Slot").asString(""))) ? 1 : 0);
          if (byType != 0) return byType;
          int byLevel = Double.compare(b.path("Level").asDouble(), a.path("Level").asDouble());
          if (byLevel != 0) return byLevel;
          return Double.compare(a.path("Slot").asDouble(), b.path("Slot").asDouble());
        });
    var out = F.objectNode();
    var gems = out.putArray("gems");
    double total = 0;
    for (var g : ordered) {
      var skill = bySlot.get(g.path("Slot").asString(""));
      var desc = description(skill);
      var row = gems.addObject();
      row.set("level", copy(g.path("Level")));
      // 효과 줄에 재사용 대기시간이 있으면 쿨 보석, 그 밖(피해·지원 효과)은 딜 보석.
      row.set(
          "type",
          desc.isEmpty()
              ? F.nullNode()
              : F.stringNode(desc.contains("재사용 대기시간") ? "cooldown" : "damage"));
      var name = skill == null ? null : text(skill.path("Name"));
      row.put("skill", name);
      var bonus = GEM_BONUS.matcher(skill == null ? "" : skill.path("Option").asString(""));
      double value = bonus.find() ? Double.parseDouble(bonus.group(1)) : 0;
      row.set("attackBonus", number(value));
      row.put("valid", name != null && !name.isEmpty());
      total += value; // 원본처럼 보석 순서대로 더한다(소수 끝자리).
    }
    out.set("attackBonus", number(total));
    return out;
  }

  private static boolean cooldown(JsonNode skill) {
    return description(skill).contains("재사용 대기시간");
  }

  private static String description(JsonNode skill) {
    if (skill == null) return "";
    var parts = new ArrayList<String>();
    for (var d : skill.path("Description")) parts.add(d.asString(""));
    return String.join(" ", parts);
  }

  /** 아바타 주 스탯 % 합: 부위마다 안쪽 아바타가 있으면 그것만, 없으면 그 부위 아바타. */
  private static double avatarStats(JsonNode raw) {
    var bySlot = new LinkedHashMap<String, JsonNode>();
    for (var a : raw.path("ArmoryAvatars")) {
      var type = a.path("Type").asString("");
      var current = bySlot.get(type);
      if (current == null
          || (a.path("IsInner").asBoolean(false) && !current.path("IsInner").asBoolean(false)))
        bySlot.put(type, a);
    }
    double sum = 0;
    for (var a : bySlot.values()) {
      var m = AVATAR_STAT.matcher(strip(write(tooltip(a.path("Tooltip")))));
      if (m.find()) sum += Double.parseDouble(m.group(1));
    }
    return sum;
  }

  /** 툴팁에서 머리글(Element_000)이 title인 ItemPartBox 본문(HTML). 없으면 null. */
  private static String part(JsonNode tooltip, String title) {
    for (var e : elements(tooltip))
      if ("ItemPartBox".equals(e.path("type").asString(""))
          && strip(e.path("value").path("Element_000").asString("")).contains(title)) {
        var body = e.path("value").path("Element_001");
        return body.isString() ? body.asString() : write(body);
      }
    return null;
  }

  /** HTML 본문을 &lt;BR&gt; 줄로. keepGray가 아니면 회색(적용 안 되는 주 스탯) 줄은 뺀다. */
  private static List<String> lines(String html, boolean keepGray) {
    var out = new ArrayList<String>();
    if (html == null) return out;
    for (var line : BR.split(html, -1)) {
      if (!keepGray && GRAY.matcher(line).find()) continue;
      var text = strip(line).trim();
      if (!text.isEmpty()) out.add(text);
    }
    return out;
  }

  private static JsonNode mainStat(String html) {
    for (var line : lines(html, false)) {
      var m = MAIN_STAT.matcher(line);
      if (m.find()) return number(Long.parseLong(m.group(2)));
    }
    return F.nullNode();
  }

  private static JsonNode lineValue(String html, Pattern pattern) {
    for (var line : lines(html, true)) {
      var m = pattern.matcher(line);
      if (m.find()) return number(Double.parseDouble(m.group(1)));
    }
    return F.nullNode();
  }

  private static JsonNode itemTitle(JsonNode tooltip) {
    for (var e : elements(tooltip))
      if ("ItemTitle".equals(e.path("type").asString(""))) return e.path("value");
    return F.missingNode();
  }

  private static List<JsonNode> elements(JsonNode tooltip) {
    var out = new ArrayList<JsonNode>();
    if (tooltip != null && tooltip.isObject())
      for (var e : tooltip.properties()) if (e.getValue().isObject()) out.add(e.getValue());
    return out;
  }

  private static JsonNode tooltip(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return F.objectNode();
    if (!node.isString()) return node;
    try {
      return MAPPER.readTree(node.asString());
    } catch (JacksonException exception) {
      return F.objectNode();
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JacksonException exception) {
      return "";
    }
  }

  static String strip(String html) {
    return TAG.matcher(html == null ? "" : html).replaceAll("");
  }

  private static Double firstNumber(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode()
        ? null
        : firstNumber(node.asString(""));
  }

  private static Double firstNumber(String text) {
    Matcher m = NUMBER.matcher(text.replace(",", ""));
    return m.find() ? Double.parseDouble(m.group()) : null;
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() || node.isMissingNode() ? null : node.asString("");
  }

  private static JsonNode textNode(JsonNode node) {
    var value = text(node);
    return value == null ? F.nullNode() : F.stringNode(value);
  }

  private static JsonNode copy(JsonNode node) {
    return node == null || node.isMissingNode() ? F.nullNode() : node.deepCopy();
  }

  /** 원본(JS 수)처럼 정수 값은 정수 노드로, 그 밖은 double. */
  static JsonNode number(double value) {
    if (Double.isFinite(value) && value == Math.rint(value) && Math.abs(value) < 9.0e15)
      return F.numberNode((long) value);
    return F.numberNode(value);
  }
}
