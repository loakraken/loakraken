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
          new CharacterPopularPolicy(
              true,
              new CharacterPopularPolicy.MinUniqueViews(5, 15),
              30,
              Duration.ofMinutes(10),
              Duration.ofDays(8)),
          new CharacterMetrics(new SimpleMeterRegistry()),
          Clock.systemUTC(),
          openStopList());

  @Test
  void 클라이언트_키는_같은_날_같은_입력이면_같고_날이_바뀌면_달라지며_원문을_담지_않는다() {
    var day = LocalDate.of(2026, 10, 8);
    var first = service.clientKey(day, "203.0.113.7", "루페온", "모험가");
    assertThat(service.clientKey(day, "203.0.113.7", "루페온", "모험가")).isEqualTo(first);
    assertThat(service.clientKey(day, "203.0.113.8", "루페온", "모험가")).isNotEqualTo(first);
    assertThat(service.clientKey(day, "203.0.113.7", "루페온", "다른캐")).isNotEqualTo(first);
    // 키는 무작위 비밀값이 섞인 해시라 "203" 같은 짧은 숫자는 우연히 들어갈 수 있다. 원문(IP 전체·이름)이 없는지만 본다.
    assertThat(first).hasSize(32).doesNotContain("203.0.113.7", "모험가", "루페온");
    // 같은 /64의 IPv6는 같은 클라이언트, IPv4-mapped는 IPv4와 같다(PM-백엔드 #112 P1-1).
    assertThat(service.clientKey(day, "2001:db8:1:2::10", "루페온", "모험가"))
        .isEqualTo(service.clientKey(day, "2001:db8:1:2:ffff::1", "루페온", "모험가"))
        .isNotEqualTo(service.clientKey(day, "2001:db8:1:3::10", "루페온", "모험가"));
    assertThat(service.clientKey(day, "::ffff:203.0.113.7", "루페온", "모험가")).isEqualTo(first);
    assertThat(service.clientKey(day.plusDays(1), "203.0.113.7", "루페온", "모험가")).isNotEqualTo(first);
  }

  @Test
  void 기간별_최소_고유_조회_수는_24h_5회_7d_15회다() {
    assertThat(service.minUniqueViews(CharacterPopularService.Period.LAST_24_HOURS)).isEqualTo(5);
    assertThat(service.minUniqueViews(CharacterPopularService.Period.LAST_7_DAYS)).isEqualTo(15);
  }

  @Test
  void 기간은_24h와_7d만_받는다() {
    assertThat(CharacterPopularService.Period.of("7d"))
        .isEqualTo(CharacterPopularService.Period.LAST_7_DAYS);
    assertThatThrownBy(() -> CharacterPopularService.Period.of("1d"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 처리정지 목록이 비어 있음(#351). */
  static CharacterStopList openStopList() {
    return new CharacterStopList(org.mockito.Mockito.mock(CharacterPublicSummaryPort.class));
  }
}
