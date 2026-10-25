package com.loakraken.receipt.infrastructure;

import com.loakraken.refining.domain.OptimalStageCache;
import com.loakraken.refining.domain.RefiningOptimal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 회차별 최적 계산 결과를 요청 사이에 나눠 쓰는 캐시(#292, PM-백엔드 #287 P2). 오래 안 쓴 것부터 버리고 최대 512개를 둔다(결과 하나는 회차별 선택 경로
 * 정도라 작다). 지표: 크기 {@code loakraken.receipt.optimal.cache.size}, 적중·계산 {@code
 * loakraken.receipt.optimal.cache{result=hit|miss}}.
 */
@Component
class RefiningOptimalCache implements OptimalStageCache {
  static final int MAX_ENTRIES = 512;
  private final Map<String, Optional<RefiningOptimal.Result>> entries =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(
            Map.Entry<String, Optional<RefiningOptimal.Result>> eldest) {
          return size() > MAX_ENTRIES;
        }
      };
  private final Counter hits;
  private final Counter misses;

  RefiningOptimalCache(MeterRegistry registry) {
    hits = registry.counter("loakraken.receipt.optimal.cache", "result", "hit");
    misses = registry.counter("loakraken.receipt.optimal.cache", "result", "miss");
    Gauge.builder("loakraken.receipt.optimal.cache.size", this, RefiningOptimalCache::size)
        .description("회차별 최적 계산 캐시에 든 결과 수")
        .register(registry);
  }

  @Override
  public Optional<RefiningOptimal.Result> get(
      String key, Supplier<RefiningOptimal.Result> compute) {
    synchronized (entries) {
      var cached = entries.get(key);
      if (cached != null) {
        hits.increment();
        return cached;
      }
    }
    // 계산은 잠금 밖에서 한다(약 10~60ms). 같은 키를 동시에 계산하면 마지막 결과를 둔다(값은 같다).
    misses.increment();
    var computed = Optional.ofNullable(compute.get());
    synchronized (entries) {
      entries.put(key, computed);
    }
    return computed;
  }

  int size() {
    synchronized (entries) {
      return entries.size();
    }
  }
}
