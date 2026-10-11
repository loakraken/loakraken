package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아이템 hover 툴팁용 공식 Tooltip 줄(PM-프론트·PM-백엔드 교차 확인 10-10 11:20). 공식 원문만 줄 단위로 담고 추정하지 않는다.
 *
 * <ul>
 *   <li>titleLine: ItemTitle의 등급·부위 줄(leftStr0, 예 "고대 머리 방어구")
 *   <li>tradeLines: ItemTitle 바로 뒤 SingleTextBox(귀속·거래 제한)와 이어지는 MultiTextBox(거래 가능 여부)의 줄
 *   <li>refineExp: "재련 경험치" Progress의 현재·최대
 *   <li>effectLines: 제목이 effectTitle인 ItemPartBox의 본문 줄(룬 "스킬 룬 효과")
 *   <li>extraLines: 위와 다른 해석기(효과 묶음·연마·팔찌·카드 등)가 담지 않은 나머지 텍스트 줄을 원문 순서대로(내구도, 분해불가, 설명, 획득처 등)
 *   <li>extraLineSections: extraLines와 같은 길이로, 각 줄 바로 앞에 나온 섹션 제목(ItemPartBox·IndentStringGroup 제목,
 *       예 "팔찌 효과"). 앞에 섹션이 없으면 빈 문자열(#285)
 *   <li>itemLevel·tier: ItemTitle의 "아이템 레벨 n (티어 m)"(#285, 보석 툴팁)
 * </ul>
 */
final class LostArkTooltipLines {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String REFINE_EXP = "재련 경험치";

  /** "[상급 재련] 28단계"(계산 검증 10-10 18:10: 1단계 이상일 때만 SingleTextBox에 나오고 0단계면 문구가 없다). */
  private static final Pattern ADVANCED_REFINE = Pattern.compile("\\[상급 재련\\]\\s*(\\d{1,3})\\s*단계");

  private static final Pattern ITEM_LEVEL = Pattern.compile("아이템 레벨 (\\d{1,4})");
  private static final Pattern TIER = Pattern.compile("티어 (\\d)");

  record Lines(
      String titleLine,
      List<String> tradeLines,
      CharacterLoadout.RefineExp refineExp,
      List<String> effectLines,
      List<String> extraLines,
      Integer advancedRefineLevel,
      List<String> extraLineSections,
      Integer itemLevel,
      Integer tier) {
    static final Lines EMPTY =
        new Lines(null, List.of(), null, List.of(), List.of(), null, List.of(), null, null);
  }

  private LostArkTooltipLines() {}

  static Lines parse(String tooltip, String effectTitle) {
    if (tooltip == null || tooltip.isBlank()) return Lines.EMPTY;
    JsonNode root;
    try {
      root = MAPPER.readTree(tooltip);
    } catch (JacksonException exception) {
      return Lines.EMPTY;
    }
    if (root == null || !root.isObject()) return Lines.EMPTY;
    String titleLine = null;
    var trade = new ArrayList<String>();
    var effect = new ArrayList<String>();
    var extra = new ArrayList<String>();
    var sections = new ArrayList<String>();
    String section = "";
    Integer itemLevel = null;
    Integer tier = null;
    CharacterLoadout.RefineExp refineExp = null;
    Integer advancedRefine = null;
    // ItemTitle 바로 뒤 SingleTextBox·MultiTextBox까지만 귀속·거래 줄로 본다.
    int afterTitle = -1;
    for (var element : root.values()) {
      if (element == null || element.isNull()) continue;
      String type = text(element.path("type"));
      var value = element.path("value");
      if (afterTitle >= 0) afterTitle++;
      switch (type) {
        case "ItemTitle" -> {
          if (titleLine == null) {
            String line = LostArkText.plain(text(value.path("leftStr0")));
            titleLine = line == null || line.isEmpty() ? null : line;
            afterTitle = 0;
            String levelText = LostArkText.plain(text(value.path("leftStr2")));
            itemLevel = number(ITEM_LEVEL, levelText);
            tier = number(TIER, levelText);
          }
        }
        case "SingleTextBox", "MultiTextBox", "ShowMeTheMoney" -> {
          var lines = lines(text(value));
          var advanced = ADVANCED_REFINE.matcher(String.join(" ", lines));
          if (advancedRefine == null && advanced.find()) {
            advancedRefine = Integer.parseInt(advanced.group(1));
            continue;
          }
          if (afterTitle >= 1 && afterTitle <= 2 && tradeSlot(type, afterTitle))
            trade.addAll(lines);
          else {
            extra.addAll(lines);
            for (int i = 0; i < lines.size(); i++) sections.add(section);
          }
        }
        case "Progress" -> {
          String title = LostArkText.plain(text(value.path("title")));
          if (title != null && title.contains(REFINE_EXP) && value.path("maximum").isNumber())
            refineExp =
                new CharacterLoadout.RefineExp(
                    value.path("value").isNumber() ? value.path("value").longValue() : null,
                    value.path("maximum").longValue());
        }
        case "ItemPartBox" -> {
          String title = LostArkText.plain(text(value.path("Element_000")));
          if (title != null && !title.isEmpty()) section = title;
          if (effectTitle != null && effectTitle.equals(title))
            effect.addAll(lines(text(value.path("Element_001"))));
        }
        case "IndentStringGroup" -> {
          String title = LostArkText.plain(text(value.path("Element_000").path("topStr")));
          if (title != null && !title.isEmpty()) section = title;
        }
        default -> {}
      }
    }
    return new Lines(
        titleLine,
        List.copyOf(trade),
        refineExp,
        List.copyOf(effect),
        List.copyOf(extra),
        advancedRefine,
        List.copyOf(sections),
        itemLevel,
        tier);
  }

  private static Integer number(Pattern pattern, String text) {
    if (text == null) return null;
    var matcher = pattern.matcher(text);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  /** ItemTitle 다음 첫 요소는 SingleTextBox(귀속·거래 제한), 둘째 요소는 MultiTextBox(거래 가능 여부)일 때만 귀속·거래 줄이다. */
  private static boolean tradeSlot(String type, int afterTitle) {
    return afterTitle == 1 ? type.equals("SingleTextBox") : type.equals("MultiTextBox");
  }

  /** "|"로 나뉜 칸과 줄바꿈을 줄로 나누고 빈 줄은 뺀다. */
  private static List<String> lines(String raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    var result = new ArrayList<String>();
    for (var cell : raw.split("\\|")) {
      String plain = LostArkText.plain(cell);
      if (plain == null) continue;
      plain.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(result::add);
    }
    return result;
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : "";
  }
}
