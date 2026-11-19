package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 공식 글 길이 상한(보안 11-20 (a)): 태그 떼기 패턴은 닫는 '>' 없는 긴 '<' 줄에서 길이의 제곱으로 느려지므로, 상한을 넘는 칸은 패턴에 넣지 않고 읽지
 * 않는다. 상한 안의 글은 예전 패턴과 같은 결과를 낸다.
 */
class OfficialTextLimitTest {
  private static final Pattern OLD_TAG = Pattern.compile("<[^>]*>");
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void 상한은_실제_가장_긴_칸의_세_배를_넘는다() {
    assertThat(OfficialTextLimit.MAX_CHARS).isGreaterThan(3 * 5_317);
    assertThat(OfficialTextLimit.fits(null)).isTrue();
    assertThat(OfficialTextLimit.fits("a".repeat(OfficialTextLimit.MAX_CHARS))).isTrue();
    assertThat(OfficialTextLimit.fits("a".repeat(OfficialTextLimit.MAX_CHARS + 1))).isFalse();
  }

  @Test
  void 상한을_넘는_칸은_태그를_떼지_않고_빈_값이다() {
    var hostile = "<".repeat(OfficialTextLimit.MAX_CHARS + 1);
    assertThat(KrakenScoreInputMapper.strip(hostile)).isEmpty();
  }

  @Test
  void 상한_안의_글은_예전_패턴과_같게_태그를_뗀다() {
    for (var sample :
        List.of(
            "",
            "일반 텍스트",
            "<FONT COLOR='#99ff99'>27.00%</FONT> 증가",
            "피해 < 50% 조건",
            "a<b",
            "<<b>c",
            "x<y>z<",
            "<br>\n<font color='#fff'>t</font>\n<",
            "티어 4 <img src='x'>",
            "<".repeat(OfficialTextLimit.MAX_CHARS)))
      assertThat(KrakenScoreInputMapper.strip(sample))
          .as(sample.length() > 40 ? "긴 '<' 줄" : sample)
          .isEqualTo(OLD_TAG.matcher(sample).replaceAll(""));
    var random = new Random(20261120L);
    var alphabet = "<>a \n가".toCharArray();
    for (int i = 0; i < 2_000; i++) {
      var text = new StringBuilder();
      for (int n = random.nextInt(40); n > 0; n--)
        text.append(alphabet[random.nextInt(alphabet.length)]);
      var sample = text.toString();
      assertThat(KrakenScoreInputMapper.strip(sample))
          .as(sample)
          .isEqualTo(OLD_TAG.matcher(sample).replaceAll(""));
    }
  }

  @Test
  void 상한을_넘는_장비_툴팁은_읽지_않는다() {
    var bangle = KrakenScoreInputMapper.from(raw(bangleTooltip(0))).path("armory");
    assertThat(bangle.path("accessory").path("bangle").path("tier").asInt()).isEqualTo(4);
    var padded = bangleTooltip(OfficialTextLimit.MAX_CHARS);
    assertThat(padded.length()).isGreaterThan(OfficialTextLimit.MAX_CHARS);
    var over = KrakenScoreInputMapper.from(raw(padded)).path("armory");
    assertThat(over.path("accessory").path("bangle").path("tier").isNull()).isTrue();
  }

  private static String bangleTooltip(int padding) {
    var tooltip = JsonNodeFactory.instance.objectNode();
    tooltip
        .putObject("Element_000")
        .put("type", "ItemTitle")
        .putObject("value")
        .put("leftStr2", "<FONT SIZE='14'>아이템 티어 4</FONT>");
    tooltip.putObject("Element_001").put("type", "SingleTextBox").put("value", "x".repeat(padding));
    return MAPPER.writeValueAsString(tooltip);
  }

  private static tools.jackson.databind.JsonNode raw(String tooltip) {
    var raw = JsonNodeFactory.instance.objectNode();
    raw.putArray("ArmoryEquipment")
        .addObject()
        .put("Type", "팔찌")
        .put("Grade", "고대")
        .put("Tooltip", tooltip);
    return raw;
  }
}
