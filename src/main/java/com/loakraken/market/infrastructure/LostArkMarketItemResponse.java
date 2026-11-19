package com.loakraken.market.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

  /** 한 응답에서 받는 변형(거래 가능 횟수·묶음) 수 상한(공식은 보통 1~3개, 보안 11-16 (b)). 넘는 변형은 버린다. */
  static final int MAX_VARIANTS = 32;

  /** 변형마다 받는 일별 줄 상한(공식은 14일). 넘는 줄은 버린다. */
  static final int MAX_ROWS = 40;

  /** 받는 날짜 범위: 오늘(KST) − 이만큼 일 ~ 오늘(공식은 지난 14일). 밖의 날짜는 버린다. */
  static final int MAX_DAYS_BACK = 30;

  /** 가격의 정수 자리 상한(저장 칸 NUMERIC(16, 4)와 같음). */
  static final int MAX_INTEGER_DIGITS = 12;

  /** 가격 scale 범위(지수 표기 1e100000000 같은 값이 계산·직렬화에서 크게 펼쳐지지 않게, 보안 11-16 (b)). */
  static final int MIN_SCALE = -12;

  static final int MAX_SCALE = 8;

  /**
   * 가격이 받을 만한 값인가: 음수가 아니고, scale이 [MIN_SCALE, MAX_SCALE], 정수 자리가 MAX_INTEGER_DIGITS 이하. 값을 펼치지 않고
   * 잰다.
   */
  static boolean plausiblePrice(BigDecimal price) {
    if (price == null || price.signum() < 0) return false;
    int scale = price.scale();
    if (scale < MIN_SCALE || scale > MAX_SCALE) return false;
    return price.precision() - scale <= MAX_INTEGER_DIGITS;
  }

  static MarketItemHistory toDomain(long itemId, LostArkMarketItemResponse[] items) {
    return toDomain(itemId, items, null, new int[5]);
  }

  /**
   * 공식 거래소 품목 상세를 도메인으로(보안 11-16 (b): 고장 났거나 적대적인 상위 응답만 막는 상한). today가 있으면 날짜를 [today −
   * MAX_DAYS_BACK, today]로 거른다. 변형은 MAX_VARIANTS, 변형마다 줄은 MAX_ROWS까지. 가격이 plausiblePrice가 아니거나 거래량이
   * 음수인 줄은 버린다. dropped[0..4] = 변형·줄 수·가격·날짜·거래량으로 버린 개수.
   */
  static MarketItemHistory toDomain(
      long itemId, LostArkMarketItemResponse[] items, LocalDate today, int[] dropped) {
    var variants = new java.util.ArrayList<MarketItemHistory.Variant>();
    for (var item : items) {
      if (item == null) continue;
      if (variants.size() >= MAX_VARIANTS) {
        dropped[0]++;
        continue;
      }
      var rows = new java.util.ArrayList<MarketItemHistory.DailyStat>();
      if (item.stats() != null)
        for (var stat : item.stats()) {
          if (stat == null || stat.date() == null || stat.averagePrice() == null) continue;
          if (today != null
              && (stat.date().isAfter(today)
                  || stat.date().isBefore(today.minusDays(MAX_DAYS_BACK)))) {
            dropped[3]++;
            continue;
          }
          if (!plausiblePrice(stat.averagePrice())) {
            dropped[2]++;
            continue;
          }
          if (stat.tradeCount() != null && stat.tradeCount() < 0) {
            dropped[4]++;
            continue;
          }
          if (rows.size() >= MAX_ROWS) {
            dropped[1]++;
            continue;
          }
          rows.add(
              new MarketItemHistory.DailyStat(
                  stat.date(),
                  stat.averagePrice(),
                  stat.tradeCount() == null ? 0 : stat.tradeCount()));
        }
      variants.add(
          new MarketItemHistory.Variant(
              item.name(), item.tradeRemainCount(), item.bundleCount(), List.copyOf(rows)));
    }
    return new MarketItemHistory(itemId, List.copyOf(variants));
  }
}
