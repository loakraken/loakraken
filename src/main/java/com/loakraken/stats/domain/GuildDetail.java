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
   * @param rosterGroup 이 결과 안에서만 쓰는 원정대 묶음 번호(1부터, 같은 원정대면 같은 번호). 원정대를 모르면 null. 고정 키가 아니다
   */
  public record Member(
      String characterName,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String guildMemberGrade,
      Instant observedAt,
      Integer rosterGroup) {}

  /** 상위권 평균의 아이템 레벨 기준(길드 탭 비교 기준과 같은 1750). */
  public static final BigDecimal TOP_ITEM_LEVEL = new BigDecimal("1750");

  /**
   * 평균·중앙값·총합은 소수 둘째 자리. 전투력 값은 전투력을 관측한 길드원(combatPowerCount)만으로 낸다.
   *
   * @param combatPowerAvgTop 아이템 레벨 {@link #TOP_ITEM_LEVEL} 이상이면서 전투력을 관측한 길드원의 전투력 평균(없으면 null)
   * @param combatPowerTopCount 그 인원
   * @param combatPowerTotal 전투력을 관측한 길드원 전체의 전투력 합(없으면 null)
   */
  public record Summary(
      BigDecimal itemLevelAvg,
      BigDecimal itemLevelMedian,
      BigDecimal itemLevelMax,
      BigDecimal itemLevelMin,
      BigDecimal combatPowerAvg,
      BigDecimal combatPowerMedian,
      long combatPowerCount,
      BigDecimal combatPowerAvgTop,
      long combatPowerTopCount,
      BigDecimal combatPowerTotal) {}

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

  /** 원정대 키를 모르는 길드원만 있는 경우(묶음 번호 모두 null). */
  public static Result aggregate(List<Member> members) {
    return aggregate(members, Map.of());
  }

  /**
   * members는 비어 있지 않아야 한다(0명은 호출하는 쪽이 404로 다룬다).
   *
   * @param rosterKeys 캐릭터 이름 → 원정대 키(내부 값, 응답에 내보내지 않음). 없는 이름은 원정대를 모르는 것
   */
  public static Result aggregate(List<Member> members, Map<String, ?> rosterKeys) {
    var groups = rosterGroups(members, rosterKeys);
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
                            : member.observedAt().truncatedTo(ChronoUnit.MINUTES),
                        groups.get(member.characterName())))
            .sorted(
                Comparator.comparing(Member::itemLevel, Comparator.reverseOrder())
                    .thenComparing(Member::characterName))
            .toList();
    var itemLevels = sorted.stream().map(Member::itemLevel).toList();
    var combatPowers =
        sorted.stream().map(Member::combatPower).filter(value -> value != null).toList();
    var topCombatPowers =
        sorted.stream()
            .filter(
                member ->
                    member.combatPower() != null
                        && member.itemLevel() != null
                        && member.itemLevel().compareTo(TOP_ITEM_LEVEL) >= 0)
            .map(Member::combatPower)
            .toList();
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
            combatPowers.size(),
            round(mean(topCombatPowers)),
            topCombatPowers.size(),
            combatPowers.isEmpty()
                ? null
                : round(combatPowers.stream().reduce(BigDecimal.ZERO, BigDecimal::add))),
        classes(sorted, count),
        bins(itemLevels, ITEM_LEVEL_WIDTH),
        bins(combatPowers, COMBAT_POWER_WIDTH),
        sorted);
  }

  /**
   * 이 결과 안에서만 쓰는 원정대 묶음 번호(K28). 같은 원정대 키의 길드원을 한 묶음으로 하고, 묶음 인원 많은 순 → 묶음 최고 아이템 레벨 높은 순 → 묶음 안 첫
   * 이름순으로 1부터 매긴다. 키는 내보내지 않으므로 다른 길드·다른 시점 응답끼리 같은 계정을 이어 맞출 수 없다. 넘겨받은 길드원(처리정지 제외 뒤)만 세므로 처리정지
   * 대원이 있는 원정대도 그 대원이 없는 원정대와 같게 보인다(B5).
   */
  private static Map<String, Integer> rosterGroups(List<Member> members, Map<String, ?> keys) {
    var byKey = new java.util.HashMap<Object, List<Member>>();
    for (var member : members) {
      var key = keys.get(member.characterName());
      if (key != null) byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(member);
    }
    Comparator<Member> inGroup =
        Comparator.comparing(Member::itemLevel, Comparator.reverseOrder())
            .thenComparing(Member::characterName);
    var ordered =
        byKey.values().stream()
            .map(group -> group.stream().sorted(inGroup).toList())
            .sorted(
                Comparator.<List<Member>>comparingInt(List::size)
                    .reversed()
                    .thenComparing(group -> group.getFirst().itemLevel(), Comparator.reverseOrder())
                    .thenComparing(group -> group.getFirst().characterName()))
            .toList();
    var result = new java.util.HashMap<String, Integer>();
    for (int i = 0; i < ordered.size(); i++)
      for (var member : ordered.get(i)) result.put(member.characterName(), i + 1);
    return result;
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
