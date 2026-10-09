package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class LostArkSkillTooltip {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern PART_BREAK = Pattern.compile("부위 파괴 : 레벨 (\\d{1,2})");
  private static final Pattern COUNTER = Pattern.compile("카운터 : 가능");
  private static final Pattern ATTACK_TYPE = Pattern.compile("공격 타입 : (백 어택|헤드 어택)");
  private static final Pattern GEM_DAMAGE = Pattern.compile("피해 [0-9.]+% 증가$");
  private static final Pattern GEM_COOLDOWN = Pattern.compile("재사용 대기시간 [0-9.]+% 감소$");
  private static final String GEM_TITLE = "보석 효과";

  private LostArkSkillTooltip() {}

  /** attackType은 Tooltip을 해석했는데 공격 타입 줄이 없으면 NONE, 해석할 수 없으면 null. */
  record Parsed(
      Integer partBreakLevel,
      Boolean counter,
      List<CharacterLoadout.SkillGemEffect> gemEffects,
      CharacterLoadout.AttackType attackType) {
    static final Parsed UNKNOWN = new Parsed(null, null, List.of(), null);
  }

  static Parsed parse(String tooltip) {
    if (tooltip == null || tooltip.isBlank()) return Parsed.UNKNOWN;
    JsonNode root;
    try {
      root = MAPPER.readTree(tooltip);
    } catch (JacksonException exception) {
      return Parsed.UNKNOWN;
    }
    if (root == null || !root.isObject()) return Parsed.UNKNOWN;
    Integer partBreak = null;
    boolean counter = false;
    var attackType = CharacterLoadout.AttackType.NONE;
    var gems = new ArrayList<CharacterLoadout.SkillGemEffect>();
    for (var element : root.values()) {
      var value = element.path("value");
      if ("ItemPartBox".equals(text(element.path("type")))) {
        if (GEM_TITLE.equals(LostArkText.plain(text(value.path("Element_000")))))
          for (var line : LostArkText.plain(text(value.path("Element_001"))).lines().toList())
            if (!line.isBlank()) gems.add(new CharacterLoadout.SkillGemEffect(line, kind(line)));
        continue;
      }
      for (var raw : strings(value)) {
        String plain = LostArkText.plain(raw);
        var matcher = PART_BREAK.matcher(plain);
        if (partBreak == null && matcher.find()) partBreak = Integer.parseInt(matcher.group(1));
        if (COUNTER.matcher(plain).find()) counter = true;
        var attack = ATTACK_TYPE.matcher(plain);
        if (attack.find())
          attackType =
              "백 어택".equals(attack.group(1))
                  ? CharacterLoadout.AttackType.BACK_ATTACK
                  : CharacterLoadout.AttackType.HEAD_ATTACK;
      }
    }
    return new Parsed(partBreak, counter, gems, attackType);
  }

  /** 아크 패시브 노드 Tooltip의 MultiTextBox 효과 문구를 이어 붙인다. 없거나 해석할 수 없으면 null. */
  static String passiveEffect(String tooltip) {
    if (tooltip == null || tooltip.isBlank()) return null;
    JsonNode root;
    try {
      root = MAPPER.readTree(tooltip);
    } catch (JacksonException exception) {
      return null;
    }
    if (root == null || !root.isObject()) return null;
    var parts = new ArrayList<String>();
    for (var element : root.values()) {
      if (!"MultiTextBox".equals(text(element.path("type")))) continue;
      String plain = LostArkText.plain(text(element.path("value")));
      if (plain == null) continue;
      for (var part : plain.split("\\|\\|"))
        if (!part.isBlank()) parts.add(part.strip().replaceAll("\\s+", " "));
    }
    return parts.isEmpty() ? null : String.join(" ", parts);
  }

  private static CharacterLoadout.GemEffectKind kind(String line) {
    if (GEM_DAMAGE.matcher(line).find()) return CharacterLoadout.GemEffectKind.DAMAGE;
    if (GEM_COOLDOWN.matcher(line).find()) return CharacterLoadout.GemEffectKind.COOLDOWN;
    return null;
  }

  private static List<String> strings(JsonNode node) {
    if (node.isString()) return List.of(node.stringValue());
    var result = new ArrayList<String>();
    for (var child : node.values()) result.addAll(strings(child));
    return result;
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : "";
  }
}
