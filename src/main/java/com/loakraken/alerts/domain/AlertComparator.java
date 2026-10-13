package com.loakraken.alerts.domain;

import java.math.BigDecimal;

/** 알림 조건(M33). 기준은 그 품목의 현재 판매 최저가다. */
public enum AlertComparator {
  /** 최저가가 기준 가격 이하. */
  AT_OR_BELOW,
  /** 최저가가 기준 가격 이상. */
  AT_OR_ABOVE;

  /** 가격이 없으면(매물 없음) 어느 쪽도 참이 아니다. */
  public boolean matches(BigDecimal currentMinPrice, BigDecimal threshold) {
    if (currentMinPrice == null) return false;
    int compared = currentMinPrice.compareTo(threshold);
    return this == AT_OR_BELOW ? compared <= 0 : compared >= 0;
  }
}
