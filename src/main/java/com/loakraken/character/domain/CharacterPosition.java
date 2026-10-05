package com.loakraken.character.domain;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 아크패시브 깨달음 노드로 딜러·서포터를 판정한다. 근거가 없으면 role은 null이다. */
public record CharacterPosition(Role role, String evidence) {
  public static final String RULE_VERSION = "position-v1";
  private static final Map<String, String> SUPPORT_NODES =
      Map.of("바드", "절실한 구원", "홀리나이트", "축복의 오라", "도화가", "만개", "발키리", "해방자");
  private static final Map<String, String> DEALER_NODES =
      Map.of("바드", "진실된 용맹", "홀리나이트", "심판자", "도화가", "회귀", "발키리", "빛의 기사");
  private static final Pattern NODE = Pattern.compile("^깨달음 \\d+티어 (.+?) Lv\\.\\d+$");
  private static final CharacterPosition UNKNOWN = new CharacterPosition(null, null);

  public enum Role {
    DEALER,
    SUPPORT
  }

  public static CharacterPosition judge(String className, CharacterLoadout.ArkPassive arkPassive) {
    if (className == null || className.isBlank()) return UNKNOWN;
    if (!SUPPORT_NODES.containsKey(className)) return new CharacterPosition(Role.DEALER, className);
    if (arkPassive == null) return UNKNOWN;
    var nodes =
        arkPassive.effects().stream()
            .map(effect -> effect.description())
            .filter(Objects::nonNull)
            .map(description -> NODE.matcher(description.strip()))
            .filter(matcher -> matcher.matches())
            .map(matcher -> matcher.group(1))
            .toList();
    if (nodes.contains(SUPPORT_NODES.get(className)))
      return new CharacterPosition(Role.SUPPORT, SUPPORT_NODES.get(className));
    if (nodes.contains(DEALER_NODES.get(className)))
      return new CharacterPosition(Role.DEALER, DEALER_NODES.get(className));
    return UNKNOWN;
  }
}
