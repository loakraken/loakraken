package com.loakraken.market.presentation;

import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.domain.MarketTab;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MarketItemPricesRequest(
    @NotNull
        @Size(min = 1, max = MarketBoardService.MAX_PRICE_ITEMS)
        @Schema(description = "즐겨찾기 품목(최대 50개). 탭·티어는 그 품목을 찾을 보드 탭 스캔")
        List<@NotNull @Valid Item> items) {
  public record Item(
      @Positive @Max(999_999_999) @Schema(description = "공식 상품 ID") long itemId,
      @NotNull @Schema(description = "보드 탭(GET /api/v1/markets/tabs)", example = "REFINING")
          MarketTab tab,
      @Min(1)
          @Max(4)
          @Schema(
              description =
                  "아이템 티어. 티어가 있는 탭(REFINING·REFINING_EXTRA, tabs의 defaultTier가 있는 탭)에서만 받고, 생략하면 탭 기본 티어")
          Integer tier) {
    /** 티어가 없는 탭에 티어를 붙이면 같은 품목에 스캔 키만 늘어나므로 400이다. */
    @AssertTrue(message = "tier는 티어가 있는 탭에서만 받습니다.")
    @Schema(hidden = true)
    public boolean isTierAllowed() {
      return tier == null || tab == null || tab.defaultTier() != null;
    }
  }

  List<MarketBoardService.PriceRequest> toRequests() {
    return items.stream()
        .map(item -> new MarketBoardService.PriceRequest(item.itemId(), item.tab(), item.tier()))
        .toList();
  }
}
