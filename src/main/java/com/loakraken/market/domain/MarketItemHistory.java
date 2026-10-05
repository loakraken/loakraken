package com.loakraken.market.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 공식 거래소 상품 상세의 일별 시세. 같은 상품 ID에 거래 가능 횟수·묶음이 다른 변형이 여러 개 올 수 있다.
 *
 * <p>공식 Stats는 오늘(부분 집계)을 포함한 최근 14일이다. 요약은 기준일(today) 이전의 완료일만 사용한다.
 */
public record MarketItemHistory(long itemId, List<Variant> variants) {
  public static final int SEVEN_DAYS = 7;

  public MarketItemHistory {
    variants = variants == null ? List.of() : List.copyOf(variants);
  }

  public record Variant(
      String name, Integer tradeRemainCount, Integer bundleCount, List<DailyStat> days) {
    public Variant {
      days =
          days == null
              ? List.of()
              : days.stream().sorted(Comparator.comparing(DailyStat::date).reversed()).toList();
    }

    /** today 이전 날짜만 완료일로 본다. 최신순. */
    public List<DailyStat> completeDays(LocalDate today) {
      return days.stream().filter(day -> day.date().isBefore(today)).toList();
    }

    public Summary summary(LocalDate today) {
      var complete = completeDays(today);
      if (complete.isEmpty()) return Summary.EMPTY;
      var latest = complete.getFirst();
      var previous = complete.size() > 1 ? complete.get(1) : null;
      var week = complete.subList(0, Math.min(SEVEN_DAYS, complete.size()));
      long weekTrades = week.stream().mapToLong(DailyStat::tradeCount).sum();
      BigDecimal weekAverage =
          weekTrades == 0
              ? null
              : week.stream()
                  .map(day -> day.averagePrice().multiply(BigDecimal.valueOf(day.tradeCount())))
                  .reduce(BigDecimal.ZERO, BigDecimal::add)
                  .divide(BigDecimal.valueOf(weekTrades), 2, RoundingMode.HALF_UP);
      return new Summary(
          latest.date(),
          latest.averagePrice(),
          previous == null ? null : previous.averagePrice(),
          previous == null ? null : changeRate(latest.averagePrice(), previous.averagePrice()),
          week.size(),
          weekAverage,
          weekAverage == null ? null : changeRate(latest.averagePrice(), weekAverage),
          weekTrades);
    }
  }

  public record DailyStat(LocalDate date, BigDecimal averagePrice, long tradeCount) {}

  /**
   * latest: 가장 최근 완료일, previous: 그 전 완료일. 등락률은 % 단위(소수 둘째 자리). 7일 평균은 완료일 최대 7일의 거래량 가중 평균이며
   * sevenDayDays에 실제 사용한 일수를 담는다.
   */
  public record Summary(
      LocalDate latestDate,
      BigDecimal latestAveragePrice,
      BigDecimal previousAveragePrice,
      BigDecimal dayChangeRate,
      int sevenDayDays,
      BigDecimal sevenDayAveragePrice,
      BigDecimal sevenDayChangeRate,
      long sevenDayTradeCount) {
    public static final Summary EMPTY = new Summary(null, null, null, null, 0, null, null, 0);
  }

  static BigDecimal changeRate(BigDecimal current, BigDecimal base) {
    if (base == null || base.signum() == 0) return null;
    return current
        .subtract(base)
        .multiply(BigDecimal.valueOf(100))
        .divide(base, 2, RoundingMode.HALF_UP);
  }
}
