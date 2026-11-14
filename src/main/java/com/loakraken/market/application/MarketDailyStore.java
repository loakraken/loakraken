package com.loakraken.market.application;

import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 거래소 품목 일별 시세 저장소. 공식 상품 상세 Stats(오늘 포함 최근 14일)를 이미 받을 때마다 완료일(today 이전)을 덮어쓴다(새 공식 호출 없음). 차트
 * 기간(7일~1년)을 위해 지우지 않는다.
 */
public interface MarketDailyStore {
  /** 저장하지 않는 구성(테스트·기존 호출 호환). */
  MarketDailyStore NONE = (history, today, observedAt) -> 0;

  /** history의 변형마다 today 이전 날짜를 덮어쓰고 쓴 행 수를 돌려준다. 같은 날짜는 더 늦은 관측(observedAt)만 덮는다. */
  int upsert(MarketItemHistory history, LocalDate today, Instant observedAt);
}
