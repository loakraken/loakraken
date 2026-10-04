package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class LostArkEquipmentTooltip {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern REFINE = Pattern.compile("^\\+(\\d{1,2})\\s");
  private static final Pattern ITEM_LEVEL = Pattern.compile("아이템 레벨 (\\d{1,4})");
  private static final Pattern TIER = Pattern.compile("티어 (\\d)");
  private static final Pattern POINT = Pattern.compile("^(\\S+) \\+(\\d{1,3})$");
  private static final Pattern STONE_ENGRAVING = Pattern.compile("^\\[(.+)] Lv\\.(\\d{1,2})$");
  private static final String ARK_PASSIVE_POINT_TITLE = "아크 패시브 포인트 효과";
  private static final String STONE_ENGRAVING_TITLE = "무작위 각인 효과";
  private static final String POLISH_TITLE = "연마 효과";
  private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");
  private static final Pattern FONT_COLOR =
      Pattern.compile("(?i)<font[^>]*color=['\"]?#?([0-9a-f]{6})");
  private static final Pattern NAME_VALUE = Pattern.compile("^(.+?) ([+-][0-9][0-9.,]*%?)$");
  private static final java.util.Map<String, CharacterLoadout.PolishGrade> POLISH_GRADES =
      java.util.Map.of(
          "FE9600", CharacterLoadout.PolishGrade.HIGH,
          "CE43FC", CharacterLoadout.PolishGrade.MID,
          "00B5FF", CharacterLoadout.PolishGrade.LOW);

  private LostArkEquipmentTooltip() {}

  static CharacterLoadout.EquipmentDetail parse(String name, String tooltip) {
    if (tooltip == null || tooltip.isBlank()) return null;
    JsonNode root;
    try {
      root = MAPPER.readTree(tooltip);
    } catch (JacksonException exception) {
      return null;
    }
    if (root == null || !root.isObject()) return null;
    Integer quality = null, itemLevel = null, tier = null;
    boolean titleRead = false;
    CharacterLoadout.PointBonus arkPassivePoint = null;
    var effects = new ArrayList<CharacterLoadout.EffectGroup>();
    var stoneEngravings = new ArrayList<CharacterLoadout.StoneEngraving>();
    var polishEffects = new ArrayList<CharacterLoadout.PolishEffect>();
    for (var element : root.values()) {
      var value = element.path("value");
      switch (text(element.path("type"))) {
        case "ItemTitle" -> {
          if (titleRead) continue;
          titleRead = true;
          quality = quality(value.path("qualityValue"));
          String levelText = LostArkText.plain(text(value.path("leftStr2")));
          itemLevel = number(ITEM_LEVEL, levelText, 1, 9999);
          tier = number(TIER, levelText, 1, 9);
        }
        case "ItemPartBox" -> {
          String title = LostArkText.plain(text(value.path("Element_000")));
          var lines = lines(LostArkText.plain(text(value.path("Element_001"))));
          if (title.isEmpty() && lines.isEmpty()) continue;
          if (ARK_PASSIVE_POINT_TITLE.equals(title)) arkPassivePoint = point(lines);
          else effects.add(new CharacterLoadout.EffectGroup(title, lines));
          if (POLISH_TITLE.equals(title))
            polishEffects.addAll(polish(text(value.path("Element_001"))));
        }
        case "IndentStringGroup" -> {
          for (var group : value.values()) {
            String title = LostArkText.plain(text(group.path("topStr")));
            var lines = new ArrayList<String>();
            for (var entry : group.path("contentStr").values())
              lines.addAll(lines(LostArkText.plain(text(entry.path("contentStr")))));
            if (title.isEmpty() && lines.isEmpty()) continue;
            effects.add(new CharacterLoadout.EffectGroup(title, lines));
            if (STONE_ENGRAVING_TITLE.equals(title)) stoneEngravings.addAll(stone(lines));
          }
        }
        default -> {}
      }
    }
    return new CharacterLoadout.EquipmentDetail(
        number(REFINE, LostArkText.plain(name), 1, 99),
        quality,
        itemLevel,
        tier,
        arkPassivePoint,
        effects,
        stoneEngravings,
        polishEffects);
  }

  private static List<CharacterLoadout.PolishEffect> polish(String source) {
    var result = new ArrayList<CharacterLoadout.PolishEffect>();
    for (var raw : LINE_BREAK.split(source)) {
      String line = LostArkText.plain(raw);
      if (line.isEmpty()) continue;
      var nameValue = NAME_VALUE.matcher(line);
      if (!nameValue.matches()) continue;
      var color = FONT_COLOR.matcher(raw);
      var grade = color.find() ? POLISH_GRADES.get(color.group(1).toUpperCase()) : null;
      result.add(new CharacterLoadout.PolishEffect(nameValue.group(1), nameValue.group(2), grade));
    }
    return result;
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : "";
  }

  private static Integer quality(JsonNode node) {
    if (!node.isIntegralNumber() || !node.canConvertToInt()) return null;
    int value = node.intValue();
    return value >= 0 && value <= 100 ? value : null;
  }

  private static Integer number(Pattern pattern, String text, int min, int max) {
    if (text == null) return null;
    var matcher = pattern.matcher(text);
    if (!matcher.find()) return null;
    int value = Integer.parseInt(matcher.group(1));
    return value >= min && value <= max ? value : null;
  }

  private static List<String> lines(String text) {
    if (text == null || text.isEmpty()) return List.of();
    return text.lines().filter(line -> !line.isBlank()).toList();
  }

  private static CharacterLoadout.PointBonus point(List<String> lines) {
    if (lines.size() != 1) return null;
    var matcher = POINT.matcher(lines.getFirst());
    if (!matcher.matches()) return null;
    return new CharacterLoadout.PointBonus(matcher.group(1), Integer.parseInt(matcher.group(2)));
  }

  private static List<CharacterLoadout.StoneEngraving> stone(List<String> lines) {
    var engravings = new ArrayList<CharacterLoadout.StoneEngraving>();
    for (var line : lines) {
      var matcher = STONE_ENGRAVING.matcher(line);
      if (matcher.matches())
        engravings.add(
            new CharacterLoadout.StoneEngraving(
                matcher.group(1), Integer.parseInt(matcher.group(2))));
    }
    return engravings;
  }
}
