package com.loakraken.character.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * 아크 그리드 젬 실 효율(#520). 원본 점수식의 젬 실 효율 함수(크라켄 점수 엔진, 비교 기준 B)로 낸 값이다 — 지금 점수가 그 젬 옵션·포인트를 뺀 점수보다 몇 %
 * 높은지(소수 둘째 자리 내림). 기존 '전투력 기여'(공식 전투력 계수)와 정의가 다르다.
 *
 * <p>status가 UNSUPPORTED면 수치는 null이고 목록은 빈 배열이다. 계산할 수 없는 줄은 percent가 null이다(근사 없음).
 */
public record ArkGridRealEfficiency(
    Status status,
    String reasonCode,
    String reason,
    BigDecimal percent,
    BigDecimal optionsPercent,
    BigDecimal pointsPercent,
    List<Option> options,
    List<Series> series,
    String ruleVersion) {
  public static final String RULE_VERSION = "ark-grid-real-v1";

  public enum Status {
    OK,
    UNSUPPORTED
  }

  /** 젬 옵션 한 줄. inGamePercent는 공식 표기 %(없으면 null). */
  public record Option(String name, Integer level, BigDecimal inGamePercent, BigDecimal percent) {}

  /** 질서·혼돈 계열(그 계열 젬만 뺀 비). */
  public record Series(
      String series,
      BigDecimal percent,
      BigDecimal optionsPercent,
      BigDecimal pointsPercent,
      List<SeriesOption> options) {
    public Series {
      options = List.copyOf(options);
    }
  }

  /** 계열 안 옵션 한 줄(level은 그 계열 젬이 준 레벨 합). */
  public record SeriesOption(String name, Integer level, BigDecimal percent) {}

  public ArkGridRealEfficiency {
    options = options == null ? List.of() : List.copyOf(options);
    series = series == null ? List.of() : List.copyOf(series);
  }

  public static ArkGridRealEfficiency unsupported(String reasonCode, String reason) {
    return new ArkGridRealEfficiency(
        Status.UNSUPPORTED, reasonCode, reason, null, null, null, List.of(), List.of(), RULE_VERSION);
  }
}
