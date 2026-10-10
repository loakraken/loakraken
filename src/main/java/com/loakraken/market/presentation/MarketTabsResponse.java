package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.domain.MarketTab;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.List;

@Schema(description = "시세 보드 탭 정의. 보석·장신구는 경매장 대상이라 포함하지 않습니다.")
public record MarketTabsResponse(List<MarketTabResponse> tabs, List<String> sorts) {
  static MarketTabsResponse create() {
    return new MarketTabsResponse(
        Arrays.stream(MarketTab.values())
            .map(
                tab ->
                    new MarketTabResponse(tab, tab.label(), tab.categoryCode(), tab.defaultTier()))
            .toList(),
        List.of("default", "high", "low", "rise", "fall"));
  }

  public record MarketTabResponse(
      MarketTab tab,
      String label,
      @Schema(description = "공식 거래소 카테고리 코드") int categoryCode,
      @Schema(description = "기본 티어. null이면 티어 조건 없음") @NullableResponse Integer defaultTier) {}
}
