package com.loakraken.stats.infrastructure;

import com.loakraken.common.api.BusinessException;
import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.domain.StatsConditions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 수집품 보유율 예열(#305). 전체 집계가 원정대 1만 개에서 약 0.4초라(PM-백엔드 300ms 조건), 기본 조건(activeDays 기본값 × 전체·서버별)을 캐시
 * 수명(10분)보다 짧은 주기로 다시 집계해 사용자 요청은 늘 캐시에서 받게 한다. 공식 API는 부르지 않는다. 켜진 인스턴스끼리는 Valkey 임대(SET NX)로 한 곳만
 * 돈다(stats 모듈은 공식 API 쪽 모듈에 기대지 않는다).
 */
@Component
@ConditionalOnProperty(
    prefix = "ranking.collectibles",
    name = {"enabled", "warmup-enabled"},
    havingValue = "true")
class CollectibleOwnershipWarmup {
  private static final Logger log = LoggerFactory.getLogger(CollectibleOwnershipWarmup.class);
  static final String LEASE = "stats:lease:collectible-ownership-warmup";
  private final String holder = java.util.UUID.randomUUID().toString();
  private final StatsQueryService stats;
  private final StringRedisTemplate redis;
  private final Duration interval;
  private final Timer timer;
  private final AtomicLong lastSuccess = new AtomicLong();

  CollectibleOwnershipWarmup(
      StatsQueryService stats,
      StringRedisTemplate redis,
      MeterRegistry registry,
      @Value("${ranking.collectibles.warmup-interval:9m}") Duration interval) {
    this.stats = stats;
    this.redis = redis;
    this.interval = interval;
    this.timer =
        Timer.builder("loakraken.stats.warmup")
            .tag("job", "collectible_ownership")
            .description("수집품 보유율 예열 한 주기 소요 시간")
            .register(registry);
    io.micrometer.core.instrument.Gauge.builder(
            "loakraken.background.last_success", lastSuccess, AtomicLong::doubleValue)
        .tag("job", "collectible_ownership")
        .baseUnit("seconds")
        .description("마지막으로 성공한 시각(epoch 초). 0이면 아직 없음")
        .register(registry);
  }

  @Scheduled(
      fixedDelayString = "${ranking.collectibles.warmup-interval:9m}",
      initialDelayString = "${ranking.collectibles.warmup-initial-delay:1m}")
  void refresh() {
    if (!hold(interval.plusMinutes(1))) return;
    long started = System.nanoTime();
    try {
      var servers = new ArrayList<String>();
      servers.add(null);
      servers.addAll(stats.collectibleOwnershipServers());
      int days = StatsConditions.defaults().activeDays();
      for (var server : servers)
        stats.refreshCollectibleOwnership(
            new StatsConditions(server, null, null, days, null, null, null, null, false));
      lastSuccess.set(System.currentTimeMillis() / 1000);
    } catch (BusinessException exception) {
      log.warn("수집품 보유율 예열에 실패했습니다. 다음 주기에 다시 합니다. 코드={}", exception.errorCode());
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
      log.warn("수집품 보유율 예열 임대를 확인하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      return false;
    }
  }
}
