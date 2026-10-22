package com.loakraken.home.infrastructure;

import com.loakraken.home.application.ShopNoticeStore;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 상점 공지 알림이 본 공지 주소 집합(Valkey SET). 알릴 때마다 보관 기간을 다시 건다. */
@Component
class ValkeyShopNoticeStore implements ShopNoticeStore {
  static final String SEEN = "home:v1:shop-notice:seen";
  static final String INITIALIZED = "home:v1:shop-notice:initialized";
  static final Duration KEEP = Duration.ofDays(120);

  private final StringRedisTemplate redis;

  ValkeyShopNoticeStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean initialized() {
    return Boolean.TRUE.equals(redis.hasKey(INITIALIZED));
  }

  @Override
  public Set<String> seen() {
    var members = redis.opsForSet().members(SEEN);
    return members == null ? Set.of() : Set.copyOf(members);
  }

  @Override
  public void remember(Collection<String> urls) {
    if (!urls.isEmpty()) {
      redis.opsForSet().add(SEEN, urls.toArray(String[]::new));
      redis.expire(SEEN, KEEP);
    }
    redis.opsForValue().set(INITIALIZED, "1", KEEP);
  }
}
