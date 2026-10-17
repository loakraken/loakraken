package com.loakraken.market.infrastructure;

import com.loakraken.market.application.MarketMinuteService;
import com.loakraken.market.application.MarketPricesObserved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 분 단위 차트(K35): 예열 가격 이벤트를 저장하고 오래된 분을 지운다. 저장 실패는 로그 한 줄만 남기고 예열을 막지 않는다(예외를 밖으로 내지 않음). */
@Component
class MarketMinuteRecorder {
  private static final Logger log = LoggerFactory.getLogger(MarketMinuteRecorder.class);
  private final MarketMinuteService minutes;

  MarketMinuteRecorder(MarketMinuteService minutes) {
    this.minutes = minutes;
  }

  @EventListener
  void onPrices(MarketPricesObserved event) {
    try {
      minutes.record(event);
    } catch (RuntimeException exception) {
      log.warn("분 단위 시세 저장에 실패했습니다. exceptionType={}", exception.getClass().getName());
    }
  }

  @Scheduled(fixedDelayString = "10m", initialDelayString = "10m")
  void cleanup() {
    try {
      minutes.cleanup();
    } catch (RuntimeException exception) {
      log.warn("오래된 분 단위 시세 정리에 실패했습니다. exceptionType={}", exception.getClass().getName());
    }
  }
}
