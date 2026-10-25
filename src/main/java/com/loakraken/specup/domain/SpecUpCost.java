package com.loakraken.specup.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 후보 비용(#315). unit은 금액 단위(문자열, 지금은 GOLD뿐, 스톤 등 개수 단위가 들어올 수 있음). status: OK(금액 있음), PENDING(시세 칸을
 * 아직 관측하지 못함), UNKNOWN(비용 근거가 없음). 같은 code는 늘 같은 basis 문장이다.
 */
public record SpecUpCost(
    String unit, Long amount, String status, String code, String basis, Double exact) {
  /** exact는 반올림 전 금액(카르마·스톤 기대 골드처럼 정수가 아닌 비용). 정수 비용이면 amount와 같다. 효율은 이 값으로 나눈다. */
  public SpecUpCost(String unit, Long amount, String status, String code, String basis) {
    this(unit, amount, status, code, basis, amount == null ? null : amount.doubleValue());
  }

  /** 깨달음 카르마 레벨 기대 골드(반올림 전 값으로 효율 계산). */
  public static SpecUpCost karma(double gold) {
    return new SpecUpCost(
        "GOLD",
        Math.round(gold),
        "OK",
        "KARMA_EXPECTED",
        "단계마다 성공 확률과 실패 시 장인의 기운(천장)으로 구한 기대 시도 수에 시도당 900골드를 곱해 더한 값입니다.",
        gold);
  }

  /** 어빌리티 스톤 목표 조합 기대 골드 = 기대 키트 수(1 ÷ 목표 조합 확률) × 돌 1개 50골드(반올림 전 값으로 효율 계산). */
  public static SpecUpCost stone(double gold) {
    return new SpecUpCost(
        "GOLD",
        Math.round(gold),
        "OK",
        "STONE_EXPECTED_KITS",
        "목표 조합이 나올 확률로 구한 기대 돌(키트) 수에 돌 1개 50골드를 곱한 값입니다(페온 제외).",
        gold);
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

  /** 재련 계획(업화·전율 일반 재련 11~25단계)이 다루지 않는 재련 후보(상급 재련, 계열을 모르는 장비). */
  public static final SpecUpCost REFINE_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "REFINE_COST_UNCONFIRMED",
          "재련 계획(업화·전율 일반 재련 11~25단계)이 다루지 않는 항목이라 비용을 계산하지 않았습니다.");

  /** 장신구 연마 교체(#575): 연마 옵션을 바꾼 장신구의 매물 시세를 아직 쓰지 않는다. */
  public static final SpecUpCost POLISH_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "POLISH_COST_UNCONFIRMED",
          "연마 옵션을 바꾼 장신구의 매물 시세를 아직 받지 않아 비용을 계산하지 않았습니다.");

  /**
   * 장신구 연마 교체 비용(비교 기준 B와 같은 규칙): 목표 연마 조합 장신구의 즉시 구매 최저가 − 지금 장신구 조합의 즉시 구매 최저가(지금 장신구가 거래 가능할
   * 때만), 0보다 작으면 0. 시세 칸은 고대·4티어·품질 조건 없음, 그 연마 값들이 있는 매물(다른 줄 허용).
   */
  public static SpecUpCost polish(long amount, boolean currentSubtracted) {
    return currentSubtracted
        ? new SpecUpCost(
            "GOLD",
            amount,
            "OK",
            "POLISH_UPGRADE",
            "목표 연마 조합 장신구의 즉시 구매 최저가에서 지금 장신구 조합의 즉시 구매 최저가를 뺐습니다(품질 조건 없음, 판매 수수료는 빼지 않음).")
        : new SpecUpCost(
            "GOLD",
            amount,
            "OK",
            "POLISH_BUY",
            "목표 연마 조합 장신구의 즉시 구매 최저가입니다(품질 조건 없음). 지금 장신구는 거래할 수 없어 빼지 않았습니다.");
  }

  /** 연마 조합 시세 칸을 아직 채우지 못했을 때(채우기 스위치가 켜져 있을 때만). */
  public static final SpecUpCost POLISH_PRICE_PENDING =
      new SpecUpCost(
          "GOLD", null, "PENDING", "PRICE_PENDING", "장신구 매물 시세를 아직 받지 못했습니다. 잠시 뒤 다시 확인해 주세요.");

  /** 목표 연마 조합 매물이 지금 없을 때. */
  public static final SpecUpCost POLISH_NO_LISTING =
      new SpecUpCost(
          "GOLD", null, "UNKNOWN", "POLISH_NO_LISTING", "이 연마 조합의 장신구 매물이 지금 없어 비용을 계산하지 않았습니다.");

  /** 어빌리티 스톤(#575): 목표 조합까지의 기대 세공 비용을 아직 넣지 않았다. */
  public static final SpecUpCost STONE_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "STONE_COST_UNCONFIRMED",
          "목표 조합 어빌리티 스톤을 만드는 기대 비용을 아직 확인하지 않아 비용을 계산하지 않았습니다.");

  /** 깨달음 카르마 레벨(#575). */
  public static final SpecUpCost KARMA_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "KARMA_COST_UNCONFIRMED",
          "깨달음 카르마 레벨을 올리는 비용(확률·골드)을 아직 확인하지 않아 비용을 계산하지 않았습니다.");

  /** 팔 보호구(완갑) 재련(#575). */
  public static final SpecUpCost ARM_GUARD_COST_UNCONFIRMED =
      new SpecUpCost(
          "GOLD",
          null,
          "UNKNOWN",
          "ARM_GUARD_COST_UNCONFIRMED",
          "팔 보호구 재련 비용(확률·재료)을 아직 확인하지 않아 비용을 계산하지 않았습니다.");

  /**
   * 팔 보호구(완갑) 재련 기대 비용: 단계마다 회차별 최적 전략(시도마다 쓸 숨결 수를 골라 기대 비용 최소, 용암·빙하는 싼 숨결부터)의 기대 소모 × 재료 1개 즉시
   * 구매 최저가 + 기대 골드의 합. 성장 지원·책은 없다.
   */
  public static SpecUpCost armGuardExpected(long amount) {
    return new SpecUpCost(
        "GOLD",
        amount,
        "OK",
        "ARM_GUARD_EXPECTED",
        "단계마다 시도마다 숨결(용암·빙하) 사용 개수를 골라 기대 비용이 가장 작은 방법(장인의 기운·실패 횟수는 각 단계 0에서 시작)의 기대 소모에 재료 1개 즉시 구매 최저가를 곱하고 골드를 더한 값입니다."
            + " 장비 성장(경험치) 비용과 실링은 넣지 않았습니다.");
  }

  /** 재련 재료 시세 예열 전이거나 필요한 재료 시세가 없을 때. code·status는 보석과 같은 PRICE_PENDING(새 값 없음). */
  public static final SpecUpCost REFINE_PRICE_PENDING =
      new SpecUpCost(
          "GOLD", null, "PENDING", "PRICE_PENDING", "재련 재료 시세를 아직 받지 못했습니다. 잠시 뒤 다시 확인해 주세요.");

  /**
   * 일반 재련 기대 비용: 단계마다 회차별 최적 전략(시도마다 숨결·책 사용을 골라 기대 비용 최소, 장인의 기운·실패 횟수는 각 단계 0에서 시작)의 기대 소모 × 재료
   * 1개 즉시 구매 최저가 + 기대 골드의 합. 업화(티어 4) 1~18단계는 성장 지원으로 재련 돌파 골드 20% 감소(재료·실링은 그대로) — 공식 공지 13307
   * (2025-12-10)과 게임 가이드 '재련'(2026-07-14 수정, 10-25 확인)의 현재 규칙.
   */
  public static SpecUpCost refineExpected(long amount) {
    return new SpecUpCost(
        "GOLD",
        amount,
        "OK",
        "REFINE_EXPECTED",
        "단계마다 시도마다 숨결·책 사용을 골라 기대 비용이 가장 작은 방법(장인의 기운·실패 횟수는 각 단계 0에서 시작)의 기대 소모에 재료 1개 즉시 구매 최저가를 곱하고 골드를 더한 값입니다."
            + " 업화 18단계까지는 성장 지원 20% 적용(재련 골드만). 장비 성장(경험치) 비용은 넣지 않았습니다.");
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
   * 보석 비용(10-18 판정, 10-11 판정 (a) 대체): 목표 레벨 즉시 구매 최저가 − 지금 보석 즉시 구매 최저가(판매 수수료는 빼지 않음 — 수수료를 넣은 실제
   * 부담은 줄 상세 SELL 줄의 sellFeeGold). 지금 보석이 귀속이면 팔 수 없어 빼지 않는다. 0보다 작으면 0.
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
    return new SpecUpCost(
        "GOLD",
        Math.max(0, nextPrice - currentPrice),
        "OK",
        "GEM_UPGRADE",
        "목표 레벨 보석 즉시 구매 최저가에서 지금 보석의 즉시 구매 최저가를 뺐습니다(판매 수수료는 빼지 않음).");
  }

  /** 유물 각인 레벨 1당 각인서 권수(공식 가이드 '각인', 검증 표본 spec-up/cost-rules-2026-10-12.json, L30). */
  public static final int BOOKS_PER_LEVEL = 5;

  /** 각인서 시세 예열 전이거나 시세가 없을 때. code·status는 보석과 같은 PRICE_PENDING(새 값 없음, PM-백엔드 10-12 04:08). */
  public static final SpecUpCost BOOK_PRICE_PENDING =
      new SpecUpCost(
          "GOLD", null, "PENDING", "PRICE_PENDING", "각인서 시세를 아직 받지 못했습니다. 잠시 뒤 다시 확인해 주세요.");

  /** 유물 각인 +1 비용 = 5권 × 유물 각인서 1권 즉시 구매 최저가. */
  public static SpecUpCost engravingBooks(long unitPrice) {
    return engravingBooks(unitPrice, 1);
  }

  /** 유물 각인 +levels 비용 = 5권 × levels × 1권 즉시 구매 최저가(다단계 누적 줄). 같은 code는 같은 문장. */
  public static SpecUpCost engravingBooks(long unitPrice, int levels) {
    return new SpecUpCost(
        "GOLD",
        unitPrice * BOOKS_PER_LEVEL * levels,
        "OK",
        "ENGRAVING_BOOK_BUY",
        "유물 각인서(각인 레벨 1당 5권, 올리는 레벨 수만큼)의 즉시 구매 최저가 합입니다.");
  }

  /** 1만 골드당 전투력(소수 둘째 자리). 비용이 없거나 0 이하면 null. */
  public static BigDecimal efficiency(BigDecimal delta, Long amount) {
    if (delta == null || amount == null || amount <= 0) return null;
    return delta
        .multiply(BigDecimal.valueOf(10_000))
        .divide(BigDecimal.valueOf(amount), 2, RoundingMode.HALF_UP);
  }
}
