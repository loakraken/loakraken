package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CharacterPopularServiceTest {
  private final CharacterPopularService service =
      new CharacterPopularService(
          mock(CharacterPopularPort.class),
          new CharacterPopularPolicy(true, 5, 30, Duration.ofMinutes(10), Duration.ofDays(8)),
          new CharacterMetrics(new SimpleMeterRegistry()),
          Clock.systemUTC());

  @Test
  void 클라이언트_키는_같은_날_같은_입력이면_같고_날이_바뀌면_달라지며_원문을_담지_않는다() {
    var day = LocalDate.of(2026, 10, 8);
    var first = service.clientKey(day, "203.0.113.7", "루페온", "모험가");
    assertThat(service.clientKey(day, "203.0.113.7", "루페온", "모험가")).isEqualTo(first);
    assertThat(service.clientKey(day, "203.0.113.8", "루페온", "모험가")).isNotEqualTo(first);
    assertThat(service.clientKey(day, "203.0.113.7", "루페온", "다른캐")).isNotEqualTo(first);
    assertThat(service.clientKey(day.plusDays(1), "203.0.113.7", "루페온", "모험가")).isNotEqualTo(first);
    assertThat(first).hasSize(32).doesNotContain("203", "모험가");
  }

  @Test
  void 기간은_24h와_7d만_받는다() {
    assertThat(CharacterPopularService.Period.of("7d"))
        .isEqualTo(CharacterPopularService.Period.LAST_7_DAYS);
    assertThatThrownBy(() -> CharacterPopularService.Period.of("1d"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
