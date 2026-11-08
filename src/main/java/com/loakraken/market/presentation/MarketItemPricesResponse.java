package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.MarketBoardService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MarketItemPricesResponse(
    @Schema(description = "요청 순서대로, 같은 itemId는 처음 것만") List<MarketItemPriceResponse> items) {
  static MarketItemPricesResponse from(List<MarketBoardService.ItemPrice> prices) {
    return new MarketItemPricesResponse(
        prices.stream().map(MarketItemPriceResponse::from).toList());
  }

  public record MarketItemPriceResponse(
      @Schema(description = "공식 상품 ID") long itemId,
      @Schema(
              description =
                  "닫힌 목록이 아닌 문자열. 지금 값: READY(값 있음), PENDING(이번에 그 탭 스캔을 하지 못함 — 다음 갱신 때 다시), NOT_FOUND(그 탭 스캔에 없음 — 판매 없음 또는 스캔 상한 100개 밖)",
              example = "READY")
          String status,
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse String grade,
      @NullableResponse Integer bundleCount,
      @NullableResponse Integer tradeRemainCount,
      @NullableResponse BigDecimal currentMinPrice,
      @NullableResponse BigDecimal recentPrice,
      @NullableResponse BigDecimal yesterdayAvgPrice,
      @Schema(description = "전일 평균가 대비 현재 최저가 등락률 %(보드와 같은 계산). 없으면 null") @NullableResponse
          BigDecimal changeDay,
      @Schema(description = "그 탭 스캔 시각(READY·NOT_FOUND). PENDING이면 null") @NullableResponse
          Instant observedAt) {
    static MarketItemPriceResponse from(MarketBoardService.ItemPrice item) {
      var price = item.price();
      return new MarketItemPriceResponse(
          item.itemId(),
          item.status().name(),
          price == null ? null : price.name(),
          price == null ? null : price.icon(),
          price == null ? null : price.grade(),
          price == null ? null : price.bundleCount(),
          price == null ? null : price.tradeRemainCount(),
          price == null ? null : price.currentMinPrice(),
          price == null ? null : price.recentPrice(),
          price == null ? null : price.yDayAvgPrice(),
          item.changeDay(),
          item.observedAt());
    }
  }
}
