package com.loakraken.stats.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 통계·랭킹 규칙(stats-v1).
 *
 * <p>모집단은 LOAKraken이 관측한 캐릭터 중 최근 activeDays일 안에 프로필(전투력)을 확인한 캐릭터다. 공식 API에 마지막 접속 시각이 없어 "최근
 * 확인"으로 활동을 근사한다. 게임 전체 인구가 아니다.
 */
public final class StatsRules {
  public static final String RULE_VERSION = "stats-v1";
  public static final String SNAPSHOT_LATEST = "latest";
  public static final int MIN_SAMPLE = 30;

  /** 범위·구간 폭·백분위 값의 소수 자릿수(#108). */
  public static final int VALUE_SCALE = 2;

  public static final BigDecimal DEFAULT_ITEM_LEVEL_MIN = new BigDecimal("1640");
  public static final int DEFAULT_ACTIVE_DAYS = 30;
  public static final Set<Integer> ACTIVE_DAYS = Set.of(7, 30, 90);

  /** 딜러·서포터를 모두 할 수 있는 직업. 서포터 비율은 이 직업만 준다. */
  public static final Set<String> SUPPORT_CLASSES = Set.of("바드", "홀리나이트", "도화가", "발키리");

  private StatsRules() {}

  public enum Role {
    DEALER,
    SUPPORT
  }

  public enum Metric {
    COMBAT_POWER("combat_power"),
    ITEM_LEVEL("item_level");

    private final String column;

    Metric(String column) {
      this.column = column;
    }

    /** SQL 열 이름. 사용자 입력이 아니라 enum 상수에서만 나온다. */
    public String column() {
      return column;
    }
  }

  public enum SampleStatus {
    COLLECTING,
    READY;

    public static SampleStatus of(long count) {
      return count >= MIN_SAMPLE ? READY : COLLECTING;
    }
  }

  /** 상위 백분위 경계. 이름은 프론트가 매핑한다(PM 결정 2026-10-08). */
  public enum Tier {
    TOP1(1),
    TOP5(5),
    TOP10(10),
    TOP25(25),
    TOP50(50),
    REST(100);

    private final int upperPercent;

    Tier(int upperPercent) {
      this.upperPercent = upperPercent;
    }

    public int upperPercent() {
      return upperPercent;
    }

    public static Tier of(BigDecimal topPercent) {
      for (var tier : values())
        if (topPercent.compareTo(BigDecimal.valueOf(tier.upperPercent)) <= 0) return tier;
      return REST;
    }

    /** 한 단계 높은 티어. TOP1이면 null. */
    public Tier next() {
      return ordinal() == 0 ? null : values()[ordinal() - 1];
    }
  }

  /**
   * 다음 티어(L2) 경계 순위 K = ⌊U × total / 100⌋(U = 그 티어 상한 %). U가 정수라 topPercent의 소수 둘째 자리 올림을 거쳐도
   * {@code ceil₂(rank×100/total) ≤ U ⇔ rank ≤ K}다. 0이면 인원이 적어 누구도 그 티어에 닿을 수 없다.
   */
  public static long tierRank(Tier tier, long total) {
    return cutoffRank(tier.upperPercent(), total);
  }

  /** 분포 위 '상위 N%' 기준값을 주는 N(오름차순). */
  public static final java.util.List<Integer> TOP_PERCENT_CUTOFFS = java.util.List.of(1, 5, 10, 25, 50, 75);

  /**
   * 상위 p% 기준 순위 K = ⌊p × total / 100⌋(티어 경계와 같은 규칙). K번째로 높은 값 이상이면 topPercent ≤ p다. 0이면 누구도 닿을 수
   * 없다.
   */
  public static long cutoffRank(int topPercent, long total) {
    return (long) topPercent * total / 100;
  }

  /** 상위 몇 %인가. 1위/100명이면 1.00이다. 소수 둘째 자리 올림이라 상위 1%를 넘는 값이 1%로 보이지 않는다. */
  public static BigDecimal topPercent(long rank, long total) {
    if (total <= 0 || rank <= 0) throw new IllegalArgumentException("rank와 total은 양수여야 합니다.");
    return BigDecimal.valueOf(rank)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.CEILING);
  }
}
