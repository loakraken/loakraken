package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.MarketOptionsResult;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record MarketOptionsResponse(
    @Schema(description = "공식 검색 옵션을 수집한 시각. 캐시 적중 시 유지됩니다.") Instant observedAt,
    List<MarketCategoryResponse> categories,
    List<String> itemGrades,
    List<Integer> itemTiers,
    List<String> classes) {
  public static MarketOptionsResponse from(MarketOptionsResult result) {
    var options = result.options();
    return new MarketOptionsResponse(
        result.observedAt(),
        options.categories().stream()
            .map(
                category ->
                    new MarketCategoryResponse(
                        category.code(),
                        category.codeName(),
                        category.subs() == null
                            ? null
                            : category.subs().stream()
                                .map(
                                    sub ->
                                        new MarketSubcategoryResponse(sub.code(), sub.codeName()))
                                .toList()))
            .toList(),
        options.itemGrades(),
        options.itemTiers(),
        options.classes());
  }

  public record MarketCategoryResponse(
      int code,
      String codeName,
      @NullableResponse @Schema(description = "하위 카테고리. 원천 미제공은 null, 제공된 빈 목록은 []")
          List<MarketSubcategoryResponse> subs) {}

  public record MarketSubcategoryResponse(int code, String codeName) {}
}
