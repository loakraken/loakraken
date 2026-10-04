package com.loakraken.gem.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum GemSuccessCode implements SuccessCode {
  GEM_SYNTHESIS_PLAN_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return "보석 합성 계획 계산에 성공했습니다.";
  }
}
