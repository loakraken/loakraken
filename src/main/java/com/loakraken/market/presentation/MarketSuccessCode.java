package com.loakraken.market.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum MarketSuccessCode implements SuccessCode {
  MARKET_SEARCH_SUCCESS,
  MARKET_OPTIONS_SUCCESS,
  MATERIAL_QUOTE_SUCCESS,
  MATERIAL_PRICE_SUCCESS,
  MARKET_ITEM_HISTORY_SUCCESS,
  MARKET_BOARD_SUCCESS,
  MARKET_TABS_SUCCESS;

  public HttpStatus httpStatus() {
    return HttpStatus.OK;
  }

  public String code() {
    return name();
  }

  public String message() {
    return switch (this) {
      case MARKET_OPTIONS_SUCCESS -> "거래소 검색 옵션 조회에 성공했습니다.";
      case MARKET_SEARCH_SUCCESS -> "거래소 가격 조회에 성공했습니다.";
      case MATERIAL_QUOTE_SUCCESS -> "재료 구매 견적 계산에 성공했습니다.";
      case MATERIAL_PRICE_SUCCESS -> "재료 단가 조회에 성공했습니다.";
      case MARKET_ITEM_HISTORY_SUCCESS -> "상품 시세 이력 조회에 성공했습니다.";
      case MARKET_BOARD_SUCCESS -> "시세 보드 조회에 성공했습니다.";
      case MARKET_TABS_SUCCESS -> "시세 보드 탭 조회에 성공했습니다.";
    };
  }
}
