package com.loakraken.market.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.market.application.AuctionPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 공식 `POST /auctions/items` 응답. 필요한 필드만 읽는다. EndDate는 KST 시각이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkAuctionResponse(
    @JsonProperty("TotalCount") Long totalCount, @JsonProperty("Items") List<Item> items) {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  AuctionPort.AuctionPage toPage(Instant observedAt) {
    return new AuctionPort.AuctionPage(
        totalCount == null ? 0 : totalCount,
        items == null
            ? List.of()
            : items.stream().filter(item -> item != null).map(Item::toListing).toList(),
        observedAt);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Item(
      @JsonProperty("Name") String name,
      @JsonProperty("Grade") String grade,
      @JsonProperty("GradeQuality") Integer quality,
      @JsonProperty("AuctionInfo") AuctionInfo auctionInfo,
      @JsonProperty("Options") List<Option> options,
      @JsonProperty("Icon") String icon) {
    AuctionPort.AuctionListing toListing() {
      var info = auctionInfo == null ? new AuctionInfo(null, null, null, null, null) : auctionInfo;
      return new AuctionPort.AuctionListing(
          name,
          grade,
          quality,
          info.buyPrice(),
          info.bidPrice(),
          info.bidStartPrice(),
          info.endDate() == null ? null : info.endDate().atZone(KST).toInstant(),
          info.tradeAllowCount(),
          options == null
              ? List.of()
              : options.stream()
                  .filter(option -> option != null && option.name() != null)
                  .map(
                      option ->
                          new AuctionPort.ListingOption(
                              option.type(),
                              option.name(),
                              option.value(),
                              Boolean.TRUE.equals(option.percentage()),
                              option.className() == null || option.className().isBlank()
                                  ? null
                                  : option.className()))
                  .toList(),
          icon);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AuctionInfo(
      @JsonProperty("BuyPrice") Long buyPrice,
      @JsonProperty("BidPrice") Long bidPrice,
      @JsonProperty("BidStartPrice") Long bidStartPrice,
      @JsonProperty("EndDate") LocalDateTime endDate,
      @JsonProperty("TradeAllowCount") Integer tradeAllowCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Option(
      @JsonProperty("Type") String type,
      @JsonProperty("OptionName") String name,
      @JsonProperty("Value") BigDecimal value,
      @JsonProperty("IsValuePercentage") Boolean percentage,
      @JsonProperty("ClassName") String className) {}
}
