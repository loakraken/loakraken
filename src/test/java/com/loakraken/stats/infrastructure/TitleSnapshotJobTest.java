package com.loakraken.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.stats.application.TitleSnapshotService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 칭호 일별 스냅숏 작업: 처리정지 목록이 바뀌어 지난 목록으로 만든 날짜가 남아 있으면 정해진 시각을 기다리지 않고 지우고 오늘을 다시 만든다. 그때 지표·로그를 캐릭터 이름
 * 없이 남긴다. 남은 날짜가 없으면 아무것도 하지 않는다(오늘 몫을 미리 만들지 않음).
 */
@ExtendWith(OutputCaptureExtension.class)
class TitleSnapshotJobTest {
  private final TitleSnapshotService service = mock(TitleSnapshotService.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private TitleSnapshotJob job() {
    return job(Clock.fixed(Instant.parse("2026-11-08T06:00:00Z"), ZoneOffset.UTC));
  }

  @SuppressWarnings("unchecked")
  private TitleSnapshotJob job(Clock clock) {
    var ops = (ValueOperations<String, String>) mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.setIfAbsent(eq(TitleSnapshotJob.LEASE), any(), any(java.time.Duration.class)))
        .thenReturn(true);
    return new TitleSnapshotJob(service, redis, clock, registry);
  }

  /** 시험에서 옮길 수 있는 시계. */
  private static final class MovableClock extends Clock {
    private Instant now;

    MovableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private double resets() {
    var counter =
        registry.find("loakraken.stats.title_snapshot_reset").tag("reason", "exclusion").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void 처리정지_목록이_바뀐_날짜가_남아_있으면_지우고_오늘을_다시_만들며_이름_없이_남긴다(CapturedOutput output) {
    when(service.staleStored()).thenReturn(true);
    when(service.builtToday()).thenReturn(false);
    when(service.build()).thenReturn(new TitleSnapshotService.Built(3, 12, 2));
    job().checkExclusion();
    verify(service).build();
    assertThat(resets()).isEqualTo(1);
    assertThat(output.getOut())
        .contains("event=title_snapshot_reset reason=exclusion trigger=exclusion days=2");
  }

  /** #1064 코드 리뷰 메모: 오늘이 지금 목록으로 이미 있으면 run이 바로 끝나 지난 목록 날짜가 다음 만들기까지 남았다. */
  @Test
  void 오늘이_지금_목록으로_있어도_지난_목록_날짜는_만들지_않고_지운다(CapturedOutput output) {
    when(service.staleStored()).thenReturn(true);
    when(service.builtToday()).thenReturn(true);
    when(service.purgeStale()).thenReturn(1);
    job().checkExclusion();
    verify(service).purgeStale();
    verify(service, never()).build();
    assertThat(resets()).isEqualTo(1);
    assertThat(output.getOut())
        .contains("event=title_snapshot_reset reason=exclusion trigger=purge days=1");
  }

  @Test
  void 남은_날짜가_없으면_만들지_않는다() {
    when(service.staleStored()).thenReturn(false);
    when(service.builtToday()).thenReturn(false);
    job().checkExclusion();
    verify(service, never()).build();
    assertThat(resets()).isZero();
  }

  private double retries(String outcome) {
    var counter =
        registry.find("loakraken.stats.title_snapshot_retry").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  /** #864 코드 리뷰 P3: 정해진 시각의 만들기가 실패하면 다음 날까지 기다리지 않고 확인 주기에 다시 만든다. */
  @Test
  void 오늘_만들기가_실패하면_확인_주기에_다시_만든다(CapturedOutput output) {
    when(service.staleStored()).thenReturn(false);
    when(service.builtToday()).thenReturn(false);
    when(service.build())
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("40001"))
        .thenReturn(new TitleSnapshotService.Built(3, 12, 0));
    var job = job();
    assertThat(job.run("schedule")).isFalse();
    assertThat(output.getOut())
        .contains(
            "event=title_snapshot_failed trigger=schedule retries=0 maxRetries=5 exceptionType=CannotAcquireLockException");
    job.checkExclusion();
    verify(service, org.mockito.Mockito.times(2)).build();
    assertThat(retries("built")).isEqualTo(1);
    assertThat(output.getOut()).contains("event=title_snapshot_built trigger=retry");
    // 만든 뒤에는 더 다시 하지 않는다.
    job.checkExclusion();
    verify(service, org.mockito.Mockito.times(2)).build();
  }

  @Test
  void 다시_만들기는_하루_다섯_번까지이고_다_실패하면_경고를_한_번_남긴다(CapturedOutput output) {
    when(service.staleStored()).thenReturn(false);
    when(service.builtToday()).thenReturn(false);
    when(service.build())
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("40001"));
    var job = job();
    job.run("schedule");
    for (int i = 0; i < 8; i++) job.checkExclusion();
    // 정해진 시각 1번 + 다시 만들기 5번.
    verify(service, org.mockito.Mockito.times(1 + TitleSnapshotJob.MAX_RETRIES_PER_DAY)).build();
    assertThat(retries("failed")).isEqualTo(TitleSnapshotJob.MAX_RETRIES_PER_DAY);
    assertThat(output.getOut().split("event=title_snapshot_retry_exhausted", -1)).hasSize(2);
  }

  @Test
  void 그사이_다른_곳이_오늘을_만들었으면_다시_만들지_않는다() {
    when(service.staleStored()).thenReturn(false);
    when(service.builtToday()).thenReturn(false);
    when(service.build())
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("40001"));
    var job = job();
    job.run("schedule");
    when(service.builtToday()).thenReturn(true);
    job.checkExclusion();
    verify(service, org.mockito.Mockito.times(1)).build();
    assertThat(retries("built") + retries("failed")).isZero();
  }

  /**
   * #1064 코드 리뷰 P3: 지난 목록 날짜가 계속 남아 있고 다시 만들기도 계속 실패하면, 처리정지 다시 만들기도 같은 하루 상한·지표로 센다(전에는 주기마다 제한
   * 없음).
   */
  @Test
  void 지난_목록_날짜가_계속_남고_만들기가_실패해도_하루_다섯_번까지만_다시_한다(CapturedOutput output) {
    when(service.staleStored()).thenReturn(true);
    when(service.builtToday()).thenReturn(false);
    when(service.build())
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("40001"));
    var job = job();
    for (int i = 0; i < 10; i++) job.checkExclusion();
    // 처음 1번(실패로 오늘을 기억) + 다시 만들기 5번.
    verify(service, org.mockito.Mockito.times(1 + TitleSnapshotJob.MAX_RETRIES_PER_DAY)).build();
    assertThat(retries("failed")).isEqualTo(TitleSnapshotJob.MAX_RETRIES_PER_DAY);
    assertThat(output.getOut().split("event=title_snapshot_retry_exhausted", -1)).hasSize(2);
    assertThat(output.getOut()).contains("event=title_snapshot_failed trigger=exclusion retries=1");
  }

  /** #1064 코드 리뷰 P3: KST 날이 바뀌면(15:00Z) 앞날의 다시 만들기 횟수를 버리고, 새 날 실패부터 다시 다섯 번까지 한다. */
  @Test
  void 날이_바뀌면_다시_만들기_횟수를_새로_센다() {
    when(service.staleStored()).thenReturn(false);
    when(service.builtToday()).thenReturn(false);
    when(service.build())
        .thenThrow(new org.springframework.dao.CannotAcquireLockException("40001"));
    var clock = new MovableClock(Instant.parse("2026-11-08T14:00:00Z")); // KST 11-08 23:00
    var job = job(clock);
    job.run("schedule");
    for (int i = 0; i < 8; i++) job.checkExclusion();
    verify(service, org.mockito.Mockito.times(1 + TitleSnapshotJob.MAX_RETRIES_PER_DAY)).build();
    // KST 11-09 00:10: 앞날의 실패는 다시 하지 않는다(새 날은 정해진 시각·기동 뒤에 만든다).
    clock.set(Instant.parse("2026-11-08T15:10:00Z"));
    job.checkExclusion();
    verify(service, org.mockito.Mockito.times(1 + TitleSnapshotJob.MAX_RETRIES_PER_DAY)).build();
    // 새 날 정해진 시각에 실패하면 그날 몫으로 다시 다섯 번까지.
    job.run("schedule");
    for (int i = 0; i < 8; i++) job.checkExclusion();
    verify(service, org.mockito.Mockito.times(2 * (1 + TitleSnapshotJob.MAX_RETRIES_PER_DAY)))
        .build();
    assertThat(retries("failed")).isEqualTo(2 * TitleSnapshotJob.MAX_RETRIES_PER_DAY);
  }

  @Test
  void 지운_날짜가_없는_보통_만들기는_초기화로_세지_않는다() {
    when(service.builtToday()).thenReturn(false);
    when(service.build()).thenReturn(new TitleSnapshotService.Built(3, 12, 0));
    assertThat(job().run("schedule")).isTrue();
    assertThat(resets()).isZero();
  }
}
