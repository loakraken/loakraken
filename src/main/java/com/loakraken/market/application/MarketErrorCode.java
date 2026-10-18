package com.loakraken.market.application;

import com.loakraken.common.api.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MarketErrorCode implements ErrorCode {
  MARKET_CACHE_UNAVAILABLE,
  MARKET_ITEM_NOT_FOUND,
  INVALID_AUCTION_LEVELS,
  INVALID_AUCTION_FILTER,
  INVALID_GEM_FILTER,
  GEM_FILTER_DISABLED,
  MARKET_SEARCH_BUSY;

  public HttpStatus httpStatus() {
    return switch (this) {
      case MARKET_CACHE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case MARKET_ITEM_NOT_FOUND, GEM_FILTER_DISABLED -> HttpStatus.NOT_FOUND;
      case INVALID_AUCTION_LEVELS, INVALID_AUCTION_FILTER, INVALID_GEM_FILTER ->
          HttpStatus.BAD_REQUEST;
      case MARKET_SEARCH_BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
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
      case INVALID_AUCTION_FILTER -> "장신구 검색 조건이 올바르지 않습니다(부위에 없는 연마 효과, 같은 효과 두 번, 범위 밖 값).";
      case INVALID_GEM_FILTER ->
          "보석 매물 직업·스킬 조건이 올바르지 않습니다(목록에 없는 직업·스킬, 직업 없이 고른 스킬, 그 스킬로 찾을 수 없는 보석 종류).";
      case GEM_FILTER_DISABLED -> "보석 매물 직업·스킬 필터는 지금 쓸 수 없습니다.";
      case MARKET_SEARCH_BUSY -> "지금은 경매장 검색이 많습니다. 잠시 뒤 다시 시도해 주세요.";
    };
  }
}
