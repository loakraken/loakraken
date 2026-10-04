package com.loakraken.market.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum MarketSuccessCode implements SuccessCode {
  MARKET_SEARCH_SUCCESS,
  MARKET_OPTIONS_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return this == MARKET_OPTIONS_SUCCESS ? "거래소 검색 옵션 조회에 성공했습니다." : "거래소 가격 조회에 성공했습니다.";
  }
}
