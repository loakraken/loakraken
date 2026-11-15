package com.loakraken.market.domain;

import java.time.LocalDate;

/**
 * 시세 기간 차트 범위(프론트 합의 11-13). value는 요청·응답의 문자열(7d·14d·30d·90d·365d). 범위는 기준일(KST)을 포함한 days일이다:
 * from = 기준일 − (days − 1).
 */
public enum MarketHistoryRange {
  D7("7d", 7),
  D14("14d", 14),
  D30("30d", 30),
  D90("90d", 90),
  D365("365d", 365);

  /** 요청 검증용 정규식(이 다섯 값만). */
  public static final String PATTERN = "7d|14d|30d|90d|365d";

  private final String value;
  private final int days;

  MarketHistoryRange(String value, int days) {
    this.value = value;
    this.days = days;
  }

  public String value() {
    return value;
  }

  public int days() {
    return days;
  }

  /** 기준일을 포함한 범위의 첫 날짜. */
  public LocalDate from(LocalDate baseDate) {
    return baseDate.minusDays(days - 1L);
  }

  /** 공식 14일 안에서 끝나는 범위(저장 표를 읽지 않음). */
  public boolean officialOnly() {
    return days <= 14;
  }

  /** null이면 14d(지금 응답과 같음). 다섯 값이 아니면 IllegalArgumentException. */
  public static MarketHistoryRange parse(String value) {
    if (value == null) return D14;
    for (var range : values()) if (range.value.equals(value)) return range;
    throw new IllegalArgumentException("range는 " + PATTERN + " 가운데 하나여야 합니다.");
  }
}
