package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.TitleSnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 칭호 장착 확인 일별 스냅숏(칭호 통계 날짜 비교). 기동 2분 뒤 한 번과 매일 한 번, 오늘(KST) 스냅숏이 없으면 만든다. 여러 인스턴스 중 임대를 잡은 하나만 한다.
 * 공식 API를 부르지 않는다. 기본 꺼짐(stats.title-snapshot.enabled).
 *
 * <p>처리정지 목록이 바뀌어 지난 목록으로 만든 날짜가 남아 있으면 정해진 시각을 기다리지 않고(확인 주기 기본 10분) 지운 뒤 오늘을 다시 만든다. 그 사이 읽기는 지난
 * 목록 날짜를 주지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "stats.title-snapshot", name = "enabled", havingValue = "true")
class TitleSnapshotJob {
  private static final Logger log = LoggerFactory.getLogger(TitleSnapshotJob.class);
  static final String LEASE = "stats:lease:title-snapshot";
  private final String holder = java.util.UUID.randomUUID().toString();
  private final TitleSnapshotService snapshots;
  private final StringRedisTemplate redis;
  private final Clock clock;
  private final Timer timer;
  private final io.micrometer.core.instrument.Counter exclusionResets;
  private final AtomicLong lastSuccess = new AtomicLong();

  TitleSnapshotJob(
      TitleSnapshotService snapshots,
      StringRedisTemplate redis,
      Clock clock,
      MeterRegistry registry) {
    this.snapshots = snapshots;
    this.redis = redis;
    this.clock = clock;
    this.timer =
        Timer.builder("loakraken.stats.title_snapshot")
            .description("칭호 장착 확인 일별 스냅숏 한 번 만드는 데 걸린 시간")
            .register(registry);
    this.exclusionResets =
        io.micrometer.core.instrument.Counter.builder("loakraken.stats.title_snapshot_reset")
            .tag("reason", "exclusion")
            .description("처리정지 목록이 바뀌어 지난 목록으로 만든 칭호 스냅숏 날짜를 지우고 다시 만든 횟수")
            .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "title_snapshot")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
  }

  @Scheduled(cron = "${stats.title-snapshot.cron:0 20 5 * * *}", zone = "Asia/Seoul")
  void tick() {
    run("schedule");
  }

  /** 기동 뒤 한 번: 켠 날부터 기록이 생기게. 오늘 스냅숏이 있으면 아무것도 하지 않는다. */
  @Scheduled(
      initialDelayString = "${stats.title-snapshot.startup-delay:2m}",
      fixedDelay = Long.MAX_VALUE)
  void afterStartup() {
    run("startup");
  }

  /** 지난 처리정지 목록으로 만든 날짜가 남아 있을 때만 지우고 오늘을 다시 만든다(없으면 오늘 몫을 미리 만들지 않음). */
  @Scheduled(
      initialDelayString = "${stats.title-snapshot.exclusion-check:10m}",
      fixedDelayString = "${stats.title-snapshot.exclusion-check:10m}")
  void checkExclusion() {
    try {
      if (!snapshots.staleStored()) return;
    } catch (org.springframework.dao.DataAccessException exception) {
      log.warn(
          "event=title_snapshot_check_failed 처리정지 목록 변경을 확인하지 못했습니다. 다음 주기에 다시 합니다. 예외 유형={}",
          exception.getClass().getSimpleName());
      return;
    }
    run("exclusion");
  }

  /** 만들었으면 true. trigger = 이 실행을 연 곳(schedule·startup·exclusion). */
  boolean run(String trigger) {
    if (snapshots.builtToday()) return false;
    if (!hold(Duration.ofMinutes(10))) return false;
    long started = System.nanoTime();
    try {
      var built = snapshots.build();
      lastSuccess.set(clock.instant().getEpochSecond());
      log.info(
          "event=title_snapshot_built trigger={} keys={} rows={}",
          trigger,
          built.keys(),
          built.rows());
      if (built.resetDays() > 0) {
        exclusionResets.increment();
        // 캐릭터 이름·목록 값은 남기지 않는다(지운 날짜 수만).
        log.info(
            "event=title_snapshot_reset reason=exclusion trigger={} days={}",
            trigger,
            built.resetDays());
      }
      return true;
    } catch (org.springframework.dao.DataAccessException exception) {
      log.warn(
          "event=title_snapshot_failed trigger={} 칭호 일별 스냅숏을 만들지 못했습니다. 다음 날 다시 합니다. 예외 유형={}",
          trigger,
          exception.getClass().getSimpleName());
      return false;
    } finally {
      timer.record(Duration.ofNanos(System.nanoTime() - started));
    }
  }

  /** 임대를 잡거나(없으면) 이미 내 것이면 연장한다. Valkey 장애면 이번 주기를 건너뛴다. */
  private boolean hold(Duration ttl) {
    try {
      var ops = redis.opsForValue();
      if (Boolean.TRUE.equals(ops.setIfAbsent(LEASE, holder, ttl))) return true;
      if (!holder.equals(ops.get(LEASE))) return false;
      redis.expire(LEASE, ttl);
      return true;
    } catch (org.springframework.dao.DataAccessException exception) {
      log.warn("칭호 일별 스냅숏 임대를 확인하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return false;
    }
  }
}
