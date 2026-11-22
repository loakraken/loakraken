package com.loakraken.refining.application;

import com.loakraken.refining.domain.AdvancedRefineCost;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 상급 재련 기대 비용 결과 캐시(#1091 코드 리뷰 P2, 스위치 켜기 전 조건). 열쇠 = 장비 종류 · 현재 단계 · 목표 단계 · 재료 시세 관측 시각. 시세 관측
 * 시각이 바뀌면 지난 값을 모두 버리고(한 시세 안의 열쇠는 많아야 2 × 820), 최근에 쓴 순서로 {@value #MAX_ENTRIES}개까지 둔다. 계산(최악 0→40
 * 약 12ms)은 잠금 밖에서 하므로 같은 열쇠가 동시에 오면 둘 다 풀 수 있다(같은 값). 시세가 모자라 계산하지 못한 결과(null)는 넣지 않는다.
 */
@Component
public class AdvancedPlanCache {
  static final int MAX_ENTRIES = 2048;

  public record Key(String type, int fromStage, int toStage, Instant pricesObservedAt) {}

  private final Counter hits;
  private final Counter misses;
  private Instant snapshot;
  private final Map<Key, AdvancedRefineCost.Result> entries =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, AdvancedRefineCost.Result> eldest) {
          return size() > MAX_ENTRIES;
        }
      };

  public AdvancedPlanCache(MeterRegistry registry) {
    this.hits =
        Counter.builder("loakraken.refining.advanced_cache")
            .tag("outcome", "hit")
            .description("상급 재련 기대 비용을 결과 캐시에서 준 수")
            .register(registry);
    this.misses =
        Counter.builder("loakraken.refining.advanced_cache")
            .tag("outcome", "miss")
            .description("상급 재련 기대 비용을 새로 푼 수")
            .register(registry);
  }

  /** 캐시에 있으면 그 결과, 없으면 compute로 풀어 넣는다(null이면 넣지 않음). */
  public AdvancedRefineCost.Result get(Key key, Supplier<AdvancedRefineCost.Result> compute) {
    synchronized (this) {
      if (snapshot == null || !snapshot.equals(key.pricesObservedAt())) {
        entries.clear();
        snapshot = key.pricesObservedAt();
      }
      var cached = entries.get(key);
      if (cached != null) {
        hits.increment();
        return cached;
      }
    }
    misses.increment();
    var result = compute.get();
    if (result != null)
      synchronized (this) {
        if (key.pricesObservedAt().equals(snapshot)) entries.put(key, result);
      }
    return result;
  }
}
