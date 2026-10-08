package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 아크 그리드 코어·젬·효과 Tooltip 해석(arkgrid-tooltip-v1). 해석할 수 없으면 null을 돌려준다. */
final class LostArkArkGridTooltip {
  static final String PARSER_VERSION = "arkgrid-tooltip-v1";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern NUMBER = Pattern.compile("([0-9]+)");
  private static final Pattern THRESHOLD = Pattern.compile("\\[(\\d+)P\\]");
  private static final Pattern PERCENT = Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)%");
  private static final Pattern REQUIRED_WILLPOWER =
      Pattern.compile(
          "필요 의지력\\s*:\\s*(\\d+)(?:\\s*\\(기본 값\\s*(\\d+)\\s*[–-]\\s*의지력 효율\\s*(\\d+)\\))?");
  private static final Pattern CORE_POINT = Pattern.compile("(?:질서|혼돈) 포인트\\s*:\\s*(\\d+)");
  private static final Pattern GEM_TYPE = Pattern.compile("젬 타입\\s*:\\s*(\\S+)");
  private static final Pattern GEM_POINT = Pattern.compile("젬 포인트\\s*:\\s*(\\d+)");
  private static final Pattern GEM_OPTION = Pattern.compile("^\\[(.+?)\\]\\s*Lv\\.(\\d+)$");

  private LostArkArkGridTooltip() {}

  static CharacterLoadout.CoreDetail core(String tooltip) {
    var parts = parts(tooltip);
    if (parts == null) return null;
    String type = null;
    Integer supply = null;
    var options = new ArrayList<CharacterLoadout.CoreOption>();
    var conditions = new ArrayList<String>();
    for (var part : parts) {
      switch (part.title()) {
        case "코어 타입" -> type = part.body().strip();
        case "코어 공급 의지력" -> supply = firstInt(NUMBER, part.body());
        case "코어 옵션" -> options.addAll(options(part.body()));
        case "코어 옵션 발동 조건" ->
            part.body()
                .lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .forEach(conditions::add);
        default -> {}
      }
    }
    if (type == null && supply == null && options.isEmpty()) return null;
    return new CharacterLoadout.CoreDetail(type, supply, options, conditions);
  }

  static CharacterLoadout.GemDetail gem(String tooltip) {
    var root = root(tooltip);
    if (root == null) return null;
    String name = null;
    for (var element : root.values())
      if ("NameTagBox".equals(text(element.path("type")))) {
        name = LostArkText.plain(text(element.path("value")));
        break;
      }
    var parts = parts(root);
    String gemType = null;
    Integer gemPoint = null;
    Integer required = null;
    Integer base = null;
    Integer efficiency = null;
    Integer corePoint = null;
    var options = new ArrayList<CharacterLoadout.GemOption>();
    for (var part : parts) {
      if (part.title().equals("젬 기본 정보")) {
        var type = GEM_TYPE.matcher(part.body());
        if (type.find()) gemType = type.group(1);
        gemPoint = firstInt(GEM_POINT, part.body());
      } else if (part.title().equals("젬 효과")) {
        var willpower = REQUIRED_WILLPOWER.matcher(part.body());
        if (willpower.find()) {
          required = Integer.parseInt(willpower.group(1));
          if (willpower.group(2) != null) base = Integer.parseInt(willpower.group(2));
          if (willpower.group(3) != null) efficiency = Integer.parseInt(willpower.group(3));
        }
        corePoint = firstInt(CORE_POINT, part.body());
        options.addAll(gemOptions(part.body()));
      }
    }
    if (gemType == null && required == null && options.isEmpty()) return null;
    return new CharacterLoadout.GemDetail(
        name, gemType, gemPoint, required, base, efficiency, corePoint, options);
  }

  /** 효과 Tooltip(예: "공격력 +1.02%")을 정제 문구와 수치로 나눈다. */
  static CharacterLoadout.GridEffect effect(String name, Integer level, String tooltip) {
    var value = LostArkText.plain(tooltip);
    if (value == null || value.isBlank()) return new CharacterLoadout.GridEffect(name, level);
    value = value.replaceAll("\\s+", " ").strip();
    var percent = firstDecimal(value);
    // 수치를 읽을 수 없는 문자열은 해석하지 못한 원천이므로 노출하지 않는다.
    if (percent == null || value.length() > 120)
      return new CharacterLoadout.GridEffect(name, level);
    return new CharacterLoadout.GridEffect(name, level, value, percent);
  }

  private static List<CharacterLoadout.CoreOption> options(String body) {
    var result = new ArrayList<CharacterLoadout.CoreOption>();
    Matcher matcher = THRESHOLD.matcher(body);
    int previousEnd = -1;
    int previousThreshold = -1;
    while (matcher.find()) {
      if (previousThreshold >= 0)
        result.add(option(previousThreshold, body.substring(previousEnd, matcher.start())));
      previousThreshold = Integer.parseInt(matcher.group(1));
      previousEnd = matcher.end();
    }
    if (previousThreshold >= 0) result.add(option(previousThreshold, body.substring(previousEnd)));
    result.sort(java.util.Comparator.comparingInt(CharacterLoadout.CoreOption::threshold));
    return result;
  }

  private static CharacterLoadout.CoreOption option(int threshold, String text) {
    return new CharacterLoadout.CoreOption(threshold, text.replaceAll("\\s+", " ").strip());
  }

  private static List<CharacterLoadout.GemOption> gemOptions(String body) {
    var result = new ArrayList<CharacterLoadout.GemOption>();
    var lines = body.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    for (int i = 0; i < lines.size(); i++) {
      var header = GEM_OPTION.matcher(lines.get(i));
      if (!header.find()) continue;
      String effect = i + 1 < lines.size() ? lines.get(i + 1) : null;
      if (effect != null && GEM_OPTION.matcher(effect).find()) effect = null;
      result.add(
          new CharacterLoadout.GemOption(
              header.group(1), Integer.parseInt(header.group(2)), effect, firstDecimal(effect)));
    }
    return result;
  }

  private record Part(String title, String body) {}

  private static List<Part> parts(String tooltip) {
    var root = root(tooltip);
    return root == null ? null : parts(root);
  }

  private static List<Part> parts(JsonNode root) {
    var result = new ArrayList<Part>();
    for (var element : root.values()) {
      if (!"ItemPartBox".equals(text(element.path("type")))) continue;
      var value = element.path("value");
      var title = LostArkText.plain(text(value.path("Element_000")));
      var body = LostArkText.plain(text(value.path("Element_001")));
      if (title != null && body != null) result.add(new Part(title.strip(), body));
    }
    return result;
  }

  private static JsonNode root(String tooltip) {
    if (tooltip == null || tooltip.isBlank()) return null;
    try {
      var root = MAPPER.readTree(tooltip);
      return root != null && root.isObject() ? root : null;
    } catch (JacksonException exception) {
      return null;
    }
  }

  private static Integer firstInt(Pattern pattern, String text) {
    if (text == null) return null;
    var matcher = pattern.matcher(text);
    return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
  }

  private static BigDecimal firstDecimal(String text) {
    if (text == null) return null;
    var matcher = PERCENT.matcher(text);
    return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : "";
  }
}
