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

  private String tag(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  public enum Endpoint {
    ARMORY,
    SIBLINGS,
    MARKET_ITEMS,
    MARKET_TRADES,
    MARKET_OPTIONS,
    MARKET_ITEM_HISTORY
  }

  public enum ApiOutcome {
    SUCCESS,
    NOT_FOUND,
    RATE_LIMITED,
    TIMEOUT,
    ERROR
  }

  public enum GateEvent {
    BUDGET_REJECT,
    CONCURRENCY_REJECT,
    UPSTREAM_429,
    UPSTREAM_BACKOFF_REJECT
  }
}
