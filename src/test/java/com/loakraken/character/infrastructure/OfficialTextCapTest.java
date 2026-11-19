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
  void 상한을_넘는_표시_글은_빈_값이다() {
    assertThat(LostArkText.plain("<".repeat(OfficialTextLimit.MAX_CHARS + 1))).isEmpty();
    var atLimit = "가".repeat(OfficialTextLimit.MAX_CHARS);
    assertThat(LostArkText.plain(atLimit)).isEqualTo(atLimit);
    assertThat(LostArkText.plain((String) null)).isNull();
  }
}
