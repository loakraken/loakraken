package com.loakraken.specup.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SpecUpErrorCode implements ErrorCode {
  /** 플래그가 꺼져 있다(PM-프론트 16:27 ⑦: 캐시 없음 CHARACTER_NOT_CACHED와 구분해 웹이 메뉴를 숨긴다). */
  SPEC_UP_DISABLED(HttpStatus.NOT_FOUND, "스펙업 가이드는 아직 열리지 않았습니다."),
  /** 목표 전투력이 지금 전투력의 2배를 넘는다(L31 계산 상한). */
  SPEC_UP_GOAL_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "목표 전투력은 지금 전투력의 2배 이하로 입력해 주세요.");

  private final HttpStatus httpStatus;
  private final String message;

  SpecUpErrorCode(HttpStatus httpStatus, String message) {
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
