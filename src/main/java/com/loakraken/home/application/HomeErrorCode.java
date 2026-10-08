package com.loakraken.home.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum HomeErrorCode implements ErrorCode {
  HOME_CACHE_UNAVAILABLE;

  public HttpStatus httpStatus() {
    return HttpStatus.SERVICE_UNAVAILABLE;
  }

  public String code() {
    return name();
  }

  public String message() {
    return "홈 일정·소식 캐시를 사용할 수 없습니다.";
  }
}
