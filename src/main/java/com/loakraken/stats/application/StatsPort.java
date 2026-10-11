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

  /** value보다 큰 캐릭터 수와 모집단 수. 처리정지 목록은 모집단에서 뺀다. */
  Standing standing(StatsConditions conditions, Instant since, Metric metric, BigDecimal value);

  RankingPage ranking(
      StatsConditions conditions, Instant since, Metric metric, int offset, int limit);

  Coverage coverage();

  /** 이름이 같은 캐릭터가 여러 서버에 있으면 가장 최근에 갱신한 캐릭터. */
  Optional<Target> target(String characterName);

  /** 처리정지 목록(public_exclusion)에 있는가. */
  boolean excluded(Target target);

  /** target이 조건의 모집단(처리정지 목록 제외)에 들어가는가. */
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
   * 순위(RANK)를 매긴다. 처리정지 목록 캐릭터는 대표·캐릭터 행·캐릭터 수에서 빼고, 공개 캐릭터가 없는 원정대는 뺀다. server가 있으면 그 서버 원정대만.
   */
  CollectiblePage collectibleRanking(
      String server, Instant since, boolean characterUnit, int offset, int limit);

  /** 원정대별 수집품 종류(공식 순서). */
  java.util.Map<java.util.UUID, List<CollectibleType>> collectibleTypes(
      java.util.Collection<java.util.UUID> rosterKeys);

  /** 원정대 랭킹 기준(#297). 요청 값만 이 enum이고 응답에는 문자열로 나간다. */
  enum RosterBasis {
    ITEM_LEVEL,
    COMBAT_POWER,
    ROSTER_LEVEL
  }

  /** 원정대 랭킹 합산 방식(#297). ROSTER_LEVEL에서는 쓰지 않는다. */
  enum RosterAggregate {
    TOP6_AVG,
    TOP6_SUM,
    ALL_AVG
  }

  /** 원정대 랭킹 행(#297). 대표 = 아이템 레벨이 가장 높은 공개 캐릭터. score는 반올림 전 값. */
  record RosterRow(
      long rank,
      java.util.UUID rosterKey,
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      long characterCount,
      long usedCharacters,
      BigDecimal score) {}

  /** excluded: COMBAT_POWER에서 전투력 관측 누락으로 뺀 원정대 수(다른 기준은 0). */
  record RosterPage(long total, long excluded, List<RosterRow> rows) {}

  /** 원정대 구성 한 명. value는 기준 값(ROSTER_LEVEL이면 아이템 레벨). */
  record RosterMember(String characterName, String className, BigDecimal value) {}

  /**
   * 원정대 랭킹(#297). since 뒤에 관측한 공개 캐릭터를 roster_key로 묶어 기준·합산 값 내림차순으로 같은 순위(RANK)를 매긴다(소수 둘째 자리 기준).
   * COMBAT_POWER는 since 뒤 전투력을 관측한 캐릭터만 값이 있고, 아이템 레벨 1640 이상인데 전투력 관측이 없는 캐릭터가 있는 원정대는 뺀다.
   */
  RosterPage rosterRanking(
      String server,
      Instant since,
      RosterBasis basis,
      RosterAggregate aggregate,
      int offset,
      int limit);

  /** 원정대별 구성(기준 상위 6명, 값이 있는 캐릭터만, 순서대로). */
  java.util.Map<java.util.UUID, List<RosterMember>> rosterComposition(
      java.util.Collection<java.util.UUID> rosterKeys,
      String server,
      Instant since,
      RosterBasis basis);

  /**
   * 캐릭터 기준 수집품 순위(#303). reason이 null이면 rank·point·maxPoint가 있다. reason: NOT_OBSERVED(처리정지 목록 포함),
   * NOT_OBSERVED(캐릭터·원정대·수집품 관측 없음), INACTIVE(activeDays 안 관측 없음), NO_COLLECTIBLES(Σ Point = 0).
   * total은 같은 조건의 순위 모집단 수.
   */
  record CollectibleStanding(
      String reason,
      String characterName,
      String serverName,
      Long rank,
      long total,
      Long point,
      Long maxPoint) {}

  CollectibleStanding collectibleStanding(
      String characterName, String server, Instant since, boolean characterUnit);

  /** 길드 랭킹 정렬(#301). 요청 값만 이 enum이고 응답에는 문자열로 나간다. */
  enum GuildSort {
    AVG,
    SUM
  }

  /** 길드 랭킹 행(#301). 평균·합은 관측한 공개 길드원의 아이템 레벨(반올림 전). */
  record GuildRow(
      long rank,
      String serverName,
      String guildName,
      long memberCount,
      BigDecimal itemLevelAvg,
      BigDecimal itemLevelSum,
      BigDecimal combatPowerAvg,
      long combatPowerCount) {}

  /** excluded: 관측 길드원이 최소 인원 미만이라 뺀 길드 수. */
  record GuildPage(long total, long excluded, List<GuildRow> rows) {}

  /**
   * 길드 랭킹(#301). since 뒤에 프로필을 관측한 공개 캐릭터를 (서버, 길드 이름)으로 묶고, 관측 길드원이 minMembers 미만인 길드는 뺀다. sort 값
   * 내림차순으로 같은 순위(RANK, 소수 둘째 자리 기준)를 매긴다.
   */
  GuildPage guildRanking(
      String server, Instant since, GuildSort sort, int minMembers, int offset, int limit);

  /** 수집품 항목별 보유(#305). owned = Point > 0, completed = Point ≥ 만점(사전의 최근 만점). */
  record CollectibleOwnershipRow(
      String type, String name, int sortOrder, int maxPoint, long owned, long completed) {}

  /** population: 조건에 맞는 관측 원정대 수(분모 N). */
  record CollectibleOwnership(long population, List<CollectibleOwnershipRow> items) {}

  /**
   * 수집품 항목별 보유 원정대 수(#305). since 뒤에 항목을 관측했고 공개 캐릭터(server가 있으면 그 서버)가 있는 원정대가 분모다. type이 있으면 그
   * 종류만, 가진 원정대가 없는 항목도 0으로 준다. since 뒤에 관측되지 않은 항목(게임에서 사라진 항목)은 빼고, 순서는 공식 종류 순서 → 종류 안 순서다.
   */
  CollectibleOwnership collectibleOwnership(String server, Instant since, String type);

  /** 수집품 항목을 관측한 원정대의 서버 이름(예열 대상, #305). */
  List<String> collectibleServers();

  /** 칭호 하나의 장착 확인 원정대 수(#311). */
  record TitleEquipRow(String title, long rosters) {}

  /** population: 조건에 맞는 관측 원정대 수(분모 N). */
  record TitleEquip(long population, List<TitleEquipRow> titles) {}

  /**
   * 칭호 장착 확인(#311). 분모는 since 뒤에 프로필(칭호가 보이는 관측)을 받은 공개 캐릭터가 있는 원정대, 분자는 그 가운데 since 뒤에 그 칭호를 장착한
   * 공개 캐릭터가 확인된 원정대다. 공식 프로필에는 장착 칭호만 있어 보유 여부는 알 수 없다. title이 있으면 그 칭호만.
   */
  TitleEquip titleEquip(String server, Instant since, String title);
}
