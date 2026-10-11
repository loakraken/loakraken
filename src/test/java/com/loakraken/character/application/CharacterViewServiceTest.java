package com.loakraken.character.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class CharacterViewServiceTest {
  // 2026-10-08T15:30Z = KST 10-09 00:30. 날짜 경계는 KST로 센다.
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T15:30:00Z"), ZoneOffset.UTC);
  private final CharacterViewPort port = mock(CharacterViewPort.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void 기록은_KST_날짜로_비동기_저장하고_저장_실패는_삼킨다() throws Exception {
    var done = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              done.countDown();
              if (done.getCount() == 0)
                throw new DataAccessResourceFailureException("internal-secret");
              return null;
            })
        .when(port)
        .increment(any(), any());
    var service = service(true);
    service.record("모험가", "루페온");
    service.record("모험가", "루페온");
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    verify(port, timeout(5000).times(2)).increment(eq("모험가"), eq(LocalDate.parse("2026-10-09")));
    service.shutdown();
    assertThat(
            registry
                .get("loakraken.character.views.writes")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 비활성이면_기록하지_않고_조회는_503_오류다() throws Exception {
    var service = service(false);
    service.record("모험가", "루페온");
    service.shutdown();
    verifyNoInteractions(port);
    assertThatThrownBy(() -> service.views("모험가", 7))
        .isInstanceOf(CharacterException.class)
        .extracting(error -> ((CharacterException) error).errorCode())
        .isEqualTo(CharacterErrorCode.CHARACTER_VIEWS_DISABLED);
  }

  @Test
  void 조회는_빈_날을_0으로_채우고_합계를_낸다() {
    when(port.daily(eq("모험가"), any(), any()))
        .thenReturn(Map.of(LocalDate.parse("2026-10-09"), 3L, LocalDate.parse("2026-10-07"), 1L));
    when(port.total("모험가")).thenReturn(10L);
    var views = service(true).views("모험가", 3);
    assertThat(views.from()).isEqualTo(LocalDate.parse("2026-10-07"));
    assertThat(views.days())
        .extracting(CharacterViewService.DailyViews::count)
        .containsExactly(1L, 0L, 3L);
    assertThat(views.rangeTotal()).isEqualTo(4);
    assertThat(views.total()).isEqualTo(10);
  }

  private CharacterViewService service(boolean enabled) {
    return new CharacterViewService(
        port,
        new CharacterViewPolicy(enabled),
        new CharacterMetrics(registry),
        clock,
        openStopList());
  }

  /** 처리정지 목록이 비어 있음(#351). */
  static CharacterStopList openStopList() {
    return new CharacterStopList(org.mockito.Mockito.mock(CharacterPublicSummaryPort.class));
  }
}
