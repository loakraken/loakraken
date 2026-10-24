package com.loakraken.common.lostark;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 태그는 고정된 열거형만 사용하며 이름, URL, 키, 예외 메시지를 받지 않는다. */
@Component
public class LostArkMetrics {
  private final MeterRegistry registry;

  public LostArkMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public Timer.Sample startApi() {
    return Timer.start(registry);
  }

  public void finishApi(Timer.Sample sample, Endpoint endpoint, ApiOutcome outcome) {
    sample.stop(
        registry.timer(
            "loakraken.lostark.requests", "endpoint", tag(endpoint), "outcome", tag(outcome)));
  }

  public void gate(GateEvent event) {
    registry.counter("loakraken.lostark.gate", "event", tag(event)).increment();
  }

  /** 게이트가 허가한 공식 호출 1회를 경로별로 센다(거절은 세지 않음). */
  public void call(CallOrigin origin) {
    registry.counter("loakraken.lostark.calls", "origin", tag(origin)).increment();
  }

  private String tag(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  public enum Endpoint {
    ARMORY,
    PROFILE,
    SIBLINGS,
    MARKET_ITEMS,
    MARKET_TRADES,
    MARKET_OPTIONS,
    MARKET_ITEM_HISTORY,
    AUCTION_ITEMS,
    GAME_CALENDAR,
    NEWS_NOTICES,
    NEWS_EVENTS
  }

  public enum ApiOutcome {
    SUCCESS,
    NOT_FOUND,
    RATE_LIMITED,
    TIMEOUT,
    ERROR
  }

  /**
   * 공식 호출 경로. USER: 사용자 요청, USER_RESERVE: 사용자 요청 중 사용자 몫을 남기는 호출, BACKGROUND: 수집기·예열(클라이언트 한도 안 셈).
   */
  public enum CallOrigin {
    USER,
    USER_RESERVE,
    BACKGROUND
  }

  public enum GateEvent {
    BUDGET_REJECT,
    CONCURRENCY_REJECT,
    UPSTREAM_429,
    UPSTREAM_BACKOFF_REJECT,
    GATE_UNAVAILABLE
  }
}
