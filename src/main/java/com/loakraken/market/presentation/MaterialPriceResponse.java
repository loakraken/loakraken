package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.MaterialUnitPriceService;
import com.loakraken.market.domain.MaterialPriceBasis;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "재료 이름별 1개당 골드. 거래소 현재 판매 목록 첫 페이지에서 이름이 정확히 같은 상품만 사용합니다.")
public record MaterialPriceResponse(
    MaterialPriceBasis priceBasis, List<MaterialPriceItemResponse> items) {
  static MaterialPriceResponse from(
      MaterialPriceBasis basis, List<MaterialUnitPriceService.UnitPrice> prices) {
    return new MaterialPriceResponse(
        basis,
        prices.stream()
            .map(
                price ->
                    new MaterialPriceItemResponse(
                        price.name(),
                        price.status(),
                        price.unitPriceGold(),
                        price.sourceItemName(),
                        price.bundleCount(),
                        price.bundlePrice(),
                        price.contentsPerItem(),
                        price.observedAt()))
            .toList());
  }

  public record MaterialPriceItemResponse(
      @Schema(description = "요청한 재료 이름") String name,
      @Schema(
              description =
                  "PRICED: 단가 계산, NOT_FOUND: 같은 이름 상품 없음, PRICE_UNAVAILABLE: 선택 가격이 없거나 0")
          MaterialUnitPriceService.UnitPrice.Status status,
      @Schema(description = "1개당 골드(소수 여섯째 자리). 파편은 가장 싼 주머니 기준 파편 1개당") @NullableResponse
          BigDecimal unitPriceGold,
      @Schema(description = "사용한 거래소 상품 이름") @NullableResponse String sourceItemName,
      @NullableResponse Integer bundleCount,
      @Schema(description = "선택한 기준의 묶음 가격") @NullableResponse BigDecimal bundlePrice,
      @Schema(description = "주머니 1개에 든 파편 수(공식 Tooltip). 파편이 아니면 null") @NullableResponse
          Integer contentsPerItem,
      @Schema(description = "거래소 페이지를 읽은 UTC 시각. 캐시 적중에도 유지") Instant observedAt) {}
}
