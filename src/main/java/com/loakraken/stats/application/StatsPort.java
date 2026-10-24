package com.loakraken.stats.application;

import com.loakraken.stats.domain.ClassBuilds;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules.Metric;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 관측 캐릭터 집계. since는 activeDays 기준 시각이다. */
public interface StatsPort {
  Summary summary(StatsConditions conditions, Instant since);

  List<ClassRow> classes(StatsConditions conditions, Instant since);

  /**
   * 직업 각인별 통계(직업 표의 직업 각인 보기). 모집단(처리정지 제외)을 직업과 마지막 장비 요약의 직업 각인(공식 칭호 원문)으로 나눈다. 요약이 없거나 칭호가 비면
   * classEngraving이 null인 행이다(직업별 미확인 수).
   */
  List<ClassEngravingRow> classEngravingTable(StatsConditions conditions, Instant since);

  List<ServerRow> servers(StatsConditions conditions, Instant since);

  /**
   * 최고 전투력(L5). 모집단(처리정지 제외) 중 best_combat_power(관측 이력 전체에서 가장 높았던 전투력)가 있는 캐릭터의 전체·직업별
   * 인원·평균·최고·분위수. conditions의 전투력 범위는 지금 전투력에 걸린다.
   */
  BestCombatPower bestCombatPower(StatsConditions conditions, Instant since);

  /** percentiles는 p10·p25·p50·p75·p90(percentile_cont). 인원이 0이면 값은 null·빈 목록. */
  record BestCombatPowerRow(
      String className,
      long count,
      BigDecimal mean,
      BigDecimal max,
      List<BigDecimal> percentiles) {}

  /** overall은 늘 있고(인원 0이면 count 0), classes는 인원 많은 순. */
  record BestCombatPower(BestCombatPowerRow overall, List<BestCombatPowerRow> classes) {}

  Range range(StatsConditions conditions, Instant since, Metric metric);

  List<Bin> histogram(StatsConditions conditions, Instant since, Metric metric, BigDecimal width);

  /** value보다 큰 캐릭터 수와 모집단 수. 처리정지 목록은 모집단에서 뺀다. */
  Standing standing(StatsConditions conditions, Instant since, Metric metric, BigDecimal value);

  /**
   * 모집단에서 k번째(1부터)로 높은 값(다음 티어 경계, L2). 같은 값은 각각 센다. k가 모집단보다 크면 null. 캐릭터마다 부르지 않고 (지표·조건·k)마다 한 번
   * 구해 캐시한다.
   */
  BigDecimal nthHighest(StatsConditions conditions, Instant since, Metric metric, long k);

  /** classEngraving이 있으면 모집단을 마지막 장비 요약의 직업 각인이 그 값(공백 무시)인 캐릭터로 좁힌다(#618). 요약이 없는 캐릭터는 빠진다. */
  RankingPage ranking(
      StatsConditions conditions,
      Instant since,
      Metric metric,
      int offset,
      int limit,
      String classEngraving);

  /** 모집단(처리정지 제외)을 마지막 장비 요약의 직업 각인(공식 칭호 원문)별로 센다(#618). 요약이 없거나 칭호가 비면 키가 null이다. */
  Map<String, Long> classEngravingCounts(StatsConditions conditions, Instant since);

  /**
   * 팔찌·젬 효율 랭킹(L38·L39). columns의 값 열에서 지금 규칙 버전({@link EfficiencyColumns#ruleVersion()}) 값만 센다.
   * 처리정지 목록은 모집단에서 뺀다.
   */
  EfficiencyPage efficiencyRanking(
      StatsConditions conditions, Instant since, EfficiencyColumns columns, int offset, int limit);

  /**
   * 직업 상세 빌드(M12) 표본. 모집단(처리정지 제외)에서 conditions.className 직업 캐릭터마다 마지막 장비 요약 1행을 읽는다. population은
   * 요약이 없는 캐릭터도 센 모집단 수다.
   */
  ClassBuildSamples classBuildSamples(StatsConditions conditions, Instant since);

  Coverage coverage();

  /** 이름이 같은 캐릭터가 여러 서버에 있으면 가장 최근에 갱신한 캐릭터. */
  Optional<Target> target(String characterName);

  /** 처리정지 목록(public_exclusion)에 있는가. */
  boolean excluded(Target target);

  /** characters 중 처리정지 목록에 있는 것(서버를 비운 항목은 모든 서버). 한 번의 조회다. */
  java.util.Set<Named> stopListed(java.util.Collection<Named> characters);

  /** 캐릭터 이름과 서버. */
  record Named(String characterName, String serverName) {}

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

  /** 직업 표의 한 행을 직업 각인까지 나눈 것. classEngraving이 null이면 직업 각인을 모르는 캐릭터다. */
  record ClassEngravingRow(String classEngraving, ClassRow stats) {}

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

  /**
   * @param bestCombatPower 관측한 최고 전투력(L37)
   * @param arkPassive 마지막 장비 요약의 아크 패시브 포인트·직업 각인(K32). 요약이 없으면 null
   * @param estherWeapon 마지막 장비 요약의 에스더 무기(K32). 에스더 무기가 아니거나 요약이 없으면 null
   */
  record RankingRow(
      long rank,
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      BigDecimal bestCombatPower,
      ArkPassiveSummary arkPassive,
      EstherWeapon estherWeapon) {}

  /** 아크 패시브 진화·깨달음·도약 포인트와 직업 각인(공식 ArkPassive.Title). 못 읽은 값은 null. */
  record ArkPassiveSummary(
      Integer evolution, Integer enlightenment, Integer leap, String classEngraving) {}

  /** 에스더 무기 강화 단계와 엘라 부여 단계. 못 읽은 값은 null. */
  record EstherWeapon(Integer refineLevel, Integer ellaStage) {}

  record RankingPage(long total, List<RankingRow> rows) {}

  /**
   * 효율 랭킹 종류(L38 팔찌, L39 젬). 열 이름은 characters 테이블이고, 지금 규칙 버전은 캐릭터 모듈이 저장할 때 쓰는 공통 상수다. legacy는 전투력
   * 기여 모델(팔찌 = 전투력 시뮬레이터, 젬 = 젬 옵션 합계 줄), real은 캐릭터 응답의 실 효율(팔찌·젬 실 효율)이다. 어느 쪽으로 세는지는 설정이 고른다.
   */
  enum EfficiencyKind {
    BRACELET(
        new EfficiencyColumns(
            "bracelet_efficiency_pct",
            "bracelet_rule_version",
            com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM,
            false),
        new EfficiencyColumns(
            "bracelet_real_pct",
            "bracelet_real_rule_version",
            com.loakraken.common.api.EfficiencyRuleVersions.BRACELET_REAL,
            true)),
    ARK_GRID_GEM(
        new EfficiencyColumns(
            "ark_grid_gem_pct",
            "ark_grid_rule_version",
            com.loakraken.common.api.EfficiencyRuleVersions.ARK_GRID,
            false),
        new EfficiencyColumns(
            "ark_grid_real_pct",
            "ark_grid_real_rule_version",
            com.loakraken.common.api.EfficiencyRuleVersions.ARK_GRID_REAL,
            true));

    private final EfficiencyColumns legacy;
    private final EfficiencyColumns real;

    EfficiencyKind(EfficiencyColumns legacy, EfficiencyColumns real) {
      this.legacy = legacy;
      this.real = real;
    }

    public EfficiencyColumns columns(boolean useReal) {
      return useReal ? real : legacy;
    }
  }

  /**
   * 랭킹 값 열, 규칙 버전 열, 지금 규칙 버전(이 버전 값만 센다). 열 이름은 코드 상수뿐이다(요청 값이 SQL에 들어가지 않음). dealersOnly면 포지션이
   * 딜러인 캐릭터만 센다 — 실 효율은 서포터도 저장되지만(서포터는 점수 기준이 달라) 딜러와 한 줄로 세지 않는다. 전투력 기여 모델 값은 딜러만 계산된다.
   */
  record EfficiencyColumns(
      String column, String versionColumn, String ruleVersion, boolean dealersOnly) {}

  record EfficiencyRow(
      long rank,
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      BigDecimal value,
      Instant observedAt) {}

  record EfficiencyPage(long total, String ruleVersion, List<EfficiencyRow> rows) {}

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

  /** characters는 samples와 같은 순서·같은 길이다(상위 목록에 쓴다). */
  record ClassBuildSamples(
      long population, List<ClassBuilds.Sample> samples, List<ClassBuildCharacter> characters) {
    public ClassBuildSamples(long population, List<ClassBuilds.Sample> samples) {
      this(population, samples, List.of());
    }
  }

  /** 표본 캐릭터. 전투력은 관측값, observedAt은 마지막 프로필 확인 시각. */
  record ClassBuildCharacter(
      String characterName,
      String serverName,
      java.math.BigDecimal itemLevel,
      java.math.BigDecimal combatPower,
      Instant observedAt) {}

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
  /** AVG·SUM: 아이템 레벨 평균·합. CP_AVG: 아이템 레벨 1750 이상 길드원 전투력 평균. CP_SUM: 전투력 총합(전원). */
  enum GuildSort {
    AVG,
    SUM,
    CP_AVG,
    CP_SUM
  }

  /**
   * 길드 랭킹 행(#301). 평균·합은 관측한 공개 길드원의 아이템 레벨(반올림 전). 전투력은 관측한 길드원만: combatPowerAvg는 전원 평균,
   * combatPowerAvgTop은 아이템 레벨 1750 이상 평균(#580, 길드 탭과 같은 정의), combatPowerTotal은 전원 합.
   */
  record GuildRow(
      long rank,
      String serverName,
      String guildName,
      long memberCount,
      BigDecimal itemLevelAvg,
      BigDecimal itemLevelSum,
      BigDecimal combatPowerAvg,
      long combatPowerCount,
      BigDecimal combatPowerAvgTop,
      long combatPowerTopCount,
      BigDecimal combatPowerTotal) {}

  /** excluded: 관측 길드원이 최소 인원 미만이라 뺀 길드 수. */
  /**
   * @param total 순위에 든 전체 길드 수(이름 조건과 무관)
   * @param excluded 길드원이 minMembers 미만이라 뺀 전체 길드 수
   * @param excludedMatches 이름 조건이 있을 때 그 이름에 맞지만 minMembers 미만이라 뺀 길드 수. 이름 조건이 없으면 null
   * @param matchCount 이름 조건이 있을 때 그 이름에 맞고 순위에 든 길드 수(쪽 수 계산용). 이름 조건이 없으면 null
   */
  record GuildPage(
      long total, long excluded, Long excludedMatches, Long matchCount, List<GuildRow> rows) {}

  /**
   * 길드 랭킹(#301). since 뒤에 프로필을 관측한 공개 캐릭터를 (서버, 길드 이름)으로 묶고, 관측 길드원이 minMembers 미만인 길드는 뺀다. sort 값
   * 내림차순으로 같은 순위(RANK, 소수 둘째 자리 기준)를 매긴다.
   */
  GuildPage guildRanking(
      String server,
      Instant since,
      GuildSort sort,
      int minMembers,
      int offset,
      int limit,
      String guild);

  /**
   * 한 길드의 관측 길드원(길드 탭, L36). 길드 랭킹 memberCount와 같은 정의: since 뒤 프로필 관측, 아이템 레벨 있음, 처리정지 제외. 길드 이름은
   * 대소문자를 무시해 찾고, 철자가 여럿이면 요청과 정확히 같은 철자를, 없으면 길드원이 가장 많은 철자(같으면 이름순 첫째) 하나만 고른다(랭킹 한 줄과 같은 길드).
   * 없으면 빈 목록.
   */
  GuildMembers guildMembers(String server, String guild, Instant since);

  /**
   * guildName은 저장된 철자. 길드원이 없으면 guildName null·빈 목록. rosterKeys: 캐릭터 이름 → 원정대 키(내부 값, 묶음 번호를 매길 때만
   * 쓰고 응답·캐시에 넣지 않음). 원정대를 모르는 길드원은 이 맵에 없다.
   */
  record GuildMembers(
      String guildName,
      List<com.loakraken.stats.domain.GuildDetail.Member> members,
      java.util.Map<String, java.util.UUID> rosterKeys) {
    public GuildMembers(
        String guildName, List<com.loakraken.stats.domain.GuildDetail.Member> members) {
      this(guildName, members, java.util.Map.of());
    }
  }

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
