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
  }

  /** 상위 몇 %인가. 1위/100명이면 1.00이다. 소수 둘째 자리 올림이라 상위 1%를 넘는 값이 1%로 보이지 않는다. */
  public static BigDecimal topPercent(long rank, long total) {
    if (total <= 0 || rank <= 0) throw new IllegalArgumentException("rank와 total은 양수여야 합니다.");
    return BigDecimal.valueOf(rank)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 2, RoundingMode.CEILING);
  }
}
