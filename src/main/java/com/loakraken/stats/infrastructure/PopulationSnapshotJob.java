package com.loakraken.stats.infrastructure;

import com.loakraken.stats.application.PopulationSnapshotPort;
import com.loakraken.stats.application.StatsPopulationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 전체 인구 주간 스냅샷(통계 묶음 B5). 기동 2분 뒤 한 번과 매일 한 번 보고, 스냅샷이 없거나 가장 최근 것이 7일 이상 지났으면 오늘(KST) 날짜로 새로 만든다(켠
 * 직후 첫 스냅샷, 그 뒤 주마다). 12주보다 오래된 스냅샷은 지운다. 여러 인스턴스 중 임대를 잡은 하나만 한다. 공식 API를 부르지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "stats.population-snapshot", name = "enabled", havingValue = "true")
class PopulationSnapshotJob {
  private static final Logger log = LoggerFactory.getLogger(PopulationSnapshotJob.class);
  static final String LEASE = "stats:lease:population-snapshot";
  static final ZoneId KST = StatsPopulationService.KST;
  static final int INTERVAL_DAYS = 7;
  static final int KEEP_WEEKS = StatsPopulationService.KEEP_WEEKS;
  private final String holder = java.util.UUID.randomUUID().toString();
  private final PopulationSnapshotPort snapshots;
  private final StringRedisTemplate redis;
  private final Clock clock;
  private final Timer timer;
  private final AtomicLong lastSuccess = new AtomicLong();

  PopulationSnapshotJob(
      PopulationSnapshotPort snapshots,
      StringRedisTemplate redis,
      Clock clock,
      MeterRegistry registry) {
    this.snapshots = snapshots;
    this.redis = redis;
    this.clock = clock;
    this.timer =
        Timer.builder("loakraken.stats.population_snapshot")
            .description("전체 인구 스냅샷 한 번 만드는 데 걸린 시간")
            .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "population_snapshot")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
  }

  @Scheduled(cron = "${stats.population-snapshot.cron:0 10 5 * * *}", zone = "Asia/Seoul")
  void tick() {
    run("schedule");
  }

  /**
   * 기동 뒤 한 번(배포·운영 10-31): 저녁에 켜도 다음 날 05:10까지 탭이 비지 않게. 7일 규칙이 그대로라 최근 스냅샷이 있으면 아무것도 하지 않고, 임대로 한
   * 인스턴스만 만든다. 기동을 막지 않게 스케줄러 스레드에서 돈다.
   */
  @Scheduled(
      initialDelayString = "${stats.population-snapshot.startup-delay:2m}",
      fixedDelay = Long.MAX_VALUE)
  void afterStartup() {
    run("startup");
  }

  /** 만들었으면 true. */
  boolean run() {
    return run("schedule");
  }

  /**
   * trigger = 이 실행을 연 곳(schedule·startup). 같은 스냅샷 만들기를 읽기 쪽(처리정지 목록이 바뀜, stop_list)도 부르므로 로그에 어디서
   * 만들었는지 남긴다.
   */
  boolean run(String trigger) {
    var today = LocalDate.now(clock.withZone(KST));
    var latest = snapshots.latest().map(PopulationSnapshotPort.Latest::date).orElse(null);
    if (latest != null && latest.isAfter(today.minusDays(INTERVAL_DAYS))) return false;
    if (!hold(Duration.ofMinutes(10))) return false;
    long started = System.nanoTime();
    try {
      snapshots.build(today, today.minusWeeks(KEEP_WEEKS));
      lastSuccess.set(clock.instant().getEpochSecond());
      log.info("event=population_snapshot_built trigger={} date={}", trigger, today);
      return true;
    } catch (org.springframework.dao.DataAccessException exception) {
      log.warn(
          "event=population_snapshot_failed trigger={} 전체 인구 스냅샷을 만들지 못했습니다. 다음 날 다시 합니다. 예외 유형={}",
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
      log.warn("전체 인구 스냅샷 임대를 확인하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return false;
    }
  }
}
