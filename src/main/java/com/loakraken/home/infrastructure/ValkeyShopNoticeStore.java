package com.loakraken.home.infrastructure;

import com.loakraken.home.application.ShopNoticeStore;
import java.util.Collection;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 상점 공지 알림이 본 공지 주소 집합(Valkey SET). 만료를 걸지 않는다 — volatile-lru에서 만료가 걸린 키는 메모리가 모자랄 때 지워질 수 있고, 지워지면
 * 같은 공지를 다시 알리기 때문이다(주 몇 건만 늘어 크기 걱정은 없음).
 */
@Component
class ValkeyShopNoticeStore implements ShopNoticeStore {
  static final String SEEN = "home:v1:shop-notice:seen";
  static final String INITIALIZED = "home:v1:shop-notice:initialized";
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
    if (!urls.isEmpty()) redis.opsForSet().add(SEEN, urls.toArray(String[]::new));
    redis.opsForValue().set(INITIALIZED, "1");
  }
}
