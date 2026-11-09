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
            // TODO: 장비 상자(10100)는 공식 분류 코드가 맞지만(11-09 공식 options) 매물이 0이라 탭 목록에서 뺀다.
            // 보드 조회는 그대로 받는다. 이 분류에 매물이 생기면 이 줄을 지운다.
            .filter(tab -> tab != MarketTab.EQUIPMENT_BOX)
            .map(
                tab ->
                    new MarketTabResponse(tab, tab.label(), tab.categoryCode(), tab.defaultTier()))
            .toList(),
        List.of("default", "high", "low", "rise", "fall", "volume"));
  }

  public record MarketTabResponse(
      MarketTab tab,
      String label,
      @Schema(description = "공식 거래소 카테고리 코드") int categoryCode,
      @Schema(description = "기본 티어. null이면 티어 조건 없음") @NullableResponse Integer defaultTier) {}
}
