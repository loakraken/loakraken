package com.loakraken.market.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketTab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MarketBoardRequestTest {
  private static MarketBoardRequest request(String q) {
    return new MarketBoardRequest(MarketTab.REFINING, null, null, null, q);
  }

  @Test
  void 검색어는_앞뒤_공백을_정리하고_공백뿐이면_검색하지_않는다() {
    assertThat(request(null).query()).isNull();
    assertThat(request("   ").query()).isNull();
    assertThat(request("  운명의   파괴석 ").query()).isEqualTo("운명의 파괴석");
    assertThat(request("   ").isQueryAllowed()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"ㅇㅂㄷ", "파괴석", "Abydos 7", "운명의 파괴석(귀속)", "+1 · 재료:A-B", "가나다라마바사아자차카타파하가나다라마바"})
  void 허용_문자와_20자까지는_받는다(String q) {
    assertThat(request(q).isQueryAllowed()).as(q).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"가나다라마바사아자차카타파하가나다라마바사", "a<b", "파괴석!", "%", "파괴석/", "😀"})
  void 길이_초과나_허용_밖_문자는_거절한다(String q) {
    assertThat(request(q).isQueryAllowed()).as(q).isFalse();
  }

  @Test
  void 초성_변환과_이름_일치() {
    assertThat(MarketBoardRules.initials("운명의 파괴석")).isEqualTo("ㅇㅁㅇ ㅍㄱㅅ");
    assertThat(MarketBoardRules.nameMatches("운명의 파괴석", "ㅍㄱㅅ")).isTrue();
    assertThat(MarketBoardRules.nameMatches("운명의 파괴석", "명의 파")).isTrue();
    assertThat(MarketBoardRules.nameMatches("운명의 파괴석", "명의파")).isTrue();
    // 음절 검색어는 초성으로 바꾸어 비교하지 않는다(엉뚱한 일치 방지).
    assertThat(MarketBoardRules.nameMatches("포근한 고기", "파괴")).isFalse();
    assertThat(MarketBoardRules.nameMatches("운명의 파괴석", "ㅍㄱㅎ")).isFalse();
  }
}
