package com.loakraken.common.lostark;

import java.time.Instant;

/**
 * 공식 API 호출 예산의 60초 창과 공식 한도 대기 시각. 인스턴스 합산(Valkey) 또는 단일 인스턴스(메모리)로 둔다.
 *
 * <p>구현은 판정과 기록을 원자적으로 한다. 저장소 장애는 {@link GateUnavailableException}으로 알린다.
 */
public interface GateStore {
  enum Admission {
    ADMITTED,
    BUDGET,
    BACKOFF
  }

  /** 최근 60초 기록 수가 budget − reserve 미만이고 대기 중이 아니면 기록하고 ADMITTED. */
  Admission admit(int budget, int reserve);

  /** 기록 없이 같은 조건만 본다. */
  boolean available(int budget, int reserve);

  /** 지금 기록된 대기 시각보다 늦을 때만 바꾼다. */
  void blockUntil(Instant until);

  class GateUnavailableException extends RuntimeException {
    public GateUnavailableException(Throwable cause) {
      super("호출 게이트 저장소를 사용할 수 없습니다.", cause);
    }
  }
}
