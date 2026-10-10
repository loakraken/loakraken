package com.loakraken.market.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketBoardRulesTest {
  @Test
  void 등락률은_현재_최저가와_기준가로_소수_첫째_자리까지_계산하고_기준이_없으면_null이다() {
    assertThat(MarketBoardRules.change(dec("259"), dec("256.0"))).isEqualByComparingTo("1.2");
    assertThat(MarketBoardRules.change(dec("6"), dec("4.7"))).isEqualByComparingTo("27.7");
    assertThat(MarketBoardRules.change(dec("20"), dec("38.1"))).isEqualByComparingTo("-47.5");
    assertThat(MarketBoardRules.change(dec("22"), dec("0.0"))).isNull();
    assertThat(MarketBoardRules.change(dec("22"), null)).isNull();
    assertThat(MarketBoardRules.change(null, dec("10"))).isNull();
  }

  @Test
  void 상승과_하락_정렬은_등락률이_없는_품목을_맨_뒤에_두고_같으면_이름순이다() {
    var items =
        List.of(
            price(1, "다", "10", "10"),
            price(2, "가", "12", "10"),
            price(3, "나", "8", "10"),
            price(4, "라", "5", "0.0"),
            price(5, "마", "12", "10"));
    assertThat(MarketBoardRules.sortByChange(items, MarketBoardRules.Sort.RISE))
        .extracting(MarketPrice::id)
        .containsExactly(2L, 5L, 1L, 3L, 4L);
    assertThat(MarketBoardRules.sortByChange(items, MarketBoardRules.Sort.FALL))
        .extracting(MarketPrice::id)
        .containsExactly(3L, 1L, 2L, 5L, 4L);
    assertThatThrownBy(() -> MarketBoardRules.sortByChange(items, MarketBoardRules.Sort.HIGH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static MarketPrice price(long id, String name, String current, String yesterday) {
    return new MarketPrice(
        id, name, "일반", null, 1, null, dec(yesterday), null, current == null ? null : dec(current));
  }

  private static BigDecimal dec(String value) {
    return new BigDecimal(value);
  }
}
