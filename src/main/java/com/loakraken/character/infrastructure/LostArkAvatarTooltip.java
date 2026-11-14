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

  /**
   * 덧입기 표시 줄. 겉 아바타를 덧입기 칸에 입으면 공식 툴팁의 SingleTextBox에 이 문구가 두 줄(BR)로 붙고, 그 아래 능력은 적용되지 않는다(공식 가이드
   * https://lostark.game.onstove.com/GameGuide/Pages/요즈의%20항아리 '아바타 덧입기', 2026-11-15 확인 · 저장된 원문
   * 2026-10-18). 줄을 이어 붙인 문구가 정확히 같을 때만 맞는다. 문구가 바뀌면 덧입기로 보지 않고 세므로(예전 '칸마다 큰 값' 규칙) 덜 세는 쪽으로 틀리지
   * 않는다.
   */
  static final String OVERLAY_MARKER = "아래 능력들이 적용되지 않는 상태입니다.(덧입기 슬롯에 착용 중)";

  private LostArkAvatarTooltip() {}

  /** 덧입기 표시 줄이 있으면 true, 없으면 false, 원문이 없거나 읽지 못하면 null. */
  static Boolean effectsDisabled(String tooltip) {
    if (tooltip == null || tooltip.isBlank()) return null;
    JsonNode root;
    try {
      root = MAPPER.readTree(tooltip);
    } catch (JacksonException exception) {
      return null;
    }
    if (root == null || !root.isObject()) return null;
    for (var element : root.values()) {
      if (!"SingleTextBox".equals(text(element.path("type")))) continue;
      var plain = LostArkText.plain(text(element.path("value")));
      if (plain != null && OVERLAY_MARKER.equals(plain.replace("\n", ""))) return true;
    }
    return false;
  }

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
