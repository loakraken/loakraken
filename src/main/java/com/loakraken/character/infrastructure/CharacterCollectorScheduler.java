package com.loakraken.character.infrastructure;

import com.loakraken.character.application.CharacterCollectorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 통계 표본 수집 주기 실행. 단일 인스턴스 전제다. */
@Component
@ConditionalOnProperty(prefix = "stats.collector", name = "enabled", havingValue = "true")
class CharacterCollectorScheduler {
  private final CharacterCollectorService collector;

  CharacterCollectorScheduler(CharacterCollectorService collector) {
    this.collector = collector;
  }

  @Scheduled(
      fixedDelayString = "${stats.collector.interval:3s}",
      initialDelayString = "${stats.collector.initial-delay:30s}")
  void tick() {
    collector.tick();
  }
}
