package com.loakraken.market.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MarketErrorCode implements ErrorCode {
  MARKET_CACHE_UNAVAILABLE;

  public HttpStatus httpStatus() {
    return HttpStatus.SERVICE_UNAVAILABLE;
  }

  public String code() {
    return name();
  }

  public String message() {
    return "거래소 캐시를 사용할 수 없습니다.";
  }
}
