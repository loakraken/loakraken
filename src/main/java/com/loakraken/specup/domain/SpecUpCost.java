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

  /** 재련 계획(업화·전율 일반 재련 11~25단계)이 다루지 않는 재련 후보(상급 재련, 계열을 모르는 장비). */
  public static final SpecUpCost REFINE_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "REFINE_COST_UNCONFIRMED",
          "재련 계획(업화·전율 일반 재련 11~25단계)이 다루지 않는 항목이라 비용을 계산하지 않았습니다.");

  /** 재련 재료 시세 예열 전이거나 필요한 재료 시세가 없을 때. code·status는 보석과 같은 PRICE_PENDING(새 값 없음). */
  public static final SpecUpCost REFINE_PRICE_PENDING =
      new SpecUpCost(
          "GOLD", null, "PENDING", "PRICE_PENDING", "재련 재료 시세를 아직 받지 못했습니다. 잠시 뒤 다시 확인해 주세요.");

  /** 일반 재련 +1 기대 비용(L30 후속): 재련 계획의 기대 소모 × 재료 1개 즉시 구매 최저가 + 기대 골드. 숨결 없음과 최대 사용 중 작은 쪽이다. */
  public static SpecUpCost refineExpected(long amount) {
    return new SpecUpCost(
        "GOLD",
        amount,
        "OK",
        "REFINE_EXPECTED",
        "재련 계획의 기대 소모(장인의 기운·실패 횟수 0에서 시작, 숨결을 쓰지 않을 때와 최대로 쓸 때 중 기대 비용이 작은 쪽, 업화 18단계까지 성장 지원 골드 20% 할인)에"
            + " 재료 1개 즉시 구매 최저가를 곱하고 골드를 더한 값입니다. 장비 성장(경험치) 비용은 넣지 않았습니다.");
  }

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

  /** 유물 각인 레벨 1당 각인서 권수(공식 가이드 '각인', 검증 표본 spec-up/cost-rules-2026-10-12.json, L30). */
  public static final int BOOKS_PER_LEVEL = 5;

  /** 각인서 시세 예열 전이거나 시세가 없을 때. code·status는 보석과 같은 PRICE_PENDING(새 값 없음, PM-백엔드 10-12 04:08). */
  public static final SpecUpCost BOOK_PRICE_PENDING =
      new SpecUpCost(
          "GOLD", null, "PENDING", "PRICE_PENDING", "각인서 시세를 아직 받지 못했습니다. 잠시 뒤 다시 확인해 주세요.");

  /** 유물 각인 +1 비용 = 5권 × 유물 각인서 1권 즉시 구매 최저가. */
  public static SpecUpCost engravingBooks(long unitPrice) {
    return new SpecUpCost(
        "GOLD",
        unitPrice * BOOKS_PER_LEVEL,
        "OK",
        "ENGRAVING_BOOK_BUY",
        "유물 각인서 5권(각인 레벨 1당 5권)의 즉시 구매 최저가 합입니다.");
  }

  /** 1만 골드당 전투력(소수 둘째 자리). 비용이 없거나 0 이하면 null. */
  public static BigDecimal efficiency(BigDecimal delta, Long amount) {
    if (delta == null || amount == null || amount <= 0) return null;
    return delta
        .multiply(BigDecimal.valueOf(10_000))
        .divide(BigDecimal.valueOf(amount), 2, RoundingMode.HALF_UP);
  }
}
