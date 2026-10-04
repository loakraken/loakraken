package com.loakraken.character.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 태그는 고정된 열거형만 사용하며 이름, URL, 키, 예외 메시지를 받지 않는다. */
@Component
public class CharacterMetrics {
  private final MeterRegistry registry;

  public CharacterMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void cache(CacheOutcome outcome) {
    registry.counter("loakraken.cache.operations", "outcome", tag(outcome)).increment();
  }

  public void search(boolean success) {
    registry
        .counter("loakraken.character.searches", "outcome", success ? "success" : "failure")
        .increment();
  }

  private String tag(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
  }

  public enum CacheOutcome {
    HIT,
    MISS,
    INVALID,
    READ_ERROR,
    WRITE_SUCCESS,
    WRITE_ERROR
  }
}
