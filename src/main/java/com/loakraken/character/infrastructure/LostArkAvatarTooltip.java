package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.CharacterLoadout;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아바타 툴팁의 성향 줄. 공식 툴팁은 type이 SymbolString이고 titleStr이 "성향"인 요소에 `&tdc_smart 지성 : 5<br>
 * &tdc_courage 담력 : 10<br>
 * `처럼 담는다. 요소 번호(Element_004~007)는 아바타마다 달라 제목으로 찾는다. 이동 효과·변신 스킨에는 없다(저장된 실제 raw 26명 259벌 중 238벌에
 * 있음, 2026-11-02 확인).
 */
final class LostArkAvatarTooltip {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String TITLE = "성향";
  private static final Pattern TENDENCY = Pattern.compile("(지성|담력|매력|친절)\\s*:\\s*(\\d{1,4})");

  private LostArkAvatarTooltip() {}

  static List<CharacterLoadout.AvatarTendency> tendencies(String tooltip) {
    if (tooltip == null || tooltip.isBlank()) return List.of();
    JsonNode root;
    try {
      root = MAPPER.readTree(tooltip);
    } catch (JacksonException exception) {
      return List.of();
    }
    if (root == null || !root.isObject()) return List.of();
    var result = new ArrayList<CharacterLoadout.AvatarTendency>();
    for (var element : root.values()) {
      if (!"SymbolString".equals(text(element.path("type")))) continue;
      var value = element.path("value");
      if (!TITLE.equals(LostArkText.plain(text(value.path("titleStr"))))) continue;
      var matcher = TENDENCY.matcher(LostArkText.plain(text(value.path("contentStr"))));
      while (matcher.find())
        result.add(
            new CharacterLoadout.AvatarTendency(
                matcher.group(1), Integer.parseInt(matcher.group(2))));
    }
    return result;
  }

  private static String text(JsonNode node) {
    return node.isString() ? node.stringValue() : "";
  }
}
