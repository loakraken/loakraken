package com.loakraken.market.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketItemHistoryTest {
  private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

  @Test
  void 요약은_오늘_부분_집계를_빼고_완료일로_전일_대비와_7일_가중_평균을_낸다() {
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    days.add(day(TODAY, "175.5", 8_817)); // 부분 집계
    days.add(day(TODAY.minusDays(1), "172.6", 200_000));
    days.add(day(TODAY.minusDays(2), "165.4", 100_000));
    for (int i = 3; i <= 13; i++) days.add(day(TODAY.minusDays(i), "160", 100_000));
    var variant = new MarketItemHistory.Variant("운명의 파괴석", null, 100, days);

    assertThat(variant.days().getFirst().date()).isEqualTo(TODAY);
    var summary = variant.summary(TODAY);
    assertThat(summary.latestDate()).isEqualTo(TODAY.minusDays(1));
    assertThat(summary.latestAveragePrice()).isEqualByComparingTo("172.6");
    assertThat(summary.previousAveragePrice()).isEqualByComparingTo("165.4");
    // (172.6 − 165.4) / 165.4 = 4.353…%
    assertThat(summary.dayChangeRate()).isEqualByComparingTo("4.35");
    assertThat(summary.sevenDayDays()).isEqualTo(7);
    // (172.6×200000 + 165.4×100000 + 160×100000×5) / 800000 = 163.825
    assertThat(summary.sevenDayTradeCount()).isEqualTo(800_000);
    assertThat(summary.sevenDayAveragePrice()).isEqualByComparingTo("163.83");
    assertThat(summary.sevenDayChangeRate()).isEqualByComparingTo("5.35");
  }

  @Test
  void 완료일이_없거나_하루뿐이면_비교_값은_null이다() {
    var onlyToday = new MarketItemHistory.Variant("재료", 1, 1, List.of(day(TODAY, "10", 5)));
    assertThat(onlyToday.summary(TODAY)).isEqualTo(MarketItemHistory.Summary.EMPTY);

    var oneDay =
        new MarketItemHistory.Variant(
            "재료", 1, 1, List.of(day(TODAY.minusDays(1), "10", 0), day(TODAY, "11", 1)));
    var summary = oneDay.summary(TODAY);
    assertThat(summary.latestAveragePrice()).isEqualByComparingTo("10");
    assertThat(summary.previousAveragePrice()).isNull();
    assertThat(summary.dayChangeRate()).isNull();
    assertThat(summary.sevenDayDays()).isEqualTo(1);
    assertThat(summary.sevenDayAveragePrice()).isNull(); // 거래량 0이면 가중 평균 없음
    assertThat(summary.sevenDayChangeRate()).isNull();
  }

  private static MarketItemHistory.DailyStat day(LocalDate date, String price, long trades) {
    return new MarketItemHistory.DailyStat(date, new BigDecimal(price), trades);
  }
}
