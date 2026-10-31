package com.loakraken.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.stats.application.PopulationSnapshotPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 전체 인구 스냅샷 작업: 없거나 7일 지났으면 오늘(KST)로 만들고 12주보다 오래된 것은 지운다. 그 안이면 만들지 않는다. */
class PopulationSnapshotJobTest {
  private final PopulationSnapshotPort port = mock(PopulationSnapshotPort.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private PopulationSnapshotJob job() {
    var ops = (ValueOperations<String, String>) mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.setIfAbsent(eq(PopulationSnapshotJob.LEASE), any(), any(java.time.Duration.class)))
        .thenReturn(true);
    // 2026-10-31 20:00 UTC = 11-01 05:00 KST
    var clock = Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"), ZoneOffset.UTC);
    return new PopulationSnapshotJob(port, redis, clock, new SimpleMeterRegistry());
  }

  @Test
  void 스냅샷이_없으면_오늘_KST로_만든다() {
    when(port.latest()).thenReturn(Optional.empty());
    assertThat(job().run()).isTrue();
    verify(port).build(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 8, 9));
  }

  /** 기동 뒤 한 번도 같은 규칙: 없으면 만들고, 최근 것이 있으면 하지 않는다(배포·운영 10-31). */
  @Test
  void 기동_뒤_한_번도_같은_7일_규칙이다() {
    when(port.latest()).thenReturn(Optional.empty());
    job().afterStartup();
    verify(port).build(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 8, 9));
    when(port.latest())
        .thenReturn(Optional.of(new PopulationSnapshotPort.Latest(LocalDate.of(2026, 11, 1), "0")));
    job().afterStartup();
    verify(port).build(any(), any());
  }

  @Test
  void 가장_최근이_7일_지났으면_만들고_그_안이면_만들지_않는다() {
    when(port.latest())
        .thenReturn(
            Optional.of(new PopulationSnapshotPort.Latest(LocalDate.of(2026, 10, 26), "0")));
    assertThat(job().run()).isFalse();
    verify(port, never()).build(any(), any());
    when(port.latest())
        .thenReturn(
            Optional.of(new PopulationSnapshotPort.Latest(LocalDate.of(2026, 10, 25), "0")));
    assertThat(job().run()).isTrue();
    verify(port).build(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 8, 9));
  }
}
