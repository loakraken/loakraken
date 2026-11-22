package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.domain.OfficialTextLimit;
import org.junit.jupiter.api.Test;

/**
 * 공식 글 길이 상한(보안 11-20 (a)): 장비 툴팁의 아이콘·글자색 패턴과 표시 글 정리는 상한을 넘는 칸을 읽지 않는다(깨진 응답). 상한 안의 같은 툴팁은 그대로
 * 읽는다.
 */
class OfficialTextCapTest {
  private static final String TOOLTIP =
      "{\"Element_000\":{\"type\":\"NameTagBox\",\"value\":\"+19 테스트 머리장식\"},"
          + "\"Element_001\":{\"type\":\"ItemTitle\",\"value\":{\"leftStr2\":"
          + "\"<FONT SIZE='14'>아이템 레벨 1770 (티어 4)</FONT>\",\"qualityValue\":95}}PAD}";

  private static String tooltip(int padding) {
    var pad =
        padding == 0
            ? ""
            : ",\"Element_002\":{\"type\":\"SingleTextBox\",\"value\":\""
                + "<img".repeat(padding / 4)
                + "\"}";
    return TOOLTIP.replace("PAD", pad);
  }

  @Test
  void 상한을_넘는_장비_툴팁은_읽지_않는다() {
    var within = LostArkEquipmentTooltip.parse("+19 테스트 머리장식", tooltip(0));
    assertThat(within).isNotNull();
    assertThat(within.itemLevel()).isEqualTo(1770);
    var padded = tooltip(OfficialTextLimit.MAX_CHARS);
    assertThat(padded.length()).isGreaterThan(OfficialTextLimit.MAX_CHARS);
    assertThat(LostArkEquipmentTooltip.parse("+19 테스트 머리장식", padded)).isNull();
  }

  @Test
  void 아이콘과_글자색_패턴은_꺾쇠를_넘지_않는다() {
    var tooltip = "<img src='x' width='16'></img>값 <FONT COLOR='#FE9600'>상</FONT>";
    assertThat(LostArkEquipmentTooltip.ICON.matcher(tooltip).replaceAll("")).startsWith("값 ");
    var color = LostArkEquipmentTooltip.FONT_COLOR.matcher(tooltip);
    assertThat(color.find()).isTrue();
    assertThat(color.group(1)).isEqualTo("FE9600");
    // 닫는 '>' 전에 '<'가 오면 태그로 보지 않는다(예전 패턴은 '<'를 넘어 다음 '>'까지 읽어, '<'가 많은 긴 줄에서 길이의 제곱으로 느려졌다).
    assertThat(LostArkEquipmentTooltip.ICON.matcher("<img a<b>").find()).isFalse();
    assertThat(LostArkEquipmentTooltip.FONT_COLOR.matcher("<font x<b color='#FE9600'>").find())
        .isFalse();
  }

  @Test
  void 저장된_실제_응답에서는_예전_패턴과_결과가_같다() throws Exception {
    var oldIcon = java.util.regex.Pattern.compile("(?i)<img[^>]*>(\\s*</img>)?");
    var oldColor = java.util.regex.Pattern.compile("(?i)<font[^>]*color=['\"]?#?([0-9a-f]{6})");
    var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
    int checked = 0;
    try (var files =
        java.nio.file.Files.list(java.nio.file.Path.of("src/test/resources/kraken-score"))) {
      for (var file : files.filter(path -> path.toString().endsWith(".json")).toList())
        for (var text : strings(mapper.readTree(file.toFile()), new java.util.ArrayList<>())) {
          if (text.indexOf('<') < 0) continue;
          checked++;
          assertThat(LostArkEquipmentTooltip.ICON.matcher(text).replaceAll(""))
              .isEqualTo(oldIcon.matcher(text).replaceAll(""));
          assertThat(colors(LostArkEquipmentTooltip.FONT_COLOR, text))
              .isEqualTo(colors(oldColor, text));
        }
    }
    assertThat(checked).isGreaterThan(100);
  }

  private static java.util.List<String> colors(java.util.regex.Pattern pattern, String text) {
    var found = new java.util.ArrayList<String>();
    var matcher = pattern.matcher(text);
    while (matcher.find()) found.add(matcher.group(1));
    return found;
  }

  /** 문자열 값 전부(툴팁 안 JSON 글도 문자열 하나로). */
  private static java.util.List<String> strings(
      tools.jackson.databind.JsonNode node, java.util.List<String> out) {
    if (node.isString()) {
      var text = node.asString();
      out.add(text);
      if (text.startsWith("{"))
        try {
          strings(tools.jackson.databind.json.JsonMapper.builder().build().readTree(text), out);
        } catch (tools.jackson.core.JacksonException ignored) {
          // 툴팁이 아닌 글
        }
    }
    for (var child : node.values()) strings(child, out);
    return out;
  }

  @Test
  void 상한을_넘는_표시_글은_빈_값이다() {
    assertThat(LostArkText.plain("<".repeat(OfficialTextLimit.MAX_CHARS + 1))).isEmpty();
    var atLimit = "가".repeat(OfficialTextLimit.MAX_CHARS);
    assertThat(LostArkText.plain(atLimit)).isEqualTo(atLimit);
    assertThat(LostArkText.plain((String) null)).isNull();
  }
}
