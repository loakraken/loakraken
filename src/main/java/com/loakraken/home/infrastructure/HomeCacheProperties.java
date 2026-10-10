package com.loakraken.home.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("home.cache")
public record HomeCacheProperties(
    @DefaultValue("30m") Duration calendarTtl, @DefaultValue("10m") Duration newsTtl) {
  public HomeCacheProperties {
    if (calendarTtl == null
        || newsTtl == null
        || calendarTtl.isZero()
        || calendarTtl.isNegative()
        || newsTtl.isZero()
        || newsTtl.isNegative()) throw new IllegalArgumentException("홈 캐시 TTL은 양수여야 합니다.");
  }
}
