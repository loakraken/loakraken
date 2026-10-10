package com.loakraken.market.presentation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.loakraken.market.application.MaterialQuoteCommand;
import com.loakraken.market.domain.MaterialPriceBasis;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record MaterialQuoteRequest(
    @NotNull @Schema(description = "비교에 사용할 묶음 가격. 자동 대체하지 않습니다.") MaterialPriceBasis priceBasis,
    @NotNull @Size(min = 1, max = 10) List<@NotNull @Valid MaterialQuoteItemRequest> items) {
  @AssertTrue(message = "상품 ID는 중복될 수 없습니다.")
  @JsonIgnore
  @Schema(hidden = true)
  public boolean isUniqueItemIds() {
    if (items == null) return true;
    var ids =
        items.stream()
            .filter(item -> item != null && item.itemId() != null)
            .map(item -> item.itemId().stripTrailingZeros())
            .toList();
    return ids.stream().distinct().count() == ids.size();
  }

  public MaterialQuoteCommand toCommand() {
    return new MaterialQuoteCommand(
        priceBasis,
        items.stream()
            .map(
                item ->
                    new MaterialQuoteCommand.Item(
                        item.itemId().longValueExact(),
                        item.requiredQuantity().longValueExact(),
                        item.ownedQuantity().longValueExact(),
                        item.search().toSearch()))
            .toList());
  }

  public record MaterialQuoteItemRequest(
      @NotNull
          @DecimalMin("1")
          @DecimalMax("9007199254740991")
          @Digits(integer = 16, fraction = 0)
          @Schema(
              type = "integer",
              format = "int64",
              description = "조회 결과에서 선택한 정확한 상품 ID",
              example = "123")
          BigDecimal itemId,
      @NotNull
          @DecimalMin("0")
          @DecimalMax("999999999")
          @Digits(integer = 9, fraction = 0)
          @Schema(
              type = "integer",
              format = "int64",
              description = "필요한 상품 개수. 주머니 내용물 개수가 아닙니다.",
              example = "250")
          BigDecimal requiredQuantity,
      @NotNull
          @DecimalMin("0")
          @DecimalMax("999999999")
          @Digits(integer = 9, fraction = 0)
          @Schema(
              type = "integer",
              format = "int64",
              description = "이번 계획에 사용할 수 있는 보유 개수",
              example = "40")
          BigDecimal ownedQuantity,
      @NotNull @Valid MarketSearchRequest search) {}
}
