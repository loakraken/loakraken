package com.loakraken.market.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkMarketItemResponse(
    @JsonProperty("Name") String name,
    @JsonProperty("TradeRemainCount") Integer tradeRemainCount,
    @JsonProperty("BundleCount") Integer bundleCount,
    @JsonProperty("Stats") List<Stat> stats) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Stat(
      @JsonProperty("Date") LocalDate date,
      @JsonProperty("AvgPrice") BigDecimal averagePrice,
      @JsonProperty("TradeCount") Long tradeCount) {}

  static MarketItemHistory toDomain(long itemId, LostArkMarketItemResponse[] items) {
    return new MarketItemHistory(
        itemId,
        java.util.Arrays.stream(items)
            .filter(Objects::nonNull)
            .map(
                item ->
                    new MarketItemHistory.Variant(
                        item.name(),
                        item.tradeRemainCount(),
                        item.bundleCount(),
                        item.stats() == null
                            ? List.of()
                            : item.stats().stream()
                                .filter(
                                    stat ->
                                        stat != null
                                            && stat.date() != null
                                            && stat.averagePrice() != null)
                                .map(
                                    stat ->
                                        new MarketItemHistory.DailyStat(
                                            stat.date(),
                                            stat.averagePrice(),
                                            stat.tradeCount() == null ? 0 : stat.tradeCount()))
                                .toList()))
            .toList());
  }
}
