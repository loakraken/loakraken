package com.loakraken.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 생활 분류(90000) 전체 시세 예열(재료 시세 P0). 시세 예열(market.warmup)이 켜진 인스턴스에서만 돌고, 예열 전체 분당 상한을 함께 쓴다. 한 번에 생활
 * 분류를 다 받는다(2026-10-14 공식 TotalCount 34종, 4페이지). 재료 시세 API는 이 캐시에 있는 이름을 공식 호출 없이 답한다.
 *
 * @param enabled 기본 켜짐(시세 예열이 켜진 인스턴스에서만 돈다)
 * @param interval 전체 스캔 주기. 캐시 TTL은 이 값의 3배라 한두 번 실패해도 값이 남는다
 * @param maxPages 페이지 상한(공식 10개씩)
 */
@ConfigurationProperties("market.warmup.life-materials")
public record LifeMaterialWarmupProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("10m") Duration interval,
    @DefaultValue("6") int maxPages) {
  public LifeMaterialWarmupProperties {
    if (interval == null || interval.isZero() || interval.isNegative())
      throw new IllegalArgumentException("생활 재료 예열 주기는 양수여야 합니다.");
    if (maxPages < 1) throw new IllegalArgumentException("생활 재료 예열 페이지 상한은 1 이상이어야 합니다.");
  }

  /** 캐시 TTL = 주기 × 3. */
  public Duration cacheTtl() {
    return interval.multipliedBy(3);
  }
}
