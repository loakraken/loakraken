package com.loakraken.market.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 거래소 분 단위 시세(K35) 저장소. */
public interface MarketMinuteStore {
  /** 같은 품목·같은 분이면 나중 값으로 덮는다. */
  void save(List<Point> points);

  /** from(포함) 이후 그 품목의 점, 오래된 분부터. */
  List<Point> find(long itemId, Instant from);

  /** before보다 오래된 분을 지우고 지운 수를 돌려준다. */
  int deleteBefore(Instant before);

  /**
   * @param minute 관측 시각을 UTC 분 단위로 내린 값
   * @param sampleEverySeconds 그 품목을 다시 받는 예열 주기(초)
   */
  record Point(
      long itemId,
      Instant minute,
      String itemName,
      BigDecimal currentMinPrice,
      BigDecimal recentPrice,
      int sampleEverySeconds) {}
}
