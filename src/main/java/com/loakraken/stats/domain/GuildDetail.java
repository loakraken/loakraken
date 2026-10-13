package com.loakraken.stats.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 길드 탭 집계(L36·K28·M10, guild-detail-v1). 한 길드에서 최근 관측한 공개 길드원(길드 랭킹 memberCount와 같은 정의)으로
 * 목록·평균·중앙값·분포를 낸다. 표본 방식이라 실제 길드 전체가 아니다.
 */
public final class GuildDetail {
  public static final String RULE_VERSION = "guild-detail-v1";

  /** 길드 랭킹에 넣는 최소 길드원 수와 같다. 이보다 적으면 sampleStatus=SMALL이지만 값은 그대로 준다. */
  public static final int MIN_MEMBERS = 5;

  public static final BigDecimal ITEM_LEVEL_WIDTH = BigDecimal.TEN;
  public static final BigDecimal COMBAT_POWER_WIDTH = BigDecimal.valueOf(100);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private GuildDetail() {}

  /**
   * @param guildMemberGrade 공식 길드 직위 문자열(모르면 null)
   * @param observedAt 마지막 프로필 관측 시각
   */
  public record Member(
      String characterName,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String guildMemberGrade,
      Instant observedAt) {}

  /** 평균·중앙값은 소수 둘째 자리. 전투력 값은 전투력을 관측한 길드원(combatPowerCount)만으로 낸다. */
  public record Summary(
      BigDecimal itemLevelAvg,
      BigDecimal itemLevelMedian,
      BigDecimal itemLevelMax,
      BigDecimal itemLevelMin,
      BigDecimal combatPowerAvg,
      BigDecimal combatPowerMedian,
      long combatPowerCount) {}

  public record ClassCount(String className, long count, BigDecimal share) {}

  public record Bin(BigDecimal from, long count) {}

  /** sampleStatus: SMALL(길드원 MIN_MEMBERS 미만) 또는 READY. */
  public record Result(
      long memberCount,
      String sampleStatus,
      Summary summary,
      List<ClassCount> classes,
      List<Bin> itemLevelBins,
      List<Bin> combatPowerBins,
      List<Member> members) {}

  /** members는 비어 있지 않아야 한다(0명은 호출하는 쪽이 404로 다룬다). */
  public static Result aggregate(List<Member> members) {
    var sorted =
        members.stream()
            .map(
                member ->
                    new Member(
                        member.characterName(),
                        member.className(),
                        member.itemLevel(),
                        member.combatPower(),
                        member.guildMemberGrade(),
                        // 화면 정밀도(분)로 맞춘다.
                        member.observedAt() == null
                            ? null
                            : member.observedAt().truncatedTo(ChronoUnit.MINUTES)))
            .sorted(
                Comparator.comparing(Member::itemLevel, Comparator.reverseOrder())
                    .thenComparing(Member::characterName))
            .toList();
    var itemLevels = sorted.stream().map(Member::itemLevel).toList();
    var combatPowers =
        sorted.stream().map(Member::combatPower).filter(value -> value != null).toList();
    long count = sorted.size();
    return new Result(
        count,
        count < MIN_MEMBERS ? "SMALL" : "READY",
        new Summary(
            round(mean(itemLevels)),
            round(median(itemLevels)),
            round(itemLevels.stream().max(Comparator.naturalOrder()).orElse(null)),
            round(itemLevels.stream().min(Comparator.naturalOrder()).orElse(null)),
            round(mean(combatPowers)),
            round(median(combatPowers)),
            combatPowers.size()),
        classes(sorted, count),
        bins(itemLevels, ITEM_LEVEL_WIDTH),
        bins(combatPowers, COMBAT_POWER_WIDTH),
        sorted);
  }

  /** 인원 많은 순, 같으면 직업 이름순. share는 길드원 대비 %(소수 둘째 자리). */
  private static List<ClassCount> classes(List<Member> members, long count) {
    Map<String, Long> counts =
        members.stream()
            .collect(Collectors.groupingBy(Member::className, TreeMap::new, Collectors.counting()));
    return counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Long>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.<String, Long>comparingByKey()))
        .map(
            entry ->
                new ClassCount(
                    entry.getKey(),
                    entry.getValue(),
                    BigDecimal.valueOf(entry.getValue())
                        .multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP)))
        .toList();
  }

  /** 기존 분포(/stats/distribution)와 같은 구간: FLOOR(값 / 폭) × 폭. 낮은 구간부터. */
  private static List<Bin> bins(List<BigDecimal> values, BigDecimal width) {
    Map<BigDecimal, Long> counts =
        values.stream()
            .collect(Collectors.groupingBy(floorTo(width), TreeMap::new, Collectors.counting()));
    var result = new ArrayList<Bin>();
    counts.forEach((from, n) -> result.add(new Bin(from, n)));
    return List.copyOf(result);
  }

  private static Function<BigDecimal, BigDecimal> floorTo(BigDecimal width) {
    return value -> {
      var floored = value.divide(width, 0, RoundingMode.FLOOR).multiply(width).stripTrailingZeros();
      return floored.scale() < 0 ? floored.setScale(0) : floored;
    };
  }

  private static BigDecimal mean(List<BigDecimal> values) {
    if (values.isEmpty()) return null;
    var sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return sum.divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
  }

  /** percentile_cont(0.5)와 같다: 짝수 개면 가운데 두 값의 평균. */
  private static BigDecimal median(List<BigDecimal> values) {
    if (values.isEmpty()) return null;
    var sorted = values.stream().sorted().toList();
    int middle = sorted.size() / 2;
    if (sorted.size() % 2 == 1) return sorted.get(middle);
    return sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2));
  }

  private static BigDecimal round(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
  }
}
