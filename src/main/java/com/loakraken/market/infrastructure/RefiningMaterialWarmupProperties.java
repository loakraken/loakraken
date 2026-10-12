package com.loakraken.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * T4 재련 재료 시세 예열(L30 후속, 스펙업 재련 비용). 시세 예열(market.warmup)이 켜진 인스턴스에서만 돌고, 예열 전체 분당 상한을 함께 쓴다. 한 번에
 * 재련 재료·추가 재료 탭을 다 받는다(2026-10-12 기준 11종 2페이지 + 22종 3페이지).
 *
 * @param enabled 기능 플래그(기본 꺼짐)
 * @param interval 전체 스캔 주기. 캐시 TTL은 이 값의 3배라 한두 번 실패해도 값이 남는다
 * @param maxPagesPerTab 탭마다 페이지 상한(공식 10개씩)
 */
@ConfigurationProperties("market.warmup.refining-materials")
public record RefiningMaterialWarmupProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("10m") Duration interval,
    @DefaultValue("5") int maxPagesPerTab) {
  public RefiningMaterialWarmupProperties {
    if (interval == null || interval.isZero() || interval.isNegative())
      throw new IllegalArgumentException("재련 재료 예열 주기는 양수여야 합니다.");
    if (maxPagesPerTab < 1) throw new IllegalArgumentException("재련 재료 예열 페이지 상한은 1 이상이어야 합니다.");
  }

  /** 캐시 TTL = 주기 × 3. */
  public Duration cacheTtl() {
    return interval.multipliedBy(3);
  }
}
