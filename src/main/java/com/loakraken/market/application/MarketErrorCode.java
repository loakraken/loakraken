package com.loakraken.market.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MarketErrorCode implements ErrorCode {
  MARKET_CACHE_UNAVAILABLE,
  MARKET_ITEM_NOT_FOUND,
  INVALID_AUCTION_LEVELS;

  public HttpStatus httpStatus() {
    return switch (this) {
      case MARKET_CACHE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case MARKET_ITEM_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case INVALID_AUCTION_LEVELS -> HttpStatus.BAD_REQUEST;
    };
  }

  public String code() {
    return name();
  }

  public String message() {
    return switch (this) {
      case MARKET_CACHE_UNAVAILABLE -> "거래소 캐시를 사용할 수 없습니다.";
      case MARKET_ITEM_NOT_FOUND -> "공식 거래소에서 해당 상품을 찾을 수 없습니다.";
      case INVALID_AUCTION_LEVELS -> "보석 종류·레벨 또는 연마 효과 단계 조합이 올바르지 않습니다.";
    };
  }
}
