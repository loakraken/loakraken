package com.loakraken.market.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/** 시세 보드 등락률과 상승·하락 정렬 규칙(market-board-v1). */
public final class MarketBoardRules {
  public static final String RULE_VERSION = "market-board-v1";
  public static final String CHANGE_BASIS =
      "changeDay = 현재 최저가 ÷ 전일 평균가 − 1, changeWeek = 현재 최저가 ÷ 최근 완료일 최대 7일 거래량 가중 평균가 − 1 (%, 소수 첫째 자리)";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private MarketBoardRules() {}

  public enum Sort {
    DEFAULT,
    HIGH,
    LOW,
    RISE,
    FALL;

    public boolean scansWholeTab() {
      return this == RISE || this == FALL;
    }
  }

  /** 기준 가격이 없거나 0이면, 또는 현재 최저가가 없으면 null이다. */
  public static BigDecimal change(BigDecimal current, BigDecimal base) {
    if (current == null || base == null || base.signum() <= 0) return null;
    return current.subtract(base).multiply(HUNDRED).divide(base, 1, RoundingMode.HALF_UP);
  }

  public static BigDecimal changeDay(MarketPrice price) {
    return change(price.currentMinPrice(), price.yDayAvgPrice());
  }

  private static final String CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

  /** 한글 음절은 초성으로 바꾸고 나머지 글자는 그대로 둔다(예: '운명의 파괴석' → 'ㅇㅁㅇ ㅍㄱㅅ'). */
  public static String initials(String value) {
    var out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      out.append(c >= '가' && c <= '힣' ? CHOSEONG.charAt((c - '가') / 588) : c);
    }
    return out.toString();
  }

  /**
   * 시세판 이름 검색(#619). 공백·대소문자를 무시하고 이름에 검색어가 들어 있으면 일치. 검색어가 한글 자음(초성)만이면 이름의 초성에 들어 있어도 일치(예:
   * 'ㅇㅂㄷ' → '...운명의 ...' 처럼 앞이 아니어도). 검색어는 컨트롤러가 정리·검증한 값이다.
   */
  public static boolean nameMatches(String name, String query) {
    if (name == null || query == null) return false;
    var target = compact(name);
    var needle = compact(query);
    if (needle.isEmpty()) return false;
    if (target.contains(needle)) return true;
    return needle.chars().allMatch(c -> CHOSEONG.indexOf(c) >= 0)
        && initials(target).contains(needle);
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
  }

  /** RISE는 changeDay 내림차순, FALL은 오름차순. changeDay가 null인 품목은 맨 뒤, 같으면 이름·ID 순. */
  public static List<MarketPrice> sortByChange(List<MarketPrice> items, Sort sort) {
    if (!sort.scansWholeTab()) throw new IllegalArgumentException("등락 정렬이 아닙니다.");
    Comparator<BigDecimal> byValue =
        sort == Sort.RISE ? Comparator.reverseOrder() : Comparator.naturalOrder();
    return items.stream()
        .sorted(
            Comparator.comparing(MarketBoardRules::changeDay, Comparator.nullsLast(byValue))
                .thenComparing(MarketPrice::name)
                .thenComparingLong(MarketPrice::id))
        .toList();
  }
}
