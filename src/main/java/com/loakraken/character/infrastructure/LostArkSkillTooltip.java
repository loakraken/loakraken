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
  private static final Pattern ATTACK_TYPE = Pattern.compile("공격 타입\s*:\s*(백\s*어택|헤드\s*어택)");
  private static final Pattern ATTACK_TYPE_LABEL = Pattern.compile("공격 타입\s*:");
  private static final Pattern GEM_DAMAGE = Pattern.compile("피해 [0-9.]+% 증가$");
  private static final Pattern GEM_COOLDOWN = Pattern.compile("재사용 대기시간 [0-9.]+% 감소$");
  private static final String GEM_TITLE = "보석 효과";
  private static final Pattern CATEGORY = Pattern.compile("\\s*\\[([^\\]]+)\\]\\s*");

  private LostArkSkillTooltip() {}

  /**
   * attackType은 Tooltip을 해석했는데 공격 타입 줄이 없으면 NONE이다. Tooltip을 해석할 수 없거나 "공격 타입" 줄은 있는데 값을 읽지 못하면
   * null이다(추정 금지).
   */
  record Parsed(
      Integer partBreakLevel,
      Boolean counter,
      List<CharacterLoadout.SkillGemEffect> gemEffects,
      CharacterLoadout.AttackType attackType,
      String category,
      List<String> titleLines,
      List<String> infoLines) {
    static final Parsed UNKNOWN =
        new Parsed(null, null, List.of(), null, null, List.of(), List.of());
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
    CharacterLoadout.AttackType attackType = null;
    boolean attackLabel = false;
    var gems = new ArrayList<CharacterLoadout.SkillGemEffect>();
    String category = null;
    var titleLines = new ArrayList<String>();
    var infoLines = new ArrayList<String>();
    // 공식 Tooltip은 현재 레벨 정보 뒤 BlinkLineStart부터 다음 레벨 정보를 둔다. 정보 줄은 그 앞의 글 상자만 원문 순서로 모은다.
    boolean currentLevel = true;
    for (var element : root.values()) {
      var value = element.path("value");
      var type = text(element.path("type"));
      if ("BlinkLineStart".equals(type)) currentLevel = false;
      if (currentLevel && ("SingleTextBox".equals(type) || "MultiTextBox".equals(type)))
        infoLines.addAll(lines(text(value)));
      if ("CommonSkillTitle".equals(type) && titleLines.isEmpty())
        // 아이콘 옆 줄: 스킬 종류(일반·콤보·차지 등), 재사용 대기시간, 가운데 글, 분류("[일반 스킬]") 순서.
        for (var field : List.of("name", "leftText", "middleText", "level"))
          titleLines.addAll(lines(text(value.path(field))));
      if ("CommonSkillTitle".equals(type)) {
        // 스킬 분류: "[해방 스킬]", "[둔갑 스킬]", "[일반 스킬]"(공식 Tooltip 제목, 10-10 확인)
        var level = CATEGORY.matcher(LostArkText.plain(text(value.path("level"))));
        if (category == null && level.matches()) category = level.group(1).strip();
      }
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
        if (ATTACK_TYPE_LABEL.matcher(plain).find()) attackLabel = true;
        var attack = ATTACK_TYPE.matcher(plain);
        if (attackType == null && attack.find())
          attackType =
              attack.group(1).startsWith("백")
                  ? CharacterLoadout.AttackType.BACK_ATTACK
                  : CharacterLoadout.AttackType.HEAD_ATTACK;
      }
    }
    if (attackType == null && !attackLabel) attackType = CharacterLoadout.AttackType.NONE;
    return new Parsed(
        partBreak,
        counter,
        gems,
        attackType,
        category,
        List.copyOf(titleLines),
        List.copyOf(infoLines));
  }

  /** 태그를 지운 원문을 줄(BR)과 좌우 칸(|)으로 나눠 빈 줄을 뺀다. */
  private static List<String> lines(String raw) {
    var result = new ArrayList<String>();
    if (raw == null || raw.isBlank()) return result;
    for (var line : LostArkText.plain(raw).lines().toList())
      for (var cell : line.split("\\|")) if (!cell.isBlank()) result.add(cell.strip());
    return result;
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
