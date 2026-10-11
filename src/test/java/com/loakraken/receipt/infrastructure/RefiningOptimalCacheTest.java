package com.loakraken.receipt.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.refining.domain.RefiningOptimal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 회차별 최적 계산 캐시 빈(#292): 같은 키는 한 번만 계산하고 크기·적중률을 지표로 낸다. */
class RefiningOptimalCacheTest {
  @Test
  void 같은_키는_한_번만_계산하고_적중과_계산을_센다() {
    var registry = new SimpleMeterRegistry();
    var cache = new RefiningOptimalCache(registry);
    var computed = new AtomicInteger();
    var result = new RefiningOptimal.Result(100, false, false, List.of("기본"));
    for (int i = 0; i < 3; i++)
      assertThat(
              cache.get(
                  "업화:무기:15",
                  () -> {
                    computed.incrementAndGet();
                    return result;
                  }))
          .contains(result);
    // 시세가 없으면(null) 그 결과도 캐시해 다시 계산하지 않는다.
    assertThat(cache.get("전율:무기:19", () -> null)).isEmpty();
    assertThat(cache.get("전율:무기:19", () -> result)).isEmpty();
    assertThat(computed.get()).isEqualTo(1);
    assertThat(
            registry.get("loakraken.receipt.optimal.cache").tag("result", "hit").counter().count())
        .isEqualTo(3);
    assertThat(
            registry.get("loakraken.receipt.optimal.cache").tag("result", "miss").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("loakraken.receipt.optimal.cache.size").gauge().value()).isEqualTo(2);
  }

  @Test
  void 크기를_넘으면_오래_안_쓴_것부터_버린다() {
    var cache = new RefiningOptimalCache(new SimpleMeterRegistry());
    var result = new RefiningOptimal.Result(1, false, false, List.of());
    for (int i = 0; i <= RefiningOptimalCache.MAX_ENTRIES; i++) cache.get("k" + i, () -> result);
    assertThat(cache.size()).isEqualTo(RefiningOptimalCache.MAX_ENTRIES);
  }
}
