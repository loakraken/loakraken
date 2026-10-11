package com.loakraken.specup.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 후보 비용(#315). unit은 금액 단위(문자열, 지금은 GOLD뿐, 스톤 등 개수 단위가 들어올 수 있음). status: OK(금액 있음), PENDING(시세 칸을
 * 아직 관측하지 못함), UNKNOWN(비용 근거가 없음). 같은 code는 늘 같은 basis 문장이다.
 */
public record SpecUpCost(String unit, Long amount, String status, String code, String basis) {
  public static final SpecUpCost PRICE_PENDING =
      new SpecUpCost(
          "GOLD", null, "PENDING", "PRICE_PENDING", "보석 시세를 아직 받지 못했습니다. 잠시 뒤 다시 확인해 주세요.");
  public static final SpecUpCost NO_PRICE_SOURCE =
      new SpecUpCost(
          "GOLD", null, "UNKNOWN", "NO_PRICE_SOURCE", "이 종류 보석은 시세 표에 없어 비용을 계산하지 않았습니다.");
  public static final SpecUpCost QUALITY_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "QUALITY_COST_UNCONFIRMED",
          "품질 업그레이드 비용(공식 확률·비용)을 아직 확인하지 않아 비용을 계산하지 않았습니다.");
  public static final SpecUpCost BOOK_COUNT_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "BOOK_COUNT_UNCONFIRMED",
          "각인 레벨당 필요한 각인서 수를 아직 확인하지 않아 비용을 계산하지 않았습니다.");

  /** 재련·상급 재련 후보(시뮬레이터가 계산하는 경우): 재련 비용은 아직 연결하지 않았다(L30 후속). */
  public static final SpecUpCost REFINE_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "REFINE_COST_UNCONFIRMED",
          "재련 비용(재련 계획의 기대 소모 × 재료 시세)을 아직 연결하지 않아 비용을 계산하지 않았습니다.");

  /**
   * 거래소(경매장) 판매 수수료: 판매 금액 전체의 5%, 1골드 미만 올림(계산 검증 10-11 20:35, 두 출처 이상). 레이드 경매 분배금 규칙(10만 골드
   * 초과분)과 다르다.
   */
  public static long exchangeSaleFee(long price) {
    return BigDecimal.valueOf(price)
        .multiply(EXCHANGE_FEE)
        .setScale(0, RoundingMode.CEILING)
        .longValue();
  }

  public static final BigDecimal EXCHANGE_FEE = new BigDecimal("0.05");

  /**
   * 보석 비용(PM-백엔드 10-11 판정 (a)): 목표 레벨 즉시 구매 최저가 − 지금 보석 판매 기대가(최저가 − 수수료 5% 올림). 지금 보석이 귀속이면 팔 수 없어
   * 빼지 않는다.
   */
  public static SpecUpCost gem(long nextPrice, Long currentPrice, boolean currentBound) {
    if (currentBound)
      return new SpecUpCost(
          "GOLD",
          nextPrice,
          "OK",
          "GEM_BUY_BOUND",
          "목표 레벨 보석의 즉시 구매 최저가입니다(지금 보석은 귀속이라 판매가를 빼지 않음).");
    if (currentPrice == null)
      return new SpecUpCost("GOLD", nextPrice, "OK", "GEM_BUY", "목표 레벨 보석의 즉시 구매 최저가입니다.");
    long sale = currentPrice - exchangeSaleFee(currentPrice);
    return new SpecUpCost(
        "GOLD",
        Math.max(0, nextPrice - sale),
        "OK",
        "GEM_UPGRADE",
        "목표 레벨 보석 즉시 구매 최저가에서 지금 보석의 판매 기대가(최저가에서 거래소 수수료 5%를 뺀 값)를 뺐습니다.");
  }

  /** 1만 골드당 전투력(소수 둘째 자리). 비용이 없거나 0 이하면 null. */
  public static BigDecimal efficiency(BigDecimal delta, Long amount) {
    if (delta == null || amount == null || amount <= 0) return null;
    return delta
        .multiply(BigDecimal.valueOf(10_000))
        .divide(BigDecimal.valueOf(amount), 2, RoundingMode.HALF_UP);
  }
}
