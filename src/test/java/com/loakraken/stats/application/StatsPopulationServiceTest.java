package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.common.api.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 전체 인구: 저장소 장애는 503, 다시 만들기가 실패하면 잠시 다시 시도하지 않고 503(지난 값 대신 주지 않음). 코드 리뷰 10-31. */
class StatsPopulationServiceTest {
  private final PopulationSnapshotPort port = mock(PopulationSnapshotPort.class);
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-31T12:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };
  private final StatsPopulationService service = new StatsPopulationService(port, clock);

  private static StatsErrorCode code(Throwable exception) {
    return (StatsErrorCode) ((BusinessException) exception).errorCode();
  }

  @Test
  void 다시_만들기가_실패하면_503이고_잠시_다시_시도하지_않는다() {
    when(port.latest())
        .thenReturn(
            Optional.of(new PopulationSnapshotPort.Latest(LocalDate.of(2026, 10, 25), "old")));
    when(port.exclusionRevision()).thenReturn("new");
    doThrow(new DataAccessResourceFailureException("down")).when(port).build(any(), any());

    assertThatThrownBy(() -> service.population(null))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo(StatsErrorCode.STATS_UNAVAILABLE));
    verify(port, times(1)).build(any(), any());

    // 60초 안: 다시 만들지 않고 503(지난 값 대신 주지 않음)
    now.set(now.get().plusSeconds(30));
    assertThatThrownBy(() -> service.population(null))
        .satisfies(e -> assertThat(code(e)).isEqualTo(StatsErrorCode.STATS_UNAVAILABLE));
    verify(port, times(1)).build(any(), any());

    // 지나면 다시 시도
    now.set(now.get().plusSeconds(31));
    assertThatThrownBy(() -> service.population(null))
        .satisfies(e -> assertThat(code(e)).isEqualTo(StatsErrorCode.STATS_UNAVAILABLE));
    verify(port, times(2)).build(any(), any());
  }

  @Test
  void 읽기가_실패해도_500이_아니라_503이다() {
    when(port.latest()).thenThrow(new DataAccessResourceFailureException("down"));
    assertThatThrownBy(() -> service.population("서버"))
        .isInstanceOf(BusinessException.class)
        .satisfies(e -> assertThat(code(e)).isEqualTo(StatsErrorCode.STATS_UNAVAILABLE));
  }

  @Test
  void 목록이_그대로면_다시_만들지_않는다() {
    var date = LocalDate.of(2026, 10, 25);
    when(port.latest()).thenReturn(Optional.of(new PopulationSnapshotPort.Latest(date, "same")));
    when(port.exclusionRevision()).thenReturn("same");
    when(port.rows(date, ""))
        .thenReturn(List.of(new PopulationSnapshotPort.Row("LEVEL", "1700", 3)));
    var population = service.population(null);
    assertThat(population.snapshotDate()).isEqualTo(date);
    assertThat(population.total()).isEqualTo(3);
    verify(port, times(0)).build(any(), any());
  }
}
