package com.loakraken.specup.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum SpecUpErrorCode implements ErrorCode {
  /** 플래그가 꺼져 있다(PM-프론트 16:27 ⑦: 캐시 없음 CHARACTER_NOT_CACHED와 구분해 웹이 메뉴를 숨긴다). */
  SPEC_UP_DISABLED(HttpStatus.NOT_FOUND, "스펙업 가이드는 아직 열리지 않았습니다.");

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
