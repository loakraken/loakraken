package com.loakraken.stats.application;

import com.loakraken.common.api.BusinessException;
import com.loakraken.stats.domain.ClassBuilds;
import com.loakraken.stats.domain.GuildDetail;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import com.loakraken.stats.domain.StatsRules.Metric;
import com.loakraken.stats.domain.StatsRules.SampleStatus;
import com.loakraken.stats.domain.StatsRules.Tier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 통계·랭킹 조회(stats-v1). 요청마다 SQL로 집계하고 조건별로 10분 캐시한다. 공식 API를 부르지 않는다.
 *
 * <p>표본이 MIN_SAMPLE(30) 미만이면 sampleStatus=COLLECTING이고 분위수(p90·백분위 5개)·백분위·티어는 null이다. 평균 아이템
 * 레벨·중앙값·서포터 비율·보석 평균은 표본 1명 이상이면 참고값으로 준다(#273, 사용자 지적 PM 총괄 10-10 23:50). 인원 수와 순위는 그대로 준다.
 */
@Service
public class StatsQueryService {
  private static final Logger log = LoggerFactory.getLogger(StatsQueryService.class);
  public static final int MAX_BINS = 300;
  public static final int MAX_RANKING_OFFSET = 10_000;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private final StatsPort stats;
  private final StatsCache cache;
  private final Clock clock;

  public StatsQueryService(StatsPort stats, StatsCache cache, Clock clock) {
    this.stats = stats;
    this.cache = cache;
    this.clock = clock;
  }

  public SummaryResult summary(StatsConditions conditions) {
    return cached(
        "summary:" + key(conditions),
        SummaryResult.class,
        () -> {
          var now = clock.instant();
          var summary = stats.summary(conditions, conditions.since(now));
          var meta = meta(conditions, summary.population(), now);
          boolean ready = meta.sampleStatus() == SampleStatus.READY;
          long known = summary.dealers() + summary.supports();
          // 평균·중앙값·비율은 표본 1명 이상이면 참고값(#273). 분위수(p90)만 30명 기준.
          return new SummaryResult(
              meta,
              summary.itemLevelMean(),
              summary.itemLevelMedian(),
              summary.combatPowerMedian(),
              ready ? summary.combatPowerP90() : null,
              summary.dealers(),
              summary.supports(),
              summary.population() - known,
              known > 0 ? share(summary.supports(), known) : null,
              summary.gemSample() > 0 ? summary.gemMeanLevel() : null,
              summary.gemSample());
        });
  }

  /**
   * 직업 상세 빌드 통계(M12 1단계). 표본은 그 직업 모집단에서 장비 요약이 있는 캐릭터이고, classEngraving이 있으면 그 직업 각인 캐릭터만이다. 표본이
   * 30 미만이면 COLLECTING이고 항목이 모두 빈 목록이다. 직업 각인 비율만은 직업 전체 표본 기준이라 상태를 따로 준다.
   */
  public ClassBuildsResult classBuilds(
      StatsConditions conditions, String classEngraving, int limit, BigDecimal braceletMin) {
    var bracelet =
        StatsConditions.strip(
            braceletMin == null ? ClassBuilds.DEFAULT_BRACELET_ITEM_LEVEL_MIN : braceletMin);
    if (bracelet.compareTo(conditions.itemLevelMin()) < 0) bracelet = conditions.itemLevelMin();
    var effectiveBracelet = bracelet;
    return cached(
        "class-builds:"
            + key(conditions)
            + ":"
            + (classEngraving == null ? "-" : classEngraving)
            + ":"
            + limit
            + ":"
            + effectiveBracelet.toPlainString(),
        ClassBuildsResult.class,
        () -> {
          var now = clock.instant();
          var page = stats.classBuildSamples(conditions, conditions.since(now));
          var all = page.samples();
          var samples =
              classEngraving == null
                  ? all
                  : all.stream()
                      .filter(sample -> classEngraving.equals(sample.classEngraving()))
                      .toList();
          var status = SampleStatus.of(samples.size());
          var allStatus = SampleStatus.of(all.size());
          var builds =
              status == SampleStatus.READY
                  ? ClassBuilds.aggregate(all, samples, limit, effectiveBracelet)
                  : ClassBuilds.Result.empty();
          if (allStatus == SampleStatus.READY && status != SampleStatus.READY)
            // 고른 직업 각인 표본이 작아도 직업 각인 비율은 직업 전체 표본으로 준다.
            builds =
                withClassEngravings(
                    builds, ClassBuilds.aggregate(all, all, limit, effectiveBracelet));
          return new ClassBuildsResult(
              new Meta(
                  samples.size(),
                  conditions,
                  now,
                  StatsRules.SNAPSHOT_LATEST,
                  ClassBuilds.RULE_VERSION,
                  StatsRules.MIN_SAMPLE,
                  status,
                  cached("coverage", StatsPort.Coverage.class, stats::coverage)),
              conditions.className(),
              classEngraving,
              page.population(),
              effectiveBracelet,
              allStatus,
              ClassBuilds.definitions(),
              builds);
        });
  }

  private static ClassBuilds.Result withClassEngravings(
      ClassBuilds.Result builds, ClassBuilds.Result source) {
    return new ClassBuilds.Result(
        source.classEngravings(),
        builds.engravingCombos(),
        builds.engravings(),
        builds.evolutionPoints(),
        builds.enlightenmentPoints(),
        builds.leapPoints(),
        builds.damageGems(),
        builds.cooldownGems(),
        builds.cardSets(),
        builds.topStats(),
        builds.braceletStats());
  }

  /** 길드 탭 집계(L36·K28·M10). 관측 길드원이 없으면(처리정지만 있는 길드 포함) 모르는 길드와 같은 404다. 처리정지 여부가 드러나지 않는다. */
  public GuildDetailResult guildDetail(String server, String guild, int activeDays) {
    var conditions =
        new StatsConditions(server, null, null, activeDays, null, null, null, null, false);
    return cached(
        "guild-detail:"
            + server
            + ":"
            + guild.toLowerCase(java.util.Locale.ROOT)
            + ":"
            + activeDays,
        GuildDetailResult.class,
        () -> {
          var now = clock.instant();
          var found = stats.guildMembers(server, guild, conditions.since(now));
          if (found.members().isEmpty())
            throw new BusinessException(StatsErrorCode.STATS_GUILD_NOT_OBSERVED);
          return new GuildDetailResult(
              server, found.guildName(), activeDays, now, GuildDetail.aggregate(found.members()));
        });
  }

  /**
   * 최고 전투력 통계(L5). 표본 30 미만인 칸(전체 포함)은 평균·최고·분위수를 모두 null로 두고 인원과 COLLECTING만 준다(작은 칸의 값은 사실상 한두
   * 사람의 값이라, PM 판정 2026-10-13).
   */
  public BestCombatPowerResult bestCombatPower(StatsConditions conditions) {
    return cached(
        "best-combat-power:" + key(conditions),
        BestCombatPowerResult.class,
        () -> {
          var now = clock.instant();
          var result = stats.bestCombatPower(conditions, conditions.since(now));
          return new BestCombatPowerResult(
              meta(conditions, result.overall().count(), now),
              bestRow(result.overall()),
              result.classes().stream().map(StatsQueryService::bestRow).toList());
        });
  }

  private static BestCombatPowerStat bestRow(StatsPort.BestCombatPowerRow row) {
    var status = SampleStatus.of(row.count());
    if (status != SampleStatus.READY)
      return new BestCombatPowerStat(row.className(), row.count(), null, null, null, status);
    var p = row.percentiles();
    return new BestCombatPowerStat(
        row.className(),
        row.count(),
        row.mean(),
        row.max(),
        p.size() == 5 ? new Percentiles(p.get(0), p.get(1), p.get(2), p.get(3), p.get(4)) : null,
        status);
  }

  public ClassesResult classes(StatsConditions conditions) {
    return cached(
        "classes:" + key(conditions),
        ClassesResult.class,
        () -> {
          var now = clock.instant();
          var rows = stats.classes(conditions, conditions.since(now));
          long population = rows.stream().mapToLong(StatsPort.ClassRow::count).sum();
          var result = new ArrayList<ClassStat>();
          for (var row : rows) {
            var status = SampleStatus.of(row.count());
            boolean ready = status == SampleStatus.READY;
            var p = row.combatPowerPercentiles();
            long known = row.dealers() + row.supports();
            result.add(
                new ClassStat(
                    row.className(),
                    row.count(),
                    population == 0 ? null : share(row.count(), population),
                    row.dealers(),
                    row.supports(),
                    row.count() - known,
                    known > 0 && StatsRules.SUPPORT_CLASSES.contains(row.className())
                        ? share(row.supports(), known)
                        : null,
                    row.itemLevelMean(),
                    row.gemMeanLevel(),
                    ready && p.size() == 5
                        ? new Percentiles(p.get(0), p.get(1), p.get(2), p.get(3), p.get(4))
                        : null,
                    status));
          }
          return new ClassesResult(meta(conditions, population, now), result);
        });
  }

  public ServersResult servers(StatsConditions conditions) {
    return cached(
        "servers:" + key(conditions),
        ServersResult.class,
        () -> {
          var now = clock.instant();
          var rows = stats.servers(conditions, conditions.since(now));
          long population = rows.stream().mapToLong(StatsPort.ServerRow::count).sum();
          return new ServersResult(
              meta(conditions, population, now),
              rows.stream()
                  .map(
                      row -> {
                        var status = SampleStatus.of(row.count());
                        boolean ready = status == SampleStatus.READY;
                        return new ServerStat(
                            row.serverName(),
                            row.count(),
                            population == 0 ? null : share(row.count(), population),
                            row.itemLevelMedian(),
                            row.combatPowerMedian(),
                            row.knownCharacters(),
                            row.rosters(),
                            status);
                      })
                  .toList());
        });
  }

  public DistributionResult distribution(
      StatsConditions conditions, Metric metric, BigDecimal width, BigDecimal percentileOf) {
    var binWidth = width == null ? defaultWidth(metric) : StatsConditions.strip(width);
    var distribution =
        cached(
            "distribution:" + metric + ":" + binWidth.toPlainString() + ":" + key(conditions),
            DistributionResult.class,
            () -> {
              var now = clock.instant();
              var since = conditions.since(now);
              var range = stats.range(conditions, since, metric);
              if (range.count() > 0) {
                var bins =
                    floor(range.max(), binWidth)
                        .subtract(floor(range.min(), binWidth))
                        .divide(binWidth, 0, RoundingMode.DOWN)
                        .longValue();
                if (bins + 1 > MAX_BINS)
                  throw new BusinessException(StatsErrorCode.STATS_TOO_MANY_BINS);
              }
              var bins =
                  range.count() == 0
                      ? List.<Bin>of()
                      : stats.histogram(conditions, since, metric, binWidth).stream()
                          .map(bin -> new Bin(bin.from(), bin.from().add(binWidth), bin.count()))
                          .toList();
              return new DistributionResult(
                  meta(conditions, range.count(), now), metric, binWidth, bins, null);
            });
    if (percentileOf == null) return distribution;
    return new DistributionResult(
        distribution.meta(),
        metric,
        binWidth,
        distribution.bins(),
        standing(
            conditions,
            metric,
            StatsConditions.strip(percentileOf),
            distribution.meta().aggregatedAt()));
  }

  public SnapshotsResult snapshots() {
    var summary = summary(StatsConditions.defaults());
    return new SnapshotsResult(
        List.of(
            new SnapshotInfo(
                StatsRules.SNAPSHOT_LATEST,
                summary.meta().aggregatedAt(),
                summary.meta().population())));
  }

  /**
   * 수집품(내실) 랭킹(#295, 볼트 「랭킹 확장 설계 초안」 v1). 조건 중 server·activeDays만 쓴다. 모집단은 원정대(또는 캐릭터 행) 수이고, 30
   * 미만이면 sampleStatus=COLLECTING이지만 값·순위는 그대로 준다(참고값 원칙).
   */
  public CollectibleRankingResult collectibleRanking(
      StatsConditions conditions, boolean characterUnit, int page, int size) {
    int offset = (page - 1) * size;
    if (offset > MAX_RANKING_OFFSET)
      throw new BusinessException(StatsErrorCode.STATS_INVALID_RANGE);
    String unit = characterUnit ? "CHARACTER" : "ROSTER";
    return cached(
        "collectibles:" + unit + ":" + page + ":" + size + ":" + key(conditions),
        CollectibleRankingResult.class,
        () -> {
          var now = clock.instant();
          var result =
              stats.collectibleRanking(
                  conditions.server(), conditions.since(now), characterUnit, offset, size);
          var types =
              stats.collectibleTypes(
                  result.rows().stream()
                      .map(StatsPort.CollectibleRow::rosterKey)
                      .distinct()
                      .toList());
          return new CollectibleRankingResult(
              meta(conditions, result.total(), now),
              unit,
              page,
              size,
              result.rows().stream()
                  .map(
                      row ->
                          new RankedCollectible(
                              row.rank(),
                              row.characterName(),
                              row.serverName(),
                              row.className(),
                              row.itemLevel(),
                              row.characterCount(),
                              BigDecimal.valueOf(row.point())
                                  .multiply(HUNDRED)
                                  .divide(
                                      BigDecimal.valueOf(row.maxPoint()), 2, RoundingMode.HALF_UP),
                              row.point(),
                              row.maxPoint(),
                              row.observedAt(),
                              types.getOrDefault(row.rosterKey(), List.of())))
                  .toList());
        });
  }

  /**
   * 원정대 랭킹(#297, 볼트 「랭킹 확장 설계 초안」 v1 2절). 조건 중 server·activeDays만 쓴다. 모집단은 순위에 든 원정대 수이고, 30 미만이면
   * sampleStatus=COLLECTING이지만 값·순위는 그대로 준다(참고값 원칙). 값은 소수 둘째 자리(HALF_UP)다.
   */
  public RosterRankingResult rosterRanking(
      StatsConditions conditions,
      StatsPort.RosterBasis basis,
      StatsPort.RosterAggregate aggregate,
      int page,
      int size) {
    int offset = (page - 1) * size;
    if (offset > MAX_RANKING_OFFSET)
      throw new BusinessException(StatsErrorCode.STATS_INVALID_RANGE);
    return cached(
        "rosters:" + basis + ":" + aggregate + ":" + page + ":" + size + ":" + key(conditions),
        RosterRankingResult.class,
        () -> {
          var now = clock.instant();
          var since = conditions.since(now);
          var result =
              stats.rosterRanking(conditions.server(), since, basis, aggregate, offset, size);
          var composition =
              stats.rosterComposition(
                  result.rows().stream().map(StatsPort.RosterRow::rosterKey).distinct().toList(),
                  conditions.server(),
                  since,
                  basis);
          return new RosterRankingResult(
              meta(conditions, result.total(), now),
              basis.name(),
              aggregate.name(),
              page,
              size,
              result.excluded(),
              result.rows().stream()
                  .map(
                      row ->
                          new RankedRoster(
                              row.rank(),
                              row.characterName(),
                              row.serverName(),
                              row.className(),
                              row.itemLevel(),
                              row.combatPower(),
                              row.characterCount(),
                              row.usedCharacters(),
                              row.score().setScale(2, RoundingMode.HALF_UP),
                              composition.getOrDefault(row.rosterKey(), List.of())))
                  .toList());
        });
  }

  /**
   * 캐릭터 기준 수집품 순위(#303, 볼트 「수집품 순위·보유율 설계 메모」 1절). 순위 모집단은 수집품 랭킹과 같다. 30 미만이면 topPercent는 null(기존
   * 순위 조회와 같음)이고 rank는 그대로 준다.
   */
  public CollectibleStandingResult collectibleStanding(
      StatsConditions conditions, String characterName, boolean characterUnit) {
    String unit = characterUnit ? "CHARACTER" : "ROSTER";
    return cached(
        "collectibles-standing:" + unit + ":" + characterName + ":" + key(conditions),
        CollectibleStandingResult.class,
        () -> {
          var now = clock.instant();
          var standing =
              stats.collectibleStanding(
                  characterName, conditions.server(), conditions.since(now), characterUnit);
          var status = SampleStatus.of(standing.total());
          var top =
              standing.rank() != null && status == SampleStatus.READY
                  ? StatsRules.topPercent(
                      Math.min(standing.rank(), standing.total()), standing.total())
                  : null;
          return new CollectibleStandingResult(
              meta(conditions, standing.total(), now),
              unit,
              standing.characterName(),
              standing.serverName(),
              standing.rank(),
              standing.total(),
              top,
              standing.point() == null
                  ? null
                  : BigDecimal.valueOf(standing.point())
                      .multiply(HUNDRED)
                      .divide(BigDecimal.valueOf(standing.maxPoint()), 2, RoundingMode.HALF_UP),
              standing.point(),
              standing.maxPoint(),
              standing.reason());
        });
  }

  /** 길드 랭킹 최소 관측 길드원 수(PM-백엔드 10-11 10:46 질문 1 답). */
  public static final int GUILD_MIN_MEMBERS = 5;

  /**
   * 길드 랭킹(#301, 볼트 「랭킹 확장 설계 초안」 v1 3절). 조건 중 server·activeDays만 쓴다. 모집단은 순위에 든 길드 수이고, 30 미만이면
   * sampleStatus=COLLECTING이지만 값·순위는 그대로 준다(참고값 원칙). 값은 소수 둘째 자리(HALF_UP)다.
   */
  public GuildRankingResult guildRanking(
      StatsConditions conditions, StatsPort.GuildSort sort, int page, int size, String guild) {
    int offset = (page - 1) * size;
    if (offset > MAX_RANKING_OFFSET)
      throw new BusinessException(StatsErrorCode.STATS_INVALID_RANGE);
    return cached(
        "guilds:"
            + sort
            + ":"
            + page
            + ":"
            + size
            + ":"
            + (guild == null ? "-" : guild)
            + ":"
            + key(conditions),
        GuildRankingResult.class,
        () -> {
          var now = clock.instant();
          var result =
              stats.guildRanking(
                  conditions.server(),
                  conditions.since(now),
                  sort,
                  GUILD_MIN_MEMBERS,
                  offset,
                  size,
                  guild);
          return new GuildRankingResult(
              meta(conditions, result.total(), now),
              sort.name(),
              page,
              size,
              GUILD_MIN_MEMBERS,
              result.excluded(),
              result.excludedMatches(),
              result.matchCount(),
              result.rows().stream()
                  .map(
                      row ->
                          new RankedGuild(
                              row.rank(),
                              row.serverName(),
                              row.guildName(),
                              row.memberCount(),
                              row.itemLevelAvg().setScale(2, RoundingMode.HALF_UP),
                              row.itemLevelSum().setScale(2, RoundingMode.HALF_UP),
                              row.combatPowerAvg() == null
                                  ? null
                                  : row.combatPowerAvg().setScale(2, RoundingMode.HALF_UP),
                              row.combatPowerCount()))
                  .toList());
        });
  }

  /**
   * 수집품 항목별 보유율(#305, 볼트 「수집품 순위·보유율 설계 메모」 2절). 조건 중 server·activeDays만 쓴다. 분모 N은 관측 원정대 수이고, 30
   * 미만이면 sampleStatus=COLLECTING이지만 비율은 그대로 준다(참고값). N이 0이면 비율은 null.
   *
   * <p>전체 종류 결과 하나를 조건별로 캐시하고, type은 그 결과에서 고른다(type마다 다시 집계하지 않음). 기본 조건은 예열 작업이 캐시가 끝나기 전에 다시
   * 채운다(원정대 1만 개에서 전체 집계 약 0.4초, PM-백엔드 300ms 조건).
   */
  public CollectibleOwnershipResult collectibleOwnership(StatsConditions conditions, String type) {
    var full =
        cached(
            ownershipKey(conditions),
            CollectibleOwnershipResult.class,
            () -> computeOwnership(conditions));
    if (type == null) return full;
    return new CollectibleOwnershipResult(
        full.meta(), type, full.items().stream().filter(item -> type.equals(item.type())).toList());
  }

  /** 보유율 예열(#305): 조건의 전체 종류 결과를 다시 집계해 캐시를 덮어쓴다. */
  public void refreshCollectibleOwnership(StatsConditions conditions) {
    var key = ownershipKey(conditions);
    cache.save(key, guard(() -> computeOwnership(conditions)));
  }

  /** 보유율을 예열할 서버 목록(수집품 항목을 관측한 원정대의 서버). */
  public List<String> collectibleOwnershipServers() {
    return guard(stats::collectibleServers);
  }

  private static String ownershipKey(StatsConditions conditions) {
    return "ownership:" + key(conditions);
  }

  private CollectibleOwnershipResult computeOwnership(StatsConditions conditions) {
    var now = clock.instant();
    var result = stats.collectibleOwnership(conditions.server(), conditions.since(now), null);
    long population = result.population();
    return new CollectibleOwnershipResult(
        meta(conditions, population, now),
        null,
        result.items().stream()
            .map(
                item ->
                    new OwnedItem(
                        item.type(),
                        item.name(),
                        item.sortOrder(),
                        item.maxPoint(),
                        item.owned(),
                        item.completed(),
                        percent(item.owned(), population),
                        percent(item.completed(), population)))
            .toList());
  }

  private static BigDecimal percent(long part, long whole) {
    return whole <= 0
        ? null
        : BigDecimal.valueOf(part)
            .multiply(HUNDRED)
            .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
  }

  /**
   * 칭호 장착 확인 비율(#311, PM 총괄 15:02 판정). 조건 중 server·activeDays만 쓴다. "보유율"이 아니다: 공식 프로필에는 장착 칭호만 있어,
   * 관측 원정대 N개 중 그 칭호 장착이 확인된 원정대 n개를 준다. 30 미만이면 COLLECTING이지만 비율은 그대로(참고값), N이 0이면 비율은 null.
   */
  public TitleEquipResult titleEquip(StatsConditions conditions, String title) {
    var full =
        cached(
            "titles:" + key(conditions),
            TitleEquipResult.class,
            () -> {
              var now = clock.instant();
              var result = stats.titleEquip(conditions.server(), conditions.since(now), null);
              long population = result.population();
              return new TitleEquipResult(
                  meta(conditions, population, now),
                  null,
                  result.titles().stream()
                      .map(
                          row ->
                              new EquippedTitle(
                                  row.title(), row.rosters(), percent(row.rosters(), population)))
                      .toList());
            });
    if (title == null) return full;
    return new TitleEquipResult(
        full.meta(),
        title,
        full.titles().stream().filter(row -> title.equals(row.title())).toList());
  }

  public RankingResult ranking(StatsConditions conditions, Metric metric, int page, int size) {
    int offset = (page - 1) * size;
    if (offset > MAX_RANKING_OFFSET)
      throw new BusinessException(StatsErrorCode.STATS_INVALID_RANGE);
    return cached(
        "ranking:" + metric + ":" + page + ":" + size + ":" + key(conditions),
        RankingResult.class,
        () -> {
          var now = clock.instant();
          var result = stats.ranking(conditions, conditions.since(now), metric, offset, size);
          var meta = meta(conditions, result.total(), now);
          boolean ready = meta.sampleStatus() == SampleStatus.READY;
          return new RankingResult(
              meta,
              metric,
              page,
              size,
              result.rows().stream()
                  .map(
                      row -> {
                        var top = ready ? StatsRules.topPercent(row.rank(), result.total()) : null;
                        return new RankedCharacter(
                            row.rank(),
                            row.characterName(),
                            row.serverName(),
                            row.className(),
                            row.position(),
                            row.itemLevel(),
                            row.combatPower(),
                            top,
                            top == null ? null : Tier.of(top),
                            row.bestCombatPower(),
                            row.arkPassive(),
                            row.estherWeapon());
                      })
                  .toList());
        });
  }

  /**
   * 팔찌·젬 효율 랭킹(L38·L39). 모집단은 전투력 랭킹과 같은 조건(필터·기간·처리정지 제외)에, armory를 관측해 효율을 저장한(검색된) 캐릭터 중 지금 규칙
   * 버전 값이 있는 캐릭터다. 공식 호출 없음.
   */
  public EfficiencyRankingResult efficiencyRanking(
      StatsConditions conditions, StatsPort.EfficiencyKind kind, int page, int size) {
    int offset = (page - 1) * size;
    if (offset > MAX_RANKING_OFFSET)
      throw new BusinessException(StatsErrorCode.STATS_INVALID_RANGE);
    return cached(
        "efficiency-ranking:" + kind + ":" + page + ":" + size + ":" + key(conditions),
        EfficiencyRankingResult.class,
        () -> {
          var now = clock.instant();
          var result =
              stats.efficiencyRanking(conditions, conditions.since(now), kind, offset, size);
          var meta = meta(conditions, result.total(), now);
          boolean ready = meta.sampleStatus() == SampleStatus.READY;
          return new EfficiencyRankingResult(
              meta,
              kind,
              result.ruleVersion(),
              page,
              size,
              result.rows().stream()
                  .map(
                      row -> {
                        var top = ready ? StatsRules.topPercent(row.rank(), result.total()) : null;
                        return new EfficiencyRankedCharacter(
                            row.rank(),
                            row.characterName(),
                            row.serverName(),
                            row.className(),
                            row.position(),
                            row.itemLevel(),
                            row.combatPower(),
                            row.value(),
                            row.observedAt(),
                            top,
                            top == null ? null : Tier.of(top));
                      })
                  .toList());
        });
  }

  /**
   * 캐릭터의 전체·서버·직업·서버 내 직업 순위. 캐릭터는 이 모집단 조건(필터)에 들어 있어야 순위가 나온다. 순위 모집단은 처리정지 목록을 뺀 것이라 랭킹 목록의 순위와
   * 같다. 처리정지 목록에 있는 본인은 관측이 없는 캐릭터와 같은 404다(#363). rankVisibility는 늘 VISIBLE이다.
   */
  public CharacterRankResult characterRank(String characterName, StatsConditions conditions) {
    var target =
        guard(() -> stats.target(characterName))
            .orElseThrow(() -> new BusinessException(StatsErrorCode.STATS_CHARACTER_NOT_OBSERVED));
    // 처리정지 목록 캐릭터는 관측이 없는 캐릭터와 같은 404다(#363, 처리정지 여부를 드러내지 않음).
    if (guard(() -> stats.excluded(target)))
      throw new BusinessException(StatsErrorCode.STATS_CHARACTER_NOT_OBSERVED);
    return cached(
        // v2: 서버 내 직업(SERVER_CLASS) 칸 추가(#170). 이전 캐시가 새 칸 없이 남지 않게 키를 바꾼다.
        "rank:v2:" + target.serverName() + ":" + target.characterName() + ":" + key(conditions),
        CharacterRankResult.class,
        () -> {
          var now = clock.instant();
          var since = conditions.since(now);
          boolean included = stats.contains(conditions, since, target);
          var entries = new ArrayList<RankEntry>();
          if (included) {
            for (var metric : Metric.values()) {
              var value = metric == Metric.COMBAT_POWER ? target.combatPower() : target.itemLevel();
              if (value == null) continue;
              entries.add(entry(metric, Scope.ALL, null, conditions, since, value));
              entries.add(
                  entry(
                      metric,
                      Scope.SERVER,
                      target.serverName(),
                      conditions.withServer(target.serverName()),
                      since,
                      value));
              entries.add(
                  entry(
                      metric,
                      Scope.CLASS,
                      target.className(),
                      conditions.withClassName(target.className()),
                      since,
                      value));
              entries.add(
                  entry(
                      metric,
                      Scope.SERVER_CLASS,
                      target.serverName() + "/" + target.className(),
                      conditions.withServer(target.serverName()).withClassName(target.className()),
                      since,
                      value));
            }
          }
          return new CharacterRankResult(
              target.characterName(),
              target.serverName(),
              target.className(),
              target.position(),
              target.itemLevel(),
              target.combatPower(),
              target.refreshedAt(),
              RankVisibility.VISIBLE,
              included,
              conditions,
              now,
              StatsRules.RULE_VERSION,
              StatsRules.MIN_SAMPLE,
              entries);
        });
  }

  private RankEntry entry(
      Metric metric,
      Scope scope,
      String scopeValue,
      StatsConditions conditions,
      Instant since,
      BigDecimal value) {
    var standing = stats.standing(conditions, since, metric, value);
    long rank = standing.greater() + 1;
    var status = SampleStatus.of(standing.total());
    var top =
        status == SampleStatus.READY && standing.total() > 0
            ? StatsRules.topPercent(Math.min(rank, standing.total()), standing.total())
            : null;
    return new RankEntry(
        metric,
        scope,
        scopeValue,
        rank,
        standing.total(),
        top,
        top == null ? null : Tier.of(top),
        status);
  }

  private PercentileOf standing(
      StatsConditions conditions, Metric metric, BigDecimal value, Instant aggregatedAt) {
    var standing =
        cached(
            "standing:"
                + metric
                + ":"
                + value.stripTrailingZeros().toPlainString()
                + ":"
                + key(conditions),
            StatsPort.Standing.class,
            () -> stats.standing(conditions, conditions.since(aggregatedAt), metric, value));
    long rank = standing.greater() + 1;
    var status = SampleStatus.of(standing.total());
    var top =
        status == SampleStatus.READY
            ? StatsRules.topPercent(Math.min(rank, standing.total()), standing.total())
            : null;
    return new PercentileOf(value, rank, standing.total(), top, top == null ? null : Tier.of(top));
  }

  private Meta meta(StatsConditions conditions, long population, Instant now) {
    return new Meta(
        population,
        conditions,
        now,
        StatsRules.SNAPSHOT_LATEST,
        StatsRules.RULE_VERSION,
        StatsRules.MIN_SAMPLE,
        SampleStatus.of(population),
        // coverage는 characters 전체를 세므로 조건과 무관하게 따로 10분 캐시한다(PM #100 P2-1).
        cached("coverage", StatsPort.Coverage.class, stats::coverage));
  }

  private <T> T cached(String key, Class<T> type, Supplier<T> loader) {
    var hit = cache.find(key, type);
    if (hit.isPresent()) return hit.get();
    var value = guard(loader);
    cache.save(key, value);
    return value;
  }

  private static <T> T guard(Supplier<T> loader) {
    try {
      return loader.get();
    } catch (DataAccessException | TransactionException exception) {
      log.warn("통계 집계에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
      throw new BusinessException(StatsErrorCode.STATS_UNAVAILABLE);
    }
  }

  private static String key(StatsConditions conditions) {
    if (!conditions.validRange()) throw new BusinessException(StatsErrorCode.STATS_INVALID_RANGE);
    return conditions.cacheKey();
  }

  private static BigDecimal share(long part, long whole) {
    return BigDecimal.valueOf(part)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal floor(BigDecimal value, BigDecimal width) {
    return value.divide(width, 0, RoundingMode.FLOOR).multiply(width);
  }

  public static BigDecimal defaultWidth(Metric metric) {
    return metric == Metric.ITEM_LEVEL ? BigDecimal.TEN : BigDecimal.valueOf(100);
  }

  public enum RankVisibility {
    VISIBLE,
    HIDDEN
  }

  public enum Scope {
    ALL,
    SERVER,
    CLASS,
    /** 같은 서버·같은 직업(#170). scopeValue는 "서버/직업". */
    SERVER_CLASS
  }

  public record Meta(
      long population,
      StatsConditions conditions,
      Instant aggregatedAt,
      String snapshot,
      String ruleVersion,
      int minSample,
      SampleStatus sampleStatus,
      StatsPort.Coverage coverage) {}

  /** supportShare는 역할을 판정한 캐릭터 중 서포터 비율(%)이다. */
  public record SummaryResult(
      Meta meta,
      BigDecimal itemLevelMean,
      BigDecimal itemLevelMedian,
      BigDecimal combatPowerMedian,
      BigDecimal combatPowerP90,
      long dealers,
      long supports,
      long unknownRole,
      BigDecimal supportShare,
      BigDecimal gemMeanLevel,
      long gemSample) {}

  public record Percentiles(
      BigDecimal p10, BigDecimal p25, BigDecimal p50, BigDecimal p75, BigDecimal p90) {}

  public record ClassStat(
      String className,
      long count,
      BigDecimal share,
      long dealers,
      long supports,
      long unknownRole,
      BigDecimal supportShare,
      BigDecimal itemLevelMean,
      BigDecimal gemMeanLevel,
      Percentiles combatPower,
      SampleStatus sampleStatus) {}

  public record ClassesResult(Meta meta, List<ClassStat> rows) {}

  /** 길드 탭 결과. guildName은 저장된 철자. */
  public record GuildDetailResult(
      String serverName,
      String guildName,
      int activeDays,
      Instant aggregatedAt,
      GuildDetail.Result detail) {}

  /** className은 전체 줄이면 null. 30 미만이면 mean·max·percentiles는 null. */
  public record BestCombatPowerStat(
      String className,
      long count,
      BigDecimal mean,
      BigDecimal max,
      Percentiles percentiles,
      SampleStatus sampleStatus) {}

  public record BestCombatPowerResult(
      Meta meta, BestCombatPowerStat overall, List<BestCombatPowerStat> classes) {}

  /**
   * @param classPopulation 장비 요약이 없는 캐릭터도 센 그 직업 모집단 수(/stats/classes의 인원과 같은 모집단)
   * @param braceletItemLevelMin 팔찌 전투 특성에 실제로 쓴 아이템 레벨 기준(itemLevelMin과 요청 값 중 큰 값)
   * @param classEngravingStatus 직업 각인 비율의 표본 상태(직업 각인 필터와 무관한 직업 전체 표본 기준)
   */
  public record ClassBuildsResult(
      Meta meta,
      String className,
      String classEngraving,
      long classPopulation,
      BigDecimal braceletItemLevelMin,
      SampleStatus classEngravingStatus,
      java.util.Map<String, String> definitions,
      ClassBuilds.Result builds) {}

  public record ServerStat(
      String serverName,
      long count,
      BigDecimal share,
      BigDecimal itemLevelMedian,
      BigDecimal combatPowerMedian,
      long knownCharacters,
      long rosters,
      SampleStatus sampleStatus) {}

  public record ServersResult(Meta meta, List<ServerStat> rows) {}

  public record Bin(BigDecimal from, BigDecimal to, long count) {}

  public record PercentileOf(
      BigDecimal value, long rank, long total, BigDecimal topPercent, Tier tier) {}

  public record DistributionResult(
      Meta meta, Metric metric, BigDecimal width, List<Bin> bins, PercentileOf percentileOf) {}

  public record SnapshotInfo(String snapshot, Instant aggregatedAt, long population) {}

  public record SnapshotsResult(List<SnapshotInfo> snapshots) {}

  public record RankedCharacter(
      long rank,
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      BigDecimal topPercent,
      Tier tier,
      BigDecimal bestCombatPower,
      StatsPort.ArkPassiveSummary arkPassive,
      StatsPort.EstherWeapon estherWeapon) {}

  /** 수집품 랭킹 결과(#295). unit은 ROSTER·CHARACTER(응답에서는 문자열). */
  public record CollectibleRankingResult(
      Meta meta, String unit, int page, int size, List<RankedCollectible> rows) {}

  public record RankedCollectible(
      long rank,
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      long characterCount,
      BigDecimal progressPercent,
      long point,
      long maxPoint,
      Instant observedAt,
      List<StatsPort.CollectibleType> types) {}

  /** 원정대 랭킹 결과(#297). basis·aggregate는 응답에서 문자열. */
  public record RosterRankingResult(
      Meta meta,
      String basis,
      String aggregate,
      int page,
      int size,
      long excludedRosters,
      List<RankedRoster> rows) {}

  public record RankedRoster(
      long rank,
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      long characterCount,
      long usedCharacters,
      BigDecimal value,
      List<StatsPort.RosterMember> composition) {}

  /** 캐릭터 기준 수집품 순위 결과(#303). reason이 있으면 rank·값은 null. */
  public record CollectibleStandingResult(
      Meta meta,
      String unit,
      String characterName,
      String serverName,
      Long rank,
      long total,
      BigDecimal topPercent,
      BigDecimal progressPercent,
      Long point,
      Long maxPoint,
      String reason) {}

  /** 길드 랭킹 결과(#301). sort는 응답에서 문자열. */
  public record GuildRankingResult(
      Meta meta,
      String sort,
      int page,
      int size,
      int minMembers,
      long excludedGuilds,
      Long excludedMatches,
      Long matchCount,
      List<RankedGuild> rows) {}

  public record RankedGuild(
      long rank,
      String serverName,
      String guildName,
      long memberCount,
      BigDecimal itemLevelAvg,
      BigDecimal itemLevelSum,
      BigDecimal combatPowerAvg,
      long combatPowerCount) {}

  /** 수집품 항목별 보유율 결과(#305). type이 null이면 전체 종류. */
  public record CollectibleOwnershipResult(Meta meta, String type, List<OwnedItem> items) {}

  public record OwnedItem(
      String type,
      String name,
      int sortOrder,
      int maxPoint,
      long ownedRosters,
      long completedRosters,
      BigDecimal ownedPercent,
      BigDecimal completedPercent) {}

  /** 칭호 장착 확인 비율 결과(#311). title이 null이면 전체 칭호. */
  public record TitleEquipResult(Meta meta, String title, List<EquippedTitle> titles) {}

  public record EquippedTitle(String title, long equippedRosters, BigDecimal equippedPercent) {}

  public record RankingResult(
      Meta meta, Metric metric, int page, int size, List<RankedCharacter> rows) {}

  public record EfficiencyRankedCharacter(
      long rank,
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      BigDecimal value,
      java.time.Instant observedAt,
      BigDecimal topPercent,
      Tier tier) {}

  public record EfficiencyRankingResult(
      Meta meta,
      StatsPort.EfficiencyKind kind,
      String ruleVersion,
      int page,
      int size,
      List<EfficiencyRankedCharacter> rows) {}

  public record RankEntry(
      Metric metric,
      Scope scope,
      String scopeValue,
      long rank,
      long total,
      BigDecimal topPercent,
      Tier tier,
      SampleStatus sampleStatus) {}

  public record CharacterRankResult(
      String characterName,
      String serverName,
      String className,
      String position,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      Instant refreshedAt,
      RankVisibility rankVisibility,
      boolean included,
      StatsConditions conditions,
      Instant aggregatedAt,
      String ruleVersion,
      int minSample,
      List<RankEntry> entries) {}
}
