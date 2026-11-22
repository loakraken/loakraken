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
            : items.stream()
                .filter(item -> item != null && item.plausible())
                .map(Item::toListing)
                .toList(),
        observedAt);
  }

  /**
   * 가격·옵션 값이 상한 밖인 매물 수(#995 리뷰 뒤속, 보안 11-16 (b)). toPage는 이 매물을 버린다(최저가·칸 값이 고장 난 상위 응답에 끌려가지 않게).
   */
  int implausibleItems() {
    if (items == null) return 0;
    return (int) items.stream().filter(item -> item != null && !item.plausible()).count();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Item(
      @JsonProperty("Name") String name,
      @JsonProperty("Grade") String grade,
      @JsonProperty("GradeQuality") Integer quality,
      @JsonProperty("AuctionInfo") AuctionInfo auctionInfo,
      @JsonProperty("Options") List<Option> options,
      @JsonProperty("Icon") String icon) {
    /** 즉시 구매가·입찰가·시작가가 없거나 정수 자리 12 안쪽이고, 옵션 값이 지수 표기로 크지 않은가. */
    boolean plausible() {
      if (auctionInfo != null
          && !(MarketPriceBounds.plausibleOrAbsent(auctionInfo.buyPrice())
              && MarketPriceBounds.plausibleOrAbsent(auctionInfo.bidPrice())
              && MarketPriceBounds.plausibleOrAbsent(auctionInfo.bidStartPrice()))) return false;
      if (options != null)
        for (var option : options)
          if (option != null
              && option.value() != null
              && !MarketPriceBounds.plausible(option.value().abs())) return false;
      return true;
    }

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
