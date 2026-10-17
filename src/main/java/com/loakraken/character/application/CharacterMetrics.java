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

  public void history(boolean success) {
    registry
        .counter("loakraken.character.history.writes", "outcome", success ? "success" : "failure")
        .increment();
  }

  /** 장비 요약 기록 결과: created·confirmed·skipped·failed(#146). */
  public void equipmentSummary(String outcome) {
    registry
        .counter(
            "loakraken.character.equipment_summary.writes",
            "outcome",
            outcome.toLowerCase(java.util.Locale.ROOT))
        .increment();
  }

  /** 원정대 전투력 불러오기 결과: ok·partial·limited·cooldown. */
  public void rosterRefresh(String outcome) {
    registry.counter("loakraken.character.roster_refresh", "outcome", outcome).increment();
  }

  public void cards(boolean success) {
    registry
        .counter("loakraken.character.cards.writes", "outcome", success ? "success" : "failure")
        .increment();
  }

  public void popular(boolean success) {
    registry
        .counter("loakraken.character.popular.writes", "outcome", success ? "success" : "failure")
        .increment();
  }

  public void views(boolean success) {
    registry
        .counter("loakraken.character.views.writes", "outcome", success ? "success" : "failure")
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
    WRITE_ERROR,
    NOT_FOUND_HIT
  }
}
