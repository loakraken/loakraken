package com.loakraken.market.application;

import java.time.Duration;
import java.util.Optional;

/** T4 악세 경매 검색기(N13)의 결과 캐시와 기능 몫 카운터(Valkey, 인스턴스 공유). */
public interface AccessorySearchStorePort {
  Optional<AccessorySearch.Result> find(String key);

  void save(String key, AccessorySearch.Result result, Duration ttl);

  /**
   * 이번 분의 기능 몫에서 공식 호출 하나를 가져온다. 몫을 넘었거나 저장소 장애면 false(공식 예산 보호).
   *
   * @param limit 분당 상한
   */
  boolean tryAcquireQuota(int limit);
}
