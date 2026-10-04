package com.loakraken.market.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("market.cache")
public record MarketCacheProperties(
    @DefaultValue("30s") Duration pricesTtl, @DefaultValue("10m") Duration optionsTtl) {
  public MarketCacheProperties {
    if (pricesTtl == null
        || optionsTtl == null
        || pricesTtl.isZero()
        || pricesTtl.isNegative()
        || optionsTtl.isZero()
        || optionsTtl.isNegative()) throw new IllegalArgumentException("거래소 캐시 TTL은 양수여야 합니다.");
  }
}
