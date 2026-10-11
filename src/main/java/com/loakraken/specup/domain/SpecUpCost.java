package com.loakraken.specup.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 후보 비용(#315). unit은 금액 단위(문자열, 지금은 GOLD뿐, 스톤 등 개수 단위가 들어올 수 있음). status: OK(금액 있음), PENDING(시세 칸을
 * 아직 관측하지 못함), UNKNOWN(비용 근거가 없음). 같은 code는 늘 같은 basis 문장이다.
 */
public record SpecUpCost(String unit, Long gold, String status, String code, String basis) {
  /**
   * 경매장 판매 수수료: 판매 금액 가운데 10만 골드를 넘는 부분의 5%(올림). 게임 내 분배금 기준이며 성장 도구 경매 계산과 같은 규칙이다(PM 총괄 판정
   * 10-11).
   */
  public static final BigDecimal AUCTION_FEE = new BigDecimal("0.05");

  public static final long AUCTION_FEE_FREE = 100_000;

  /** 판매 금액에서 떼는 수수료(골드). */
  public static long auctionFee(long price) {
    if (price <= AUCTION_FEE_FREE) return 0;
    return BigDecimal.valueOf(price - AUCTION_FEE_FREE)
        .multiply(AUCTION_FEE)
        .setScale(0, RoundingMode.CEILING)
        .longValue();
  }

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

  /**
   * 보석 +1 비용(결정 ① (a), 잠정): 다음 레벨 즉시 구매 최저가 − 현재 보석 판매 기대가(최저가 − 10만 골드 초과분의 5% 올림). 현재 보석이 귀속이면 팔
   * 수 없어 판매가를 빼지 않는다.
   */
  public static SpecUpCost gem(long nextPrice, Long currentPrice, boolean currentBound) {
    if (currentBound || currentPrice == null)
      return new SpecUpCost(
          "GOLD",
          nextPrice,
          "OK",
          currentBound ? "GEM_BUY_BOUND" : "GEM_BUY",
          currentBound
              ? "다음 레벨 보석 즉시 구매 최저가입니다(지금 보석은 귀속이라 판매가를 빼지 않음)."
              : "다음 레벨 보석 즉시 구매 최저가입니다.");
    long fee = auctionFee(currentPrice);
    return new SpecUpCost(
        "GOLD",
        Math.max(0, nextPrice - (currentPrice - fee)),
        "OK",
        "GEM_UPGRADE",
        "다음 레벨 보석 즉시 구매 최저가에서 지금 보석의 판매 기대가(최저가에서 10만 골드 초과분의 수수료 5%를 뺀 값, 게임 내 분배금 기준)를 뺐습니다.");
  }

  /** 1만 골드당 전투력(소수 둘째 자리). 비용이 없거나 0 이하면 null. */
  public static BigDecimal efficiency(BigDecimal delta, Long gold) {
    if (delta == null || gold == null || gold <= 0) return null;
    return delta
        .multiply(BigDecimal.valueOf(10_000))
        .divide(BigDecimal.valueOf(gold), 2, RoundingMode.HALF_UP);
  }
}
