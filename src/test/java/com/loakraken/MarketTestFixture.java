package com.loakraken;

import com.loakraken.market.application.MarketSearch;
import com.loakraken.market.domain.MarketOptions;
import java.util.List;

public final class MarketTestFixture {
  private MarketTestFixture() {}

  public static MarketSearch search() {
    return new MarketSearch(
        50000,
        "",
        null,
        "",
        "검증재료",
        1,
        MarketSearch.Sort.RECENT_PRICE,
        MarketSearch.SortCondition.ASC);
  }

  public static MarketOptions options() {
    return new MarketOptions(
        List.of(
            new MarketOptions.Category(
                50000, "재료", List.of(new MarketOptions.Subcategory(50010, "강화 재료"))),
            new MarketOptions.Category(60000, "소모품", List.of()),
            new MarketOptions.Category(70000, "기타", null)),
        List.of("일반", "희귀"),
        List.of(3, 4),
        List.of("바드"));
  }
}
