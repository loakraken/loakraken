package com.loakraken.common.lostark;

import com.loakraken.common.api.BusinessException;

/**
 * LOST_ARK_RATE_LIMITED에 이유를 붙인다. 응답(오류 코드·상태)은 같고, 로그·지표에서만 "우리 관문이 미룸"과 "공식 서버가 429를 줌"을 가른다.
 */
public class LostArkRateLimitedException extends BusinessException {
  /**
   * GATE_BUDGET: 분당 예산에서 사용자 몫(reserve)을 남기려고 관문이 미룸. GATE_BACKOFF: 앞선 공식 한도 신호(429·남은 0) 뒤 리셋까지 관문이 기다림.
   * GATE_CONCURRENCY: 동시 호출 상한. UPSTREAM_429: 지금 공식 서버가 429를 줌.
   */
  public enum Cause {
    GATE_BUDGET,
    GATE_BACKOFF,
    GATE_CONCURRENCY,
    UPSTREAM_429
  }

  private final Cause cause;

  public LostArkRateLimitedException(Cause cause) {
    super(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    this.cause = cause;
  }

  public Cause limitCause() {
    return cause;
  }

  /** 로그용 이유. 이 예외가 아니면 OTHER. */
  public static String reasonOf(Throwable exception) {
    return exception instanceof LostArkRateLimitedException limited
        ? limited.limitCause().name()
        : "OTHER";
  }
}
