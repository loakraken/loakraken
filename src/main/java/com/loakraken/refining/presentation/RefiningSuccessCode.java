package com.loakraken.refining.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum RefiningSuccessCode implements SuccessCode {
  REFINING_PLAN_SUCCESS,
  REFINING_PLAN_TOTAL_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return "재련 기대 비용 계산에 성공했습니다.";
  }
}
