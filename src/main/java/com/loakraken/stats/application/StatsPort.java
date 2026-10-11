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

  /** value보다 큰 캐릭터 수와 모집단 수. 공개 제외 목록은 모집단에서 뺀다. */
  Standing standing(StatsConditions conditions, Instant since, Metric metric, BigDecimal value);

  RankingPage ranking(
      StatsConditions conditions, Instant since, Metric metric, int offset, int limit);

  Coverage coverage();

  /** 이름이 같은 캐릭터가 여러 서버에 있으면 가장 최근에 갱신한 캐릭터. */
  Optional<Target> target(String characterName);

  /** 공개 제외 목록(public_exclusion)에 있는가. */
  boolean excluded(Target target);

  /** target이 조건의 모집단(공개 제외 목록 제외)에 들어가는가. */
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

  /**
   * 수집품 랭킹 행(#295). 원정대 단위면 대표(아이템 레벨이 가장 높은 공개 캐릭터), 캐릭터 단위면 그 캐릭터다. point·maxPoint는 원정대 합(공식
   * Point·MaxPoint).
   */
  record CollectibleRow(
      long rank,
      java.util.UUID rosterKey,
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      long characterCount,
      long point,
      long maxPoint,
      Instant observedAt) {}

  record CollectiblePage(long total, List<CollectibleRow> rows) {}

  /** 공식 수집품 종류 하나(공식 순서). */
  record CollectibleType(String type, int point, int maxPoint) {}

  record Coverage(long observedCharacters, long knownCharacters, long discoveredAccounts) {}

  record Target(
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Instant refreshedAt) {}

  /**
   * 수집품 랭킹(#295). since 뒤에 관측한 원정대 가운데 Σ Point > 0인 것을 진행도(Σ Point / Σ MaxPoint) → Σ Point 순으로 같은
   * 순위(RANK)를 매긴다. 공개 제외 캐릭터는 대표·캐릭터 행·캐릭터 수에서 빼고, 공개 캐릭터가 없는 원정대는 뺀다. server가 있으면 그 서버 원정대만.
   */
  CollectiblePage collectibleRanking(
      String server, Instant since, boolean characterUnit, int offset, int limit);

  /** 원정대별 수집품 종류(공식 순서). */
  java.util.Map<java.util.UUID, List<CollectibleType>> collectibleTypes(
      java.util.Collection<java.util.UUID> rosterKeys);
}
