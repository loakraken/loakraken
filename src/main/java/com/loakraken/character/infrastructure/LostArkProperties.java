package com.loakraken.character.infrastructure;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lostark")
public record LostArkProperties(
    @DefaultValue("https://developer-lostark.game.onstove.com") URI baseUrl,
    @DefaultValue("") String apiKey,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("5s") Duration readTimeout,
    @DefaultValue("90") int requestsPerMinute,
    @DefaultValue("4") int maxConcurrentRequests,
    @DefaultValue("5m") Duration summaryTtl) {
  public LostArkProperties {
    if (connectTimeout.isNegative()
        || connectTimeout.isZero()
        || readTimeout.isNegative()
        || readTimeout.isZero()
        || summaryTtl.isNegative()
        || summaryTtl.isZero()
        || requestsPerMinute < 1
        || maxConcurrentRequests < 1) {
      throw new IllegalArgumentException("외부 API 타임아웃, TTL 및 호출 제한은 양수여야 합니다.");
    }
  }

  @Override
  public String toString() {
    return "LostArkProperties[apiKey=REDACTED]";
  }
}
