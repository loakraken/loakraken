package com.loakraken.common.lostark;

import java.time.Clock;
import java.util.function.Supplier;

/** 다른 패키지 테스트가 사용자 키(BYOK) 게이트·요청 문맥을 만들 때 쓴다. */
public final class UserKeyTestSupport {
  private UserKeyTestSupport() {}

  /** 메모리 저장소, 방문자 한도 꺼짐, 키별 분당 90·동시 2. */
  public static UserKeyGate gate(LostArkMetrics metrics) {
    return new UserKeyGate(
        new ByokProperties(true, "", 90, 2),
        ClientLimiter.DISABLED,
        new ClientLimitStore.Memory(),
        metrics,
        Clock.systemUTC());
  }

  /** 필터를 지난 것처럼 키를 두고 {@link UserKeyContext#apply} 안에서 work를 실행한 뒤 지운다. */
  public static <T> T withKey(String key, Supplier<T> work) {
    UserKeyContext.enter(key, "test-" + Integer.toHexString(key.hashCode()));
    try {
      return UserKeyContext.apply(work);
    } finally {
      UserKeyContext.exit();
    }
  }
}
