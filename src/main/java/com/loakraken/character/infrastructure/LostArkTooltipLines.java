package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import java.util.List;
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
 * </ul>
 */
final class LostArkTooltipLines {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String REFINE_EXP = "재련 경험치";

  record Lines(
      String titleLine,
      List<String> tradeLines,
      CharacterLoadout.RefineExp refineExp,
      List<String> effectLines,
      List<String> extraLines) {
    static final Lines EMPTY = new Lines(null, List.of(), null, List.of(), List.of());
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
    CharacterLoadout.RefineExp refineExp = null;
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
          }
        }
        case "SingleTextBox", "MultiTextBox", "ShowMeTheMoney" -> {
          var lines = lines(text(value));
          if (afterTitle >= 1 && afterTitle <= 2 && tradeSlot(type, afterTitle))
            trade.addAll(lines);
          else extra.addAll(lines);
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
          if (effectTitle != null
              && effectTitle.equals(LostArkText.plain(text(value.path("Element_000")))))
            effect.addAll(lines(text(value.path("Element_001"))));
        }
        default -> {}
      }
    }
    return new Lines(
        titleLine, List.copyOf(trade), refineExp, List.copyOf(effect), List.copyOf(extra));
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
