package com.loakraken.common.lostark;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum LostArkErrorCode implements ErrorCode {
  LOST_ARK_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "로스트아크 조회 서비스가 준비되지 않았습니다."),
  LOST_ARK_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "조회 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
  LOST_ARK_API_ERROR(HttpStatus.BAD_GATEWAY, "로스트아크 API 응답을 처리할 수 없습니다."),
  LOST_ARK_API_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "로스트아크 API 응답이 지연되고 있습니다.");

  private final HttpStatus httpStatus;
  private final String message;

  LostArkErrorCode(HttpStatus httpStatus, String message) {
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
