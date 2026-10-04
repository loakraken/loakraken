package com.loakraken.market.presentation;

import com.loakraken.market.application.MarketSearch;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.text.Normalizer;

public record MarketSearchRequest(
    @NotNull @Positive @Schema(description = "공식 거래소 카테고리 코드", example = "50000")
        Integer categoryCode,
    @Size(max = 32) String characterClass,
    @Positive Integer itemTier,
    @Size(max = 16) String itemGrade,
    @Size(max = 64) String itemName,
    @Min(1) @Max(1000) @Schema(defaultValue = "1", description = "서비스 조회 범위: 1~1000 페이지")
        Integer pageNo,
    @Schema(defaultValue = "RECENT_PRICE") MarketSearch.Sort sort,
    @Schema(defaultValue = "ASC") MarketSearch.SortCondition sortCondition) {
  public MarketSearchRequest {
    characterClass = normalize(characterClass);
    itemGrade = normalize(itemGrade);
    itemName = normalize(itemName);
    if (pageNo == null) pageNo = 1;
    if (sort == null) sort = MarketSearch.Sort.RECENT_PRICE;
    if (sortCondition == null) sortCondition = MarketSearch.SortCondition.ASC;
  }

  public MarketSearch toSearch() {
    return new MarketSearch(
        categoryCode, characterClass, itemTier, itemGrade, itemName, pageNo, sort, sortCondition);
  }

  private static String normalize(String value) {
    return value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
  }
}
