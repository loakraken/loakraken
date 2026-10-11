package com.loakraken.stats.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.stats.application.StatsQueryService;
import com.loakraken.stats.domain.StatsConditions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 수집품 보유율 예열(#305): 임대를 잡은 인스턴스만 전체 + 서버별 기본 조건을 다시 집계한다. */
class CollectibleOwnershipWarmupTest {
  private final StatsQueryService stats = mock(StatsQueryService.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> ops = mock(ValueOperations.class);

  private CollectibleOwnershipWarmup warmup() {
    when(redis.opsForValue()).thenReturn(ops);
    return new CollectibleOwnershipWarmup(
        stats, redis, new SimpleMeterRegistry(), Duration.ofMinutes(9));
  }

  @Test
  void 임대를_잡으면_전체와_서버별_기본_조건을_다시_집계한다() {
    var warmup = warmup();
    when(ops.setIfAbsent(eq(CollectibleOwnershipWarmup.LEASE), anyString(), any(Duration.class)))
        .thenReturn(true);
    when(stats.collectibleOwnershipServers()).thenReturn(List.of("루페온", "카마인"));
    warmup.refresh();
    var captor = ArgumentCaptor.forClass(StatsConditions.class);
    verify(stats, org.mockito.Mockito.times(3)).refreshCollectibleOwnership(captor.capture());
    var servers = new ArrayList<String>();
    for (var conditions : captor.getAllValues()) {
      servers.add(conditions.server());
      assertThat(conditions.activeDays()).isEqualTo(StatsConditions.defaults().activeDays());
    }
    assertThat(servers).containsExactly(null, "루페온", "카마인");
  }

  @Test
  void 다른_인스턴스가_임대를_잡고_있으면_건너뛴다() {
    var warmup = warmup();
    when(ops.setIfAbsent(eq(CollectibleOwnershipWarmup.LEASE), anyString(), any(Duration.class)))
        .thenReturn(false);
    when(ops.get(CollectibleOwnershipWarmup.LEASE)).thenReturn("다른 인스턴스");
    warmup.refresh();
    verify(stats, never()).refreshCollectibleOwnership(any());
  }
}
