package com.loakraken.market.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.MarketDailyStore;
import com.loakraken.market.application.MarketHistoryRangeService;
import com.loakraken.market.application.MarketItemHistoryResult;
import com.loakraken.market.domain.MarketHistoryRange;
import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 기간 응답: 공식 14일에 저장 완료일을 합치고(같은 날짜는 공식), 변형 칸이 같은 저장 행만 붙이며, 7d는 공식 안에서 자른다. */
class MarketItemHistoryRangeResponseTest {
  // KST 2026-11-13 12:00 → 기준일 11-13
  private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
  private static final LocalDate TODAY = LocalDate.parse("2026-11-13");

  @Test
  void 삼십일은_공식_14일에_저장_완료일을_더하고_같은_날짜는_공식_값이다() {
    var stored =
        new MarketDailyStore.StoredRange(
            List.of(
                // 공식 14일과 겹치는 날(공식 값이 이김)
                new MarketDailyStore.StoredDay("-1/1", TODAY.minusDays(1), new BigDecimal("99"), 9),
                new MarketDailyStore.StoredDay("-1/1", TODAY.minusDays(20), new BigDecimal("8"), 4),
                new MarketDailyStore.StoredDay("-1/1", TODAY.minusDays(29), new BigDecimal("7"), 2),
                // 다른 변형 칸(공식 응답에 없는 변형)은 붙이지 않는다
                new MarketDailyStore.StoredDay("3/1", TODAY.minusDays(20), new BigDecimal("1"), 1)),
            Map.of("-1/1", TODAY.minusDays(40), "3/1", TODAY.minusDays(25)));

    var response =
        MarketItemHistoryResponse.from(
            new MarketHistoryRangeService.ItemRange(official(), MarketHistoryRange.D30, stored));

    assertThat(response.range()).isEqualTo("30d");
    var variant = response.variants().getFirst();
    assertThat(variant.recordedSince()).isEqualTo(TODAY.minusDays(40));
    assertThat(variant.storedDays()).isEqualTo(3);
    // 최신순: 오늘(부분) + 공식 13일 + 저장 2일
    assertThat(variant.days()).hasSize(16);
    assertThat(variant.days().getFirst().date()).isEqualTo(TODAY);
    assertThat(variant.days().getFirst().partial()).isTrue();
    assertThat(variant.days().get(1).averagePrice()).isEqualByComparingTo("10");
    assertThat(variant.days())
        .extracting(
            day -> day.date() + "/" + day.averagePrice().stripTrailingZeros().toPlainString())
        .endsWith(TODAY.minusDays(20) + "/8", TODAY.minusDays(29) + "/7");
    assertThat(variant.days().getLast().partial()).isFalse();
  }

  @Test
  void 칠일은_공식_안에서_기준일_포함_7일만_주고_저장_필드는_비운다() {
    var response =
        MarketItemHistoryResponse.from(
            new MarketHistoryRangeService.ItemRange(official(), MarketHistoryRange.D7, null));
    assertThat(response.range()).isEqualTo("7d");
    var variant = response.variants().getFirst();
    assertThat(variant.days()).hasSize(7);
    assertThat(variant.days().getLast().date()).isEqualTo(TODAY.minusDays(6));
    assertThat(variant.recordedSince()).isNull();
    assertThat(variant.storedDays()).isNull();
  }

  @Test
  void 십사일은_지금_응답과_같고_range만_돌려준다() {
    var ranged =
        MarketItemHistoryResponse.from(
            new MarketHistoryRangeService.ItemRange(official(), MarketHistoryRange.D14, null));
    var plain = MarketItemHistoryResponse.from(official());
    assertThat(ranged.range()).isEqualTo("14d");
    assertThat(ranged.variants()).isEqualTo(plain.variants());
  }

  /** 공식 14일(오늘 부분 집계 포함), 값 10, 거래량 5. 변형 하나(거래 가능 횟수 없음, 묶음 1). */
  private static MarketItemHistoryResult official() {
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    for (int i = 0; i < 14; i++)
      days.add(new MarketItemHistory.DailyStat(TODAY.minusDays(i), BigDecimal.TEN, 5));
    return new MarketItemHistoryResult(
        NOW,
        new MarketItemHistory(7L, List.of(new MarketItemHistory.Variant("품목", null, 1, days))));
  }
}
