package com.loakraken.refining.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RefiningErrorCode implements ErrorCode {
  /** 회차별 최적(breath=OPTIMAL)에 필요한 재료 시세가 아직 캐시에 없다. 다른 방식으로 대신 계산하지 않는다. */
  REFINING_PRICE_PENDING(
      HttpStatus.CONFLICT, "재련 재료 시세를 아직 받지 못해 회차별 최적 계획을 계산하지 않았습니다. 잠시 뒤 다시 요청해 주세요."),
  /** 회차별 최적 단계 풀이 자리가 모두 차 있다(캐시에 없는 풀이만 자리를 쓴다). 기다리지 않고 바로 돌려준다. */
  REFINING_SOLVE_BUSY(
      HttpStatus.TOO_MANY_REQUESTS, "재련 최적 계획 요청이 몰려 지금 계산하지 못했습니다. 잠시 뒤 다시 요청해 주세요.");

  private final HttpStatus httpStatus;
  private final String message;

  RefiningErrorCode(HttpStatus httpStatus, String message) {
    this.httpStatus = httpStatus;
    this.message = message;
  }

  public HttpStatus httpStatus() {
    return httpStatus;
  }

  public String code() {
    return name();
  }

  public String message() {
    return message;
  }
}
