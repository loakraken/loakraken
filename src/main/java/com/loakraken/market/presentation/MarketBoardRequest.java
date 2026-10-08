package com.loakraken.market.presentation;

import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketTab;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;

public record MarketBoardRequest(
    @NotNull @Schema(description = "보드 탭(GET /api/v1/markets/tabs)", example = "REFINING")
        MarketTab tab,
    @Min(1) @Max(4) @Schema(description = "아이템 티어. 생략하면 탭 기본 티어") Integer tier,
    @Pattern(regexp = "default|high|low|rise|fall")
        @Schema(
            description = "default(공식 등급순), high/low(현재 최저가), rise/fall(전일 대비 등락률, 탭 전체 스캔)",
            defaultValue = "default")
        String sort,
    @Min(1)
        @Max(MarketBoardService.MAX_SCAN_PAGES)
        @Schema(description = "10개 단위 페이지", defaultValue = "1")
        Integer page) {
  public MarketBoardRules.Sort toSort() {
    return sort == null
        ? MarketBoardRules.Sort.DEFAULT
        : MarketBoardRules.Sort.valueOf(sort.toUpperCase(Locale.ROOT));
  }

  public int pageOrDefault() {
    return page == null ? 1 : page;
  }
}
