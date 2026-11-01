package com.loakraken.refining.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum RefiningErrorCode implements ErrorCode {
  /** 회차별 최적(breath=OPTIMAL)에 필요한 재료 시세가 아직 캐시에 없다. 다른 방식으로 대신 계산하지 않는다. */
  REFINING_PRICE_PENDING(
      HttpStatus.CONFLICT, "재련 재료 시세를 아직 받지 못해 회차별 최적 계획을 계산하지 않았습니다. 잠시 뒤 다시 요청해 주세요."),
  /** 회차별 최적 단계 풀이 자리가 모두 차 있다(캐시에 없는 풀이만 자리를 쓴다). 기다리지 않고 바로 돌려준다. */
  REFINING_SOLVE_BUSY(
      HttpStatus.TOO_MANY_REQUESTS, "재련 최적 계획 요청이 몰려 지금 계산하지 못했습니다. 잠시 뒤 다시 요청해 주세요."),
  /**
   * 현재 성공 확률이 그 단계의 기본 × (1 + 0.1 × 실패 0~10) 어느 값과도 같지 않다. 게임 화면 성공 확률은 올린 숨결·책을 더한 값이라, 올리기 전 값을
   * 받아야 실패 횟수가 정해진다.
   */
  REFINING_SUCCESS_RATE_MISMATCH(
      HttpStatus.BAD_REQUEST, "현재 성공 확률이 이 단계의 값과 맞지 않습니다. 숨결·책을 올리기 전 성공 확률을 넣어 주세요."),
  /**
   * 현재 성공 확률로 정한 실패 횟수와 장인의 기운이 맞지 않는다: 그 횟수만큼 실패했을 때 쌓일 수 있는 기운(매 시도 숨결·책 없이 ~ 매 시도 최대) 밖이다(다른 단계
   * 화면 값이거나 잘못 읽은 값).
   */
  REFINING_ARTISAN_ENERGY_MISMATCH(
      HttpStatus.BAD_REQUEST,
      "현재 성공 확률과 장인의 기운이 맞지 않습니다. 같은 단계 재련 화면에서 숨결·책을 올리기 전 두 값을 다시 확인해 주세요.");

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
