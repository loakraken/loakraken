package com.loakraken.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 유물 각인서 시세 예열(L30). 시세 예열(market.warmup)이 켜진 인스턴스에서만 돌고, 예열 전체 분당 상한(max-calls-per-minute)을 함께 쓴다.
 *
 * @param enabled 기능 플래그(기본 꺼짐)
 * @param interval 전체 스캔 주기. 캐시 TTL은 이 값의 3배라 한두 번 실패해도 값이 남는다
 * @param maxPages 한 스캔의 페이지 상한(공식 10개씩, 2026-10-12 유물 각인서 43종 = 5페이지)
 */
@ConfigurationProperties("market.warmup.relic-books")
public record RelicBookWarmupProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("30m") Duration interval,
    @DefaultValue("10") int maxPages) {
  public RelicBookWarmupProperties {
    if (interval == null || interval.isZero() || interval.isNegative())
      throw new IllegalArgumentException("각인서 예열 주기는 양수여야 합니다.");
    if (maxPages < 1) throw new IllegalArgumentException("각인서 예열 페이지 상한은 1 이상이어야 합니다.");
  }

  /** 캐시 TTL = 주기 × 3. */
  public Duration cacheTtl() {
    return interval.multipliedBy(3);
  }
}
