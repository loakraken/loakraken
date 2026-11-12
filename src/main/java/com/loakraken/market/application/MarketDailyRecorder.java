package com.loakraken.market.application;

import com.loakraken.market.domain.MarketItemHistory;
import java.time.Instant;
import java.time.LocalDate;

/** 일별 시세 저장을 요청 경로 밖으로 넘긴다(보내고 잊음). 사용자 상세 요청은 저장을 기다리지 않는다. 큐가 가득 차면 버리고 로그·지표만 남긴다. */
public interface MarketDailyRecorder {
  /** 저장하지 않는 구성(테스트·기존 호출 호환). */
  MarketDailyRecorder NONE = (history, today, observedAt) -> {};

  void record(MarketItemHistory history, LocalDate today, Instant observedAt);
}
