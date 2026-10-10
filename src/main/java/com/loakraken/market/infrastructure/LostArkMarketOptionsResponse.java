package com.loakraken.market.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.market.domain.MarketOptions;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkMarketOptionsResponse(
    @JsonProperty("Categories") List<Category> categories,
    @JsonProperty("ItemGrades") List<String> itemGrades,
    @JsonProperty("ItemTiers") List<Integer> itemTiers,
    @JsonProperty("Classes") List<String> classes) {
  MarketOptions toDomain() {
    try {
      return new MarketOptions(
          categories.stream()
              .map(
                  category ->
                      new MarketOptions.Category(
                          category.code(),
                          category.codeName(),
                          category.subs() == null
                              ? null
                              : category.subs().stream()
                                  .map(
                                      sub ->
                                          new MarketOptions.Subcategory(sub.code(), sub.codeName()))
                                  .toList()))
              .toList(),
          itemGrades,
          itemTiers,
          classes);
    } catch (NullPointerException | IllegalArgumentException exception) {
      throw new BusinessException(LostArkErrorCode.LOST_ARK_API_ERROR);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Category(
      @JsonProperty("Code") Integer code,
      @JsonProperty("CodeName") String codeName,
      @JsonProperty("Subs") List<Subcategory> subs) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Subcategory(
      @JsonProperty("Code") Integer code, @JsonProperty("CodeName") String codeName) {}
}
