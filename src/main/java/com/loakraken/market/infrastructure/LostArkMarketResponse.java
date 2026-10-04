package com.loakraken.market.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.market.application.MarketPage;
import com.loakraken.market.application.MarketSource;
import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkMarketResponse(
    @JsonProperty("PageNo") Integer pageNo,
    @JsonProperty("PageSize") Integer pageSize,
    @JsonProperty("TotalCount") Integer totalCount,
    @JsonProperty("Items") List<Item> items) {
  MarketPage toPage(MarketSource source, int requestedPage) {
    if (pageNo == null
        || pageNo != requestedPage
        || pageSize == null
        || pageSize < 1
        || totalCount == null
        || totalCount < 0
        || items == null
        || items.size() > pageSize
        || items.size() > totalCount) throw invalid();
    return new MarketPage(
        pageNo,
        pageSize,
        totalCount,
        items.stream()
            .map(
                item -> {
                  if (item == null) throw invalid();
                  return item.toPrice(source);
                })
            .toList());
  }

  private static BusinessException invalid() {
    return new BusinessException(LostArkErrorCode.LOST_ARK_API_ERROR);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Item(
      @JsonProperty("Id") Long id,
      @JsonProperty("Name") String name,
      @JsonProperty("Grade") String grade,
      @JsonProperty("Icon") String icon,
      @JsonProperty("BundleCount") Integer bundleCount,
      @JsonProperty("TradeRemainCount") Integer tradeRemainCount,
      @JsonProperty("YDayAvgPrice") BigDecimal yDayAvgPrice,
      @JsonProperty("RecentPrice") BigDecimal recentPrice,
      @JsonProperty("CurrentMinPrice") BigDecimal currentMinPrice) {
    MarketPrice toPrice(MarketSource source) {
      if (id == null
          || id < 1
          || name == null
          || name.isBlank()
          || bundleCount == null
          || bundleCount < 1
          || (tradeRemainCount != null && tradeRemainCount < 0)) throw invalid();
      checkPrice(yDayAvgPrice);
      checkPrice(recentPrice);
      var minimum = source == MarketSource.ACTIVE_LISTING ? currentMinPrice : null;
      checkPrice(minimum);
      return new MarketPrice(
          id, name, grade, icon, bundleCount, tradeRemainCount, yDayAvgPrice, recentPrice, minimum);
    }

    private void checkPrice(BigDecimal price) {
      if (price != null && price.signum() < 0) throw invalid();
    }
  }
}
