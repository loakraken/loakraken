package com.loakraken.stats.application;

import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules.Metric;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 관측 캐릭터 집계. since는 activeDays 기준 시각이다. */
public interface StatsPort {
  Summary summary(StatsConditions conditions, Instant since);

  List<ClassRow> classes(StatsConditions conditions, Instant since);

  List<ServerRow> servers(StatsConditions conditions, Instant since);

  Range range(StatsConditions conditions, Instant since, Metric metric);

  List<Bin> histogram(StatsConditions conditions, Instant since, Metric metric, BigDecimal width);

  /** value보다 큰 캐릭터 수와 모집단 수. */
  Standing standing(StatsConditions conditions, Instant since, Metric metric, BigDecimal value);

  RankingPage ranking(
      StatsConditions conditions, Instant since, Metric metric, int offset, int limit);

  Coverage coverage();

  /** 이름이 같은 캐릭터가 여러 서버에 있으면 가장 최근에 갱신한 캐릭터. */
  Optional<Target> target(String characterName);

  /** target이 조건의 모집단에 들어가는가. */
  boolean contains(StatsConditions conditions, Instant since, Target target);

  record Summary(
      long population,
      BigDecimal itemLevelMean,
      BigDecimal itemLevelMedian,
      BigDecimal combatPowerMedian,
      BigDecimal combatPowerP90,
      long dealers,
      long supports,
      BigDecimal gemMeanLevel,
      long gemSample) {}

  record ClassRow(
      String className,
      long count,
      long dealers,
      long supports,
      BigDecimal itemLevelMean,
      BigDecimal gemMeanLevel,
      List<BigDecimal> combatPowerPercentiles) {}

  /** knownCharacters·rosters는 필터와 무관하게 그 서버에서 알고 있는 전체 캐릭터 수와 원정대 수다. */
  record ServerRow(
      String serverName,
      long count,
      BigDecimal itemLevelMedian,
      BigDecimal combatPowerMedian,
      long knownCharacters,
      long rosters) {}

  record Range(long count, BigDecimal min, BigDecimal max) {}

  record Bin(BigDecimal from, long count) {}

  record Standing(long greater, long total) {}

  record RankingRow(
      long rank,
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower) {}

  record RankingPage(long total, List<RankingRow> rows) {}

  record Coverage(long observedCharacters, long knownCharacters, long discoveredAccounts) {}

  record Target(
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Instant refreshedAt) {}
}
