package com.loakraken.stats.application;

import com.loakraken.common.api.BusinessException;
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
 * <p>표본이 MIN_SAMPLE(30) 미만이면 sampleStatus=COLLECTING이고 분위수·평균·백분위·티어는 null이다. 인원 수와 순위는 그대로 준다.
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
          return new SummaryResult(
              meta,
              ready ? summary.itemLevelMean() : null,
              ready ? summary.itemLevelMedian() : null,
              ready ? summary.combatPowerMedian() : null,
              ready ? summary.combatPowerP90() : null,
              summary.dealers(),
              summary.supports(),
              summary.population() - known,
              ready && known > 0 ? share(summary.supports(), known) : null,
              ready && summary.gemSample() >= StatsRules.MIN_SAMPLE ? summary.gemMeanLevel() : null,
              summary.gemSample());
        });
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
                    ready && known > 0 && StatsRules.SUPPORT_CLASSES.contains(row.className())
                        ? share(row.supports(), known)
                        : null,
                    ready ? row.itemLevelMean() : null,
                    ready ? row.gemMeanLevel() : null,
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
                            ready ? row.itemLevelMedian() : null,
                            ready ? row.combatPowerMedian() : null,
                            row.knownCharacters(),
                            row.rosters(),
                            status);
                      })
                  .toList());
        });
  }

  public DistributionResult distribution(
      StatsConditions conditions, Metric metric, BigDecimal width, BigDecimal percentileOf) {
    var binWidth = width == null ? defaultWidth(metric) : width.stripTrailingZeros();
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
        standing(conditions, metric, percentileOf, distribution.meta().aggregatedAt()));
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
                            top == null ? null : Tier.of(top));
                      })
                  .toList());
        });
  }

  /** 캐릭터의 전체·서버·직업 순위. 캐릭터는 이 모집단 조건(필터)에 들어 있어야 순위가 나온다. */
  public CharacterRankResult characterRank(String characterName, StatsConditions conditions) {
    var target =
        guard(() -> stats.target(characterName))
            .orElseThrow(() -> new BusinessException(StatsErrorCode.STATS_CHARACTER_NOT_OBSERVED));
    return cached(
        "rank:" + target.serverName() + ":" + target.characterName() + ":" + key(conditions),
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
        stats.coverage());
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

  public enum Scope {
    ALL,
    SERVER,
    CLASS
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
      Tier tier) {}

  public record RankingResult(
      Meta meta, Metric metric, int page, int size, List<RankedCharacter> rows) {}

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
      boolean included,
      StatsConditions conditions,
      Instant aggregatedAt,
      String ruleVersion,
      int minSample,
      List<RankEntry> entries) {}
}
