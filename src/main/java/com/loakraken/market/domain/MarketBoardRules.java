package com.loakraken.market.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/** 시세 보드 등락률과 상승·하락 정렬 규칙(market-board-v1). */
public final class MarketBoardRules {
  public static final String RULE_VERSION = "market-board-v1";
  public static final String CHANGE_BASIS =
      "changeDay = 현재 최저가 ÷ 전일 평균가 − 1, changeWeek = 현재 최저가 ÷ 최근 완료일 최대 7일 거래량 가중 평균가 − 1 (%, 소수 첫째 자리)";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private MarketBoardRules() {}

  public enum Sort {
    DEFAULT,
    HIGH,
    LOW,
    RISE,
    FALL;

    public boolean scansWholeTab() {
      return this == RISE || this == FALL;
    }
  }

  /** 기준 가격이 없거나 0이면, 또는 현재 최저가가 없으면 null이다. */
  public static BigDecimal change(BigDecimal current, BigDecimal base) {
    if (current == null || base == null || base.signum() <= 0) return null;
    return current.subtract(base).multiply(HUNDRED).divide(base, 1, RoundingMode.HALF_UP);
  }

  public static BigDecimal changeDay(MarketPrice price) {
    return change(price.currentMinPrice(), price.yDayAvgPrice());
  }

  /** RISE는 changeDay 내림차순, FALL은 오름차순. changeDay가 null인 품목은 맨 뒤, 같으면 이름·ID 순. */
  public static List<MarketPrice> sortByChange(List<MarketPrice> items, Sort sort) {
    if (!sort.scansWholeTab()) throw new IllegalArgumentException("등락 정렬이 아닙니다.");
    Comparator<BigDecimal> byValue =
        sort == Sort.RISE ? Comparator.reverseOrder() : Comparator.naturalOrder();
    return items.stream()
        .sorted(
            Comparator.comparing(MarketBoardRules::changeDay, Comparator.nullsLast(byValue))
                .thenComparing(MarketPrice::name)
                .thenComparingLong(MarketPrice::id))
        .toList();
  }
}
