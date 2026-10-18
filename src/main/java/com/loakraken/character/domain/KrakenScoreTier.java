package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 크라켄 점수(비교 기준 B의 점수식) 티어(#522). 점수 컷 이상이면 그 티어다. 판정은 반올림 전 점수로 하고, 남은 점수는 소수 둘째 자리, 진행률은 정수 내림이다.
 * 최상위 티어는 다음 티어 값이 null이고 진행률 100이다.
 */
public final class KrakenScoreTier {
  private KrakenScoreTier() {}

  /** 데이터 파일(kraken-score/tiers.json). tiers는 min 오름차순, 첫 컷은 0. */
  public record Table(String ruleVersion, String source, List<Cut> tiers) {
    public Table {
      if (tiers == null || tiers.isEmpty()) throw new IllegalArgumentException("티어 표가 비었습니다");
      for (int i = 1; i < tiers.size(); i++)
        if (tiers.get(i).min().compareTo(tiers.get(i - 1).min()) <= 0)
          throw new IllegalArgumentException("티어 컷이 오름차순이 아닙니다");
      tiers = List.copyOf(tiers);
    }
  }

  public record Cut(String code, BigDecimal min) {}

  /**
   * nextCode·nextMin·remaining·remainingRounded는 최상위면 null. remainingRounded는 화면이 보이는 정수 남은 점수로, 원본
   * 화면처럼 둘째 자리로 반올림한 remaining을 다시 정수로 반올림한 값이다(원본 Number(x.toFixed(2)).toFixed(0) — 웹이 따로 반올림하지 않게
   * 서버가 준다).
   */
  public record Tier(
      String code,
      BigDecimal min,
      String nextCode,
      BigDecimal nextMin,
      BigDecimal remaining,
      Integer remainingRounded,
      int progressPercent) {}

  /**
   * 점수(반올림 전 double)의 티어. 원본처럼 double로 계산한다: 남은 점수는 (다음 컷 − 점수)를 double로 뺀 뒤 그 값을 정확한 십진수로 바꿔 둘째 자리
   * 반올림(toFixed(2)와 같음), 정수 남은 점수는 그 둘째 자리 값을 다시 .5는 위로 정수 반올림(원본 화면과 같은 두 번 반올림), 진행률은 ⌊(점수 − 현재
   * 컷) ÷ (다음 컷 − 현재 컷) × 100⌋. 음수·NaN·무한대는 티어가 없다(호출하는 쪽이 먼저 거름).
   */
  public static Tier of(Table table, double score) {
    if (!Double.isFinite(score) || score < 0)
      throw new IllegalArgumentException("티어를 정할 수 없는 점수입니다");
    var tiers = table.tiers();
    int index = 0;
    for (int i = 0; i < tiers.size(); i++) if (score >= tiers.get(i).min().doubleValue()) index = i;
    var current = tiers.get(index);
    if (index == tiers.size() - 1)
      return new Tier(current.code(), current.min(), null, null, null, null, 100);
    var next = tiers.get(index + 1);
    double from = current.min().doubleValue();
    double to = next.min().doubleValue();
    int progress = (int) Math.floor((score - from) / (to - from) * 100);
    return new Tier(
        current.code(),
        current.min(),
        next.code(),
        next.min(),
        remaining(to - score),
        screenInteger(to - score),
        Math.max(0, Math.min(progress, 100)));
  }

  /**
   * 남은 점수 둘째 자리: double의 정확한 십진 값을 반올림(JS toFixed(2)와 같음 — BigDecimal.valueOf는 짧은 표기라 1.005 같은 값에서
   * 다름).
   */
  static BigDecimal remaining(double diff) {
    return new BigDecimal(diff).setScale(2, RoundingMode.HALF_UP);
  }

  /** 화면 정수 남은 점수: 둘째 자리 값을 다시 정수로(원본 Number(diff.toFixed(2)).toFixed(0)). */
  static int screenInteger(double diff) {
    return remaining(diff).setScale(0, RoundingMode.HALF_UP).intValueExact();
  }
}
