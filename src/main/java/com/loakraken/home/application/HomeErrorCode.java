package com.loakraken.home.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum HomeErrorCode implements ErrorCode {
  HOME_CACHE_UNAVAILABLE,
  HOME_STORAGE_UNAVAILABLE;

  public HttpStatus httpStatus() {
    return HttpStatus.SERVICE_UNAVAILABLE;
  }

  public String code() {
    return name();
  }

  public String message() {
    return switch (this) {
      case HOME_CACHE_UNAVAILABLE -> "홈 일정·소식 캐시를 사용할 수 없습니다.";
      case HOME_STORAGE_UNAVAILABLE -> "패치 이력을 조회할 수 없습니다.";
    };
  }
}
