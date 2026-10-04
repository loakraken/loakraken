package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LostArkTextTest {
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "<FONT COLOR='#99ff99'>27.00%</FONT> 증가|27.00% 증가",
        "<P ALIGN='CENTER'><FONT COLOR='#F99200'>6레벨 보석</FONT></P>|6레벨 보석",
        "<TEXTFORMAT LEADING='4'><FONT SIZE='12'>설명</FONT></TEXTFORMAT>|설명",
        "<img src='emoticon' width='18'></img>칭호|칭호",
        "<font color='#83E9FF'>소문자</font> 태그|소문자 태그",
        "일반 텍스트|일반 텍스트",
        "피해 < 50% 조건|피해 < 50% 조건",
        "&lt;FONT&gt; &amp;lt; &quot;인용&quot; &#39;작은따옴표&#39;|<FONT> &lt; \"인용\" '작은따옴표'",
      })
  void 표시용_마크업을_제거하고_텍스트와_엔티티는_보존한다(String source, String expected) {
    assertThat(LostArkText.plain(source)).isEqualTo(expected);
  }

  @Test
  void 줄바꿈_태그는_줄바꿈으로_바꾸고_줄마다_공백을_정리한다() {
    assertThat(LostArkText.plain(" 첫 줄 <BR>둘째 줄<br/> 셋째 줄 <Br />")).isEqualTo("첫 줄\n둘째 줄\n셋째 줄");
    assertThat(LostArkText.plain("윈도우\r\n줄바꿈")).isEqualTo("윈도우\n줄바꿈");
  }

  @Test
  void null과_마크업만_있는_값은_구분한다() {
    assertThat(LostArkText.plain((String) null)).isNull();
    assertThat(LostArkText.plain("<FONT COLOR='#FFFFFF'></FONT>")).isEmpty();
  }

  @Test
  void 목록은_null_항목을_제외하고_각_항목을_정제한다() {
    assertThat(LostArkText.plain(Arrays.asList("<FONT>효과1</FONT>", null, "효과2")))
        .containsExactly("효과1", "효과2");
    assertThat(LostArkText.plain((java.util.List<String>) null)).isEmpty();
  }
}
