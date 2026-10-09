package com.loakraken.common.lostark;

import java.time.Duration;

/**
 * 켜진 인스턴스끼리도 백그라운드 작업(수집기·시세 예열)을 한 곳에서만 돌리는 임대. 배포 중 이전·새 인스턴스가 겹쳐도 한 곳만 돈다.
 *
 * <p>저장소 장애면 false다(백그라운드는 실행하지 않는다).
 */
public interface LeaderLease {
  /** 비어 있으면 얻고, 내가 가진 임대면 연장한다. 다른 인스턴스가 가졌으면 false. */
  boolean hold(String name, Duration ttl);

  /** 내가 가진 임대면 놓는다. */
  void release(String name);
}
