package com.loakraken.home.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 공식 이번 주 일정(home-schedule-v1). 시각은 공식 응답처럼 KST 지역 시각이다.
 *
 * <p>모험 섬의 대표 보상(골드·카드 팩·실링·해적 주화)은 보상 항목 자체의 StartTimes가 그 회차를 가리킬 때만 붙는다. 시간 지정이 없는 보상은 매 회차 공통
 * 보상이라 대표 보상 판단에 쓰지 않는다.
 */
public record GameCalendar(List<Entry> entries) {
  public static final String ADVENTURE_ISLAND = "모험 섬";
  public static final String FIELD_BOSS = "필드보스";
  public static final String CHAOS_GATE = "카오스게이트";

  public GameCalendar {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  public record Entry(
      String category,
      String name,
      String icon,
      Integer minItemLevel,
      List<LocalDateTime> startTimes,
      List<Reward> rewards,
      String location) {
    public Entry {
      startTimes =
          startTimes == null
              ? List.of()
              : startTimes.stream().filter(Objects::nonNull).sorted().distinct().toList();
      rewards = rewards == null ? List.of() : List.copyOf(rewards);
      location = location == null || location.isBlank() ? null : location.strip();
    }

    public Entry(
        String category,
        String name,
        String icon,
        Integer minItemLevel,
        List<LocalDateTime> startTimes,
        List<Reward> rewards) {
      this(category, name, icon, minItemLevel, startTimes, rewards, null);
    }

    public List<LocalDateTime> startTimesOn(LocalDate date) {
      return startTimes.stream().filter(time -> time.toLocalDate().equals(date)).toList();
    }

    /** now 이후 첫 회차. 없으면 null. */
    public LocalDateTime nextAt(LocalDateTime now) {
      return startTimes.stream().filter(time -> !time.isBefore(now)).findFirst().orElse(null);
    }

    /**
     * 그 날 보상을 공식 ItemLevel 그룹 순서대로 준다. 시간 지정이 없는 보상은 공통(timed=false), 그 날 회차를 가리키는 보상은 timed=true,
     * 다른 날 회차만 가리키는 보상은 뺀다(rewardKindsOn과 같은 규칙). 보상이 남지 않은 그룹은 뺀다.
     */
    public List<RewardGroup> rewardGroupsOn(LocalDate date) {
      var times = startTimesOn(date);
      var groups = new java.util.LinkedHashMap<Integer, List<DayReward>>();
      for (var reward : rewards) {
        boolean timed = !reward.startTimes().isEmpty();
        if (timed && reward.startTimes().stream().noneMatch(times::contains)) continue;
        groups
            .computeIfAbsent(reward.minItemLevel(), key -> new java.util.ArrayList<>())
            .add(new DayReward(reward.name(), reward.icon(), reward.grade(), timed));
      }
      return groups.entrySet().stream()
          .map(entry -> new RewardGroup(entry.getKey(), List.copyOf(entry.getValue())))
          .toList();
    }

    /** 그 날짜 회차들에 시간 지정으로 붙은 대표 보상 종류. */
    public Set<RewardKind> rewardKindsOn(LocalDate date) {
      var kinds = EnumSet.noneOf(RewardKind.class);
      var times = startTimesOn(date);
      for (var reward : rewards) {
        var kind = RewardKind.of(reward.name());
        if (kind == null || reward.startTimes().isEmpty()) continue;
        if (reward.startTimes().stream().anyMatch(times::contains)) kinds.add(kind);
      }
      return kinds;
    }
  }

  /**
   * @param minItemLevel 공식 RewardItems 그룹의 ItemLevel. 카오스게이트·필드보스는 레벨별로 보상이 다르다.
   */
  public record Reward(
      String name,
      String icon,
      String grade,
      List<LocalDateTime> startTimes,
      Integer minItemLevel) {
    public Reward {
      startTimes =
          startTimes == null ? List.of() : startTimes.stream().filter(Objects::nonNull).toList();
    }

    public Reward(String name, String icon, String grade, List<LocalDateTime> startTimes) {
      this(name, icon, grade, startTimes, null);
    }
  }

  /** 그 날 보상 한 줄. timed는 그 날 회차를 가리키는 StartTimes가 있는 보상이다. */
  public record DayReward(String name, String icon, String grade, boolean timed) {}

  public record RewardGroup(Integer minItemLevel, List<DayReward> rewards) {}

  public enum RewardKind {
    GOLD,
    CARD_PACK,
    SILLING,
    PIRATE_COIN;

    /** 공식 보상 이름으로 대표 보상 종류를 판별한다(2026-10-08 실측 이름). */
    public static RewardKind of(String name) {
      if (name == null) return null;
      if (name.equals("골드")) return GOLD;
      if (name.contains("카드 팩")) return CARD_PACK;
      if (name.equals("실링")) return SILLING;
      if (name.equals("해적 주화") || name.equals("대양의 주화 상자")) return PIRATE_COIN;
      return null;
    }
  }

  public List<Entry> byCategory(String category) {
    return entries.stream()
        .filter(entry -> category.equals(entry.category()))
        .sorted(Comparator.comparing(Entry::name))
        .toList();
  }

  /** 공식 일정이 덮는 날짜 범위. 일정이 없으면 null. */
  public LocalDate coveredFrom() {
    return entries.stream()
        .flatMap(entry -> entry.startTimes().stream())
        .min(Comparator.naturalOrder())
        .map(LocalDateTime::toLocalDate)
        .orElse(null);
  }

  public LocalDate coveredTo() {
    return entries.stream()
        .flatMap(entry -> entry.startTimes().stream())
        .max(Comparator.naturalOrder())
        .map(LocalDateTime::toLocalDate)
        .orElse(null);
  }

  public record DayIslands(LocalDate date, List<IslandDay> islands) {}

  public record IslandDay(String name, Set<RewardKind> rewardKinds) {}

  public record DayKinds(LocalDate date, boolean chaosGate, boolean fieldBoss) {}

  /**
   * 공식 일정이 덮는 날짜마다(첫 날 … 마지막 날, 날짜순) 그 날 회차가 있는 카오스게이트·필드 보스가 있는지. 일정이 없으면 빈 목록. 날짜별 일정 칸과 같은 기준(그
   * 날 시작 시각이 있는 항목).
   */
  public List<DayKinds> weekKinds() {
    var from = coveredFrom();
    var to = coveredTo();
    if (from == null || to == null) return List.of();
    var days = new ArrayList<DayKinds>();
    for (var date = from; !date.isAfter(to); date = date.plusDays(1))
      days.add(new DayKinds(date, hasRoundOn(CHAOS_GATE, date), hasRoundOn(FIELD_BOSS, date)));
    return List.copyOf(days);
  }

  private boolean hasRoundOn(String category, LocalDate date) {
    return byCategory(category).stream().anyMatch(entry -> !entry.startTimesOn(date).isEmpty());
  }

  /** 모험 섬이 열리는 날짜별 섬 목록(날짜순, 섬 이름순). */
  public List<DayIslands> weekly() {
    var days = new TreeMap<LocalDate, List<IslandDay>>();
    for (var island : byCategory(ADVENTURE_ISLAND)) {
      for (var date :
          island.startTimes().stream().map(LocalDateTime::toLocalDate).distinct().toList())
        days.computeIfAbsent(date, ignored -> new ArrayList<>())
            .add(new IslandDay(island.name(), island.rewardKindsOn(date)));
    }
    return days.entrySet().stream()
        .map(entry -> new DayIslands(entry.getKey(), List.copyOf(entry.getValue())))
        .toList();
  }
}
