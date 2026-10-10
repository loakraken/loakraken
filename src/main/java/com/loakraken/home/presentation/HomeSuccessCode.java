package com.loakraken.home.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum HomeSuccessCode implements SuccessCode {
  HOME_SCHEDULE_SUCCESS,
  HOME_NEWS_SUCCESS,
  HOME_PATCH_NOTES_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return switch (this) {
      case HOME_SCHEDULE_SUCCESS -> "홈 일정 조회에 성공했습니다.";
      case HOME_NEWS_SUCCESS -> "홈 소식 조회에 성공했습니다.";
      case HOME_PATCH_NOTES_SUCCESS -> "패치 이력 조회에 성공했습니다.";
    };
  }
}
