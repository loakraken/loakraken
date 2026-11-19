package com.loakraken.market.infrastructure;

import java.math.BigDecimal;

/**
 * 공식 거래소·경매장 응답의 가격(골드) 상한(보안 11-16 (b), #995 리뷰 뒤속). 고장 났거나 적대적인 상위 응답만 막는다. 정상 가격은 정수 자리 12
 * 안쪽(9,999억 골드 미만)이다.
 */
final class MarketPriceBounds {
  private MarketPriceBounds() {}

  /** 가격의 정수 자리 상한(저장 칸 NUMERIC(16, 4)와 같음). */
  static final int MAX_INTEGER_DIGITS = 12;

  /** 정수 가격 상한(정수 자리 12, 제외). */
  static final long MAX_AMOUNT_EXCLUSIVE = 1_000_000_000_000L;

  /** 가격 scale 범위(지수 표기 1e100000000 같은 값이 계산·직렬화에서 크게 펼쳐지지 않게). */
  static final int MIN_SCALE = -12;

  static final int MAX_SCALE = 8;

  /**
   * 가격이 받을 만한 값인가: 음수가 아니고, scale이 [MIN_SCALE, MAX_SCALE], 정수 자리가 MAX_INTEGER_DIGITS 이하. 값을 펼치지 않고
   * 잰다. null은 false.
   */
  static boolean plausible(BigDecimal price) {
    if (price == null || price.signum() < 0) return false;
    int scale = price.scale();
    if (scale < MIN_SCALE || scale > MAX_SCALE) return false;
    return price.precision() - scale <= MAX_INTEGER_DIGITS;
  }

  /** 정수 가격(경매장 즉시 구매가·입찰가)이 받을 만한 값인가: null(없음)이거나 0 이상 MAX_AMOUNT_EXCLUSIVE 미만. */
  static boolean plausibleOrAbsent(Long amount) {
    return amount == null || (amount >= 0 && amount < MAX_AMOUNT_EXCLUSIVE);
  }
}
