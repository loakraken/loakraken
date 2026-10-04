package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MarketPriceResponse(
    @Schema(description = "공식 API의 목록 유형. ACTIVE_LISTING도 개별 매물 목록이 아닌 상품별 가격 요약입니다.")
        MarketSource source,
    @Schema(description = "공식 API에서 읽은 시각. 거래 체결 시각이나 가격 갱신 시각이 아닙니다.") Instant observedAt,
    @NullableResponse @Schema(description = "공식 API가 집계 시작 시각을 제공하지 않아 null") Instant windowFrom,
    @NullableResponse @Schema(description = "공식 API가 집계 종료 시각을 제공하지 않아 null") Instant windowTo,
    MarketSearchCriteria search,
    int pageNo,
    int pageSize,
    int totalCount,
    List<MarketPriceItem> items) {
  public static MarketPriceResponse from(MarketResult result) {
    var search = result.search();
    var page = result.page();
    return new MarketPriceResponse(
        result.source(),
        result.observedAt(),
        null,
        null,
        new MarketSearchCriteria(
            search.categoryCode(),
            search.characterClass(),
            search.itemTier(),
            search.itemGrade(),
            search.itemName(),
            search.pageNo(),
            search.sort(),
            search.sortCondition()),
        page.pageNo(),
        page.pageSize(),
        page.totalCount(),
        page.items().stream()
            .map(
                item ->
                    new MarketPriceItem(
                        item.id(),
                        item.name(),
                        item.grade(),
                        item.icon(),
                        item.bundleCount(),
                        item.tradeRemainCount(),
                        item.yDayAvgPrice(),
                        item.recentPrice(),
                        item.currentMinPrice()))
            .toList());
  }

  public record MarketSearchCriteria(
      int categoryCode,
      String characterClass,
      @NullableResponse Integer itemTier,
      String itemGrade,
      String itemName,
      int pageNo,
      MarketSearch.Sort sort,
      MarketSearch.SortCondition sortCondition) {}

  public record MarketPriceItem(
      long id,
      String name,
      @NullableResponse String grade,
      @NullableResponse String icon,
      @Schema(description = "공식 가격의 판매 묶음 수량. 개당 가격으로 자동 환산하지 않습니다.") int bundleCount,
      @NullableResponse Integer tradeRemainCount,
      @NullableResponse @Schema(description = "공식 전일 평균 가격. 미제공은 null, 0과 구분합니다.")
          BigDecimal yDayAvgPrice,
      @NullableResponse @Schema(description = "공식 최근 거래 가격. 현재 구매 가능한 가격을 뜻하지 않습니다.")
          BigDecimal recentPrice,
      @NullableResponse @Schema(description = "현재 판매 목록의 최저 가격. 최근 거래 목록에서는 항상 null입니다.")
          BigDecimal currentMinPrice) {}
}
