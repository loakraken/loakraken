package com.loakraken.market.application;

import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 거래소 품목 일별 시세 저장소. 공식 상품 상세 Stats(오늘 포함 최근 14일)를 이미 받을 때마다 완료일(today 이전)을 덮어쓴다(새 공식 호출 없음). 차트
 * 기간(7일~1년)을 위해 지우지 않는다.
 */
public interface MarketDailyStore {
  /** 저장하지 않는 구성(테스트·기존 호출 호환). */
  MarketDailyStore NONE = (history, today, observedAt) -> 0;

  /** history의 변형마다 today 이전 날짜를 덮어쓰고 쓴 행 수를 돌려준다. 같은 날짜는 더 늦은 관측(observedAt)만 덮는다. */
  int upsert(MarketItemHistory history, LocalDate today, Instant observedAt);

  /**
   * 품목의 저장 완료일 가운데 from 이후(포함)와, 변형마다 처음 저장한 날짜(기간과 상관없이). 시세 기간 차트(30d 이상)가 읽는다. 저장하지 않는 구성은 빈 값.
   */
  default StoredRange findRange(long itemId, LocalDate from) {
    return StoredRange.EMPTY;
  }

  /** 변형 칸 키(거래 가능 횟수/묶음 수, 공식 값이 없으면 -1). 저장 표의 trade_remain_key·bundle_key와 같은 규칙. */
  static String variantKey(Integer tradeRemainCount, Integer bundleCount) {
    return (tradeRemainCount == null ? -1 : tradeRemainCount)
        + "/"
        + (bundleCount == null ? -1 : bundleCount);
  }

  /** 저장한 완료일 하루(변형 칸 키 = variantKey). */
  record StoredDay(String variantKey, LocalDate date, BigDecimal averagePrice, long tradeCount) {}

  /** days는 최신순, firstDates는 변형 칸 키 → 처음 저장한 날짜. */
  record StoredRange(List<StoredDay> days, Map<String, LocalDate> firstDates) {
    public static final StoredRange EMPTY = new StoredRange(List.of(), Map.of());

    public StoredRange {
      days = List.copyOf(days);
      firstDates = Map.copyOf(firstDates);
    }
  }
}
