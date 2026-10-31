package com.loakraken.common.lostark;

import java.util.function.Supplier;

/**
 * 요청 하나 동안만 쓰는 사용자 공식 API 키(BYOK). {@link UserKeyFilter}가 요청 스레드에 두고 요청이 끝나면 지운다. 상속되지 않는
 * ThreadLocal이라 다른 스레드(수집기·예열·기록 작업자·비동기)는 보지 못한다.
 *
 * <p>키가 있어도 {@link #apply} 안에서만 쓴다. 지금은 캐릭터 검색·원정대 조회만 감싼다(그 응답은 공용 캐시·DB에 남기지 않는다). 그 밖의 공식
 * 호출(시세·홈·영수증 등)은 헤더가 와도 서버 키다.
 */
public final class UserKeyContext {
  private static final ThreadLocal<Key> AVAILABLE = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> APPLY = new ThreadLocal<>();

  private UserKeyContext() {}

  /** 키 원문과 그 키의 HMAC 앞 16바이트(헥스, 저장소 키·로그에는 이것만). toString은 원문을 내지 않는다. */
  record Key(String value, String id) {
    @Override
    public String toString() {
      return "UserKey[REDACTED]";
    }
  }

  static void enter(String value, String id) {
    AVAILABLE.set(new Key(value, id));
  }

  static void exit() {
    AVAILABLE.remove();
    APPLY.remove();
  }

  /** work 동안 이 요청의 사용자 키를 쓴다. 키가 없으면 그냥 실행한다. 같은 스레드 전용이다. */
  public static <T> T apply(Supplier<T> work) {
    if (AVAILABLE.get() == null) return work.get();
    Boolean previous = APPLY.get();
    APPLY.set(Boolean.TRUE);
    try {
      return work.get();
    } finally {
      if (previous == null) APPLY.remove();
      else APPLY.set(previous);
    }
  }

  /** 이 요청이 사용자 키를 가져왔는지({@link #apply} 밖에서도). 그 요청의 조회 기록(조회수 등)을 남기지 않을 때 쓴다. */
  public static boolean present() {
    return AVAILABLE.get() != null;
  }

  /** 지금 공식 호출에 사용자 키를 쓰는지(키가 있고 {@link #apply} 안). */
  public static boolean applied() {
    return Boolean.TRUE.equals(APPLY.get()) && AVAILABLE.get() != null;
  }

  /** 쓰는 키의 HMAC id(동시 요청 묶기 등 메모리 안 구분용). 쓰지 않으면 null. */
  public static String id() {
    return applied() ? AVAILABLE.get().id() : null;
  }

  /** 공식 호출의 Authorization에 넣을 키 원문. 쓰지 않으면 null. 로그·예외·저장소에 넣지 않는다. */
  public static String bearer() {
    return applied() ? AVAILABLE.get().value() : null;
  }
}
