package com.loakraken.market.presentation;

import com.loakraken.common.api.SuccessCode;
import org.springframework.http.HttpStatus;

enum MarketSuccessCode implements SuccessCode {
  MARKET_SEARCH_SUCCESS,
  MARKET_OPTIONS_SUCCESS,
  MATERIAL_QUOTE_SUCCESS,
  MATERIAL_PRICE_SUCCESS,
  MARKET_ITEM_HISTORY_SUCCESS,
  MARKET_ITEM_MINUTES_SUCCESS,
  MARKET_BOARD_SUCCESS,
  MARKET_TABS_SUCCESS,
  AUCTION_GEMS_SUCCESS,
  AUCTION_ACCESSORIES_SUCCESS,
  AUCTION_LISTINGS_SUCCESS,
  AUCTION_SEARCH_SUCCESS;

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
      case MARKET_ITEM_MINUTES_SUCCESS -> "분 단위 시세 조회에 성공했습니다.";
      case MARKET_BOARD_SUCCESS -> "시세 보드 조회에 성공했습니다.";
      case MARKET_TABS_SUCCESS -> "시세 보드 탭 조회에 성공했습니다.";
      case AUCTION_GEMS_SUCCESS -> "보석 시세 조회에 성공했습니다.";
      case AUCTION_ACCESSORIES_SUCCESS -> "장신구 시세 조회에 성공했습니다.";
      case AUCTION_LISTINGS_SUCCESS -> "경매장 매물 조회에 성공했습니다.";
      case AUCTION_SEARCH_SUCCESS -> "장신구 경매 검색에 성공했습니다.";
    };
  }
}
