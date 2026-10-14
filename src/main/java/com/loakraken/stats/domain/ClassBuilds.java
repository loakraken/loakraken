package com.loakraken.stats.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 직업 상세 빌드 통계(M12 1단계, class-builds-v1). 캐릭터마다 마지막 장비 요약(equipment-summary v1·v2, 집계에 쓰는 필드는 같음) 한
 * 행을 표본으로 항목별 인원·비율을 센다.
 *
 * <p>못 읽은 값은 그 항목의 분모(known)에서 뺀다(추정 금지). 인원(보석은 개수)이 {@link #MIN_CELL} 미만인 칸은 목록에서 뺀다. 한두 명의 증감이
 * 칸으로 드러나지 않게 하려는 것이다. 비율(share)은 분모 대비 %(소수 둘째 자리)로 기존 통계와 같다.
 */
public final class ClassBuilds {
  public static final String RULE_VERSION = "class-builds-v1";
  public static final int MIN_CELL = 5;
  public static final int DEFAULT_LIMIT = 10;
  public static final int MAX_LIMIT = 20;

  /**
   * 빌드 통계의 기본 최소 아이템 레벨(PM 판정 2026-10-13). 다른 통계 기본값(1640)과 달리 1680이다. 1640.00에는 특성이 같은 캐릭터 묶음이 섞여
   * 일부 직업의 분포가 뒤집힌다. itemLevelMin을 주면 그 값을 쓴다.
   */
  public static final BigDecimal DEFAULT_ITEM_LEVEL_MIN = new BigDecimal("1680");

  /** 팔찌 전투 특성의 기본 아이템 레벨 기준(PM 총괄 판정 2026-10-13). */
  public static final BigDecimal DEFAULT_BRACELET_ITEM_LEVEL_MIN = new BigDecimal("1680");

  /** 4티어 보석(통계 gem_mean_level과 같은 범위). 3티어는 같은 레벨이라도 다른 보석이라 세지 않는다. */
  public static final Set<String> GEM_KINDS = Set.of("겁화", "작열", "광휘");

  /** 전투 특성 6종. 주력 특성이 같은 값이면 이 순서로 앞의 것을 고른다. */
  public static final List<String> COMBAT_STATS = List.of("치명", "특화", "신속", "제압", "인내", "숙련");

  /** 팔찌 효과 중 전투 특성 줄(예: "특화 +81"). "치명타 피해 +8.40%" 같은 줄은 맞지 않는다. */
  private static final Pattern BRACELET_STAT = Pattern.compile("^(치명|특화|신속|제압|인내|숙련) \\+\\d+$");

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private ClassBuilds() {}

  /**
   * 한 캐릭터의 표본. 장비 요약에 그 부분이 없으면 null이다.
   *
   * @param itemLevel 캐릭터 아이템 레벨(팔찌 전투 특성 기준에 쓴다)
   * @param classEngraving 직업 각인(공식 ArkPassive.Title)
   * @param engravings 각인 이름
   * @param braceletLines 팔찌 효과 문장
   */
  public record Sample(
      BigDecimal itemLevel,
      String classEngraving,
      List<String> engravings,
      List<Gem> gems,
      CardSet cards,
      Points points,
      CombatStats stats,
      List<String> braceletLines,
      boolean detailed,
      List<Core> cores,
      List<SkillUse> skills) {
    /** v1 표본(코어 이름·스킬 상세 없음). */
    public Sample(
        BigDecimal itemLevel,
        String classEngraving,
        List<String> engravings,
        List<Gem> gems,
        CardSet cards,
        Points points,
        CombatStats stats,
        List<String> braceletLines) {
      this(
          itemLevel,
          classEngraving,
          engravings,
          gems,
          cards,
          points,
          stats,
          braceletLines,
          false,
          null,
          null);
    }
  }

  /** 아크 그리드 코어 한 칸(v2). kind: 질서·혼돈, position: 해·달·별. */
  public record Core(String kind, String position, String name) {}

  /** 레벨 2 이상 스킬(v2). tripods: 선택한 트라이포드, rune: 룬 이름(없으면 null). */
  public record SkillUse(String name, List<TripodKey> tripods, String rune) {}

  /** 트라이포드 칸 key. tier: 단계(1~3). */
  public record TripodKey(Integer tier, String name) {}

  /** mode: DAMAGE(피해 증가)·COOLDOWN(재사용 대기시간 감소). */
  public record Gem(String kind, Integer level, String mode) {}

  public record CardSet(String set, Integer pieces, Integer awakening) {}

  public record Points(Integer evolution, Integer enlightenment, Integer leap) {}

  public record CombatStats(
      Integer crit,
      Integer spec,
      Integer swift,
      Integer domination,
      Integer endurance,
      Integer expertise) {
    List<Integer> values() {
      return java.util.Arrays.asList(crit, spec, swift, domination, endurance, expertise);
    }
  }

  /**
   * 한 항목. known은 그 항목의 분모(값을 읽은 캐릭터 수, 보석은 그 효과 종류의 보석 수)다. hidden은 {@link #MIN_CELL} 미만이라 목록에서 뺀
   * 칸들의 count 합과 그 비율이다(limit으로 잘린 칸은 들지 않는다). 여러 값에 세는 항목(engravings·braceletStats)은 한 캐릭터가 여러 칸에
   * 들어 사람 수가 아니다.
   */
  public record Section<T>(long known, Hidden hidden, List<Cell<T>> cells) {}

  public record Cell<T>(T key, long count, BigDecimal share) {}

  /** 숨긴 칸 합계. share는 known 대비 %이고 known이 0이면 null. */
  public record Hidden(long count, BigDecimal share) {
    static final Hidden NONE = new Hidden(0, null);
  }

  /** 주력 특성. ties는 가장 큰 값이 둘 이상이라 고정 순서로 고른 캐릭터 수다(known에 들어 있음). */
  public record TopStatSection(long known, long ties, Hidden hidden, List<Cell<String>> cells) {}

  /**
   * 팔찌 전투 특성. known은 기준 레벨 이상에서 팔찌를 읽은 캐릭터 수(분모), unread는 기준 레벨 이상인데 팔찌를 못 읽은(없는) 캐릭터 수다. unread가
   * 0이면 분모는 조건에 맞는 캐릭터 전체와 같다.
   */
  public record BraceletSection(long known, long unread, Hidden hidden, List<Cell<String>> cells) {}

  public record Result(
      Section<String> classEngravings,
      Section<List<String>> engravingCombos,
      Section<String> engravings,
      Section<Integer> evolutionPoints,
      Section<Integer> enlightenmentPoints,
      Section<Integer> leapPoints,
      Section<Integer> damageGems,
      Section<Integer> cooldownGems,
      Section<CardSet> cardSets,
      TopStatSection topStats,
      BraceletSection braceletStats) {
    /** 표본이 부족할 때: 모든 항목이 빈 목록이다. */
    public static Result empty() {
      return new Result(
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new Section<>(0, Hidden.NONE, List.of()),
          new TopStatSection(0, 0, Hidden.NONE, List.of()),
          new BraceletSection(0, 0, Hidden.NONE, List.of()));
    }
  }

  /**
   * classEngravings는 직업 전체 표본(allSamples)으로 세고, 나머지 항목은 직업 각인 필터를 건 표본(samples)으로 센다. 필터가 없으면 둘은
   * 같다. 팔찌 전투 특성은 아이템 레벨이 braceletItemLevelMin 이상인 캐릭터만 센다.
   */
  public static Result aggregate(
      List<Sample> allSamples, List<Sample> samples, int limit, BigDecimal braceletItemLevelMin) {
    return new Result(
        perCharacter(allSamples, Sample::classEngraving, limit, Comparator.naturalOrder()),
        perCharacter(samples, ClassBuilds::combo, limit, ClassBuilds::compareNames),
        multi(samples, ClassBuilds::combo, limit),
        perCharacter(
            samples, s -> point(s, Points::evolution), limit, Comparator.<Integer>naturalOrder()),
        perCharacter(
            samples,
            s -> point(s, Points::enlightenment),
            limit,
            Comparator.<Integer>naturalOrder()),
        perCharacter(
            samples, s -> point(s, Points::leap), limit, Comparator.<Integer>naturalOrder()),
        gems(samples, "DAMAGE", limit),
        gems(samples, "COOLDOWN", limit),
        perCharacter(samples, ClassBuilds::cardSet, limit, ClassBuilds::compareCards),
        topStats(samples),
        bracelets(samples, braceletItemLevelMin));
  }

  /** 코어 칸 순서(공식 아크 그리드 슬롯 순). */
  public static final List<String> CORE_KINDS = List.of("질서", "혼돈");

  public static final List<String> CORE_POSITIONS = List.of("해", "달", "별");

  /** 코어 종류·위치 한 칸의 코어 이름 분포. */
  public record CoreSection(String kind, String position, Section<String> names) {}

  /**
   * 채용 칸에 드는 스킬 하나의 트라이포드·룬 분포. users: 그 스킬을 쓰는(레벨 2 이상) v2 표본 수이고 트라이포드·룬의 분모다. 룬을 안 낀 사용자는 분모에만
   * 든다.
   */
  public record SkillDetail(
      String skill, long users, Section<TripodKey> tripods, Section<String> runes) {}

  /**
   * 장비 요약 v2로만 세는 항목(M26 2단계 4번). detailedSamples: 표본 가운데 v2 행 수, coverage: 그 비율(%, 표본이 없으면 null).
   * ready가 false면(v2 표본 minSample 미만) 칸이 모두 비어 있다(작은 표본에서 한두 명의 세팅이 드러나지 않게, 기존 표본 30 규칙과 같다).
   */
  public record Details(
      long detailedSamples,
      BigDecimal coverage,
      boolean ready,
      List<CoreSection> cores,
      Section<String> skills,
      List<SkillDetail> skillDetails) {
    public static Details empty(long detailedSamples, BigDecimal coverage) {
      return new Details(
          detailedSamples,
          coverage,
          false,
          List.of(),
          new Section<>(0, Hidden.NONE, List.of()),
          List.of());
    }
  }

  /**
   * v2 표본으로 코어 이름(종류·위치 6칸), 스킬 채용(여러 값), 채용 칸 스킬마다 트라이포드(여러 값)·룬(한 값)을 센다. 최소 칸(5) 규칙은 기존 항목과 같다.
   */
  public static Details details(List<Sample> samples, int limit, int minSample) {
    var detailed = samples.stream().filter(Sample::detailed).toList();
    var coverage = samples.isEmpty() ? null : share(detailed.size(), samples.size());
    if (detailed.size() < minSample) return Details.empty(detailed.size(), coverage);
    var cores = new ArrayList<CoreSection>();
    for (var kind : CORE_KINDS)
      for (var position : CORE_POSITIONS)
        cores.add(
            new CoreSection(
                kind,
                position,
                perCharacter(
                    detailed,
                    s -> coreName(s, kind, position),
                    limit,
                    Comparator.<String>naturalOrder())));
    var skills = multi(detailed, ClassBuilds::skillNames, limit);
    var skillDetails = new ArrayList<SkillDetail>();
    for (var cell : skills.cells()) {
      var skill = cell.key();
      var users = detailed.stream().filter(s -> skillUse(s, skill) != null).toList();
      skillDetails.add(
          new SkillDetail(
              skill,
              users.size(),
              multiKeys(
                  users,
                  s -> skillUse(s, skill).tripods(),
                  limit,
                  Comparator.comparing(TripodKey::tier, Comparator.nullsLast(Integer::compare))
                      .thenComparing(TripodKey::name, Comparator.nullsLast(String::compareTo))),
              multiKeys(
                  users, s -> rune(skillUse(s, skill)), limit, Comparator.<String>naturalOrder())));
    }
    return new Details(
        detailed.size(), coverage, true, List.copyOf(cores), skills, List.copyOf(skillDetails));
  }

  private static String coreName(Sample sample, String kind, String position) {
    if (sample.cores() == null) return null;
    for (var core : sample.cores())
      if (core != null && kind.equals(core.kind()) && position.equals(core.position()))
        return core.name();
    return null;
  }

  /** 스킬을 못 읽었으면 null(분모에서 빠짐). */
  private static List<String> skillNames(Sample sample) {
    if (sample.skills() == null) return null;
    var names = new ArrayList<String>();
    for (var skill : sample.skills())
      if (skill != null && skill.name() != null) names.add(skill.name());
    return names;
  }

  private static SkillUse skillUse(Sample sample, String name) {
    if (sample.skills() == null) return null;
    for (var skill : sample.skills()) if (skill != null && name.equals(skill.name())) return skill;
    return null;
  }

  /** 룬을 안 낀 스킬도 그 스킬 사용자 분모에는 든다(룬 없음은 못 읽은 값이 아님). 어느 칸에도 세지 않는다. */
  private static List<String> rune(SkillUse use) {
    return use.rune() == null ? List.of() : List.of(use.rune());
  }

  /** 캐릭터마다 key 여러 개를 센다(multi와 같고 key 형식만 다름). 목록이 null이면 분모에서 뺀다. */
  private static <T> Section<T> multiKeys(
      List<Sample> samples, Function<Sample, List<T>> keys, int limit, Comparator<T> tie) {
    var counts = new HashMap<T, Long>();
    long known = 0;
    for (var sample : samples) {
      var values = keys.apply(sample);
      if (values == null) continue;
      known++;
      for (var value : new HashSet<>(values)) if (value != null) counts.merge(value, 1L, Long::sum);
    }
    return section(counts, known, limit, tie);
  }

  private static BraceletSection bracelets(List<Sample> samples, BigDecimal itemLevelMin) {
    var eligible =
        samples.stream()
            .filter(s -> s.itemLevel() != null && s.itemLevel().compareTo(itemLevelMin) >= 0)
            .toList();
    var section = multi(eligible, ClassBuilds::braceletStats, COMBAT_STATS.size());
    long unread = eligible.stream().filter(s -> s.braceletLines() == null).count();
    return new BraceletSection(section.known(), unread, section.hidden(), section.cells());
  }

  /** 캐릭터마다 값 하나를 센다. 값이 null이면 분모에서 뺀다. */
  private static <T> Section<T> perCharacter(
      List<Sample> samples, Function<Sample, T> key, int limit, Comparator<T> tie) {
    var counts = new HashMap<T, Long>();
    long known = 0;
    for (var sample : samples) {
      var value = key.apply(sample);
      if (value == null) continue;
      known++;
      counts.merge(value, 1L, Long::sum);
    }
    return section(counts, known, limit, tie);
  }

  /** 캐릭터마다 값 여러 개를 센다(합이 100%를 넘을 수 있다). 목록이 null이면 분모에서 뺀다. */
  private static Section<String> multi(
      List<Sample> samples, Function<Sample, List<String>> keys, int limit) {
    var counts = new HashMap<String, Long>();
    long known = 0;
    for (var sample : samples) {
      var values = keys.apply(sample);
      if (values == null) continue;
      known++;
      for (var value : new HashSet<>(values)) counts.merge(value, 1L, Long::sum);
    }
    return section(counts, known, limit, ClassBuilds::compareStatsThenNames);
  }

  /** 보석 레벨 분포. 단위는 보석 1개이고 효과 종류(mode)별로 나눈다. 광휘도 효과 종류로 들어간다. */
  private static Section<Integer> gems(List<Sample> samples, String mode, int limit) {
    var counts = new HashMap<Integer, Long>();
    long known = 0;
    for (var sample : samples) {
      var levels = gemLevels(sample, mode);
      if (levels == null) continue;
      for (var level : levels) {
        known++;
        counts.merge(level, 1L, Long::sum);
      }
    }
    return section(counts, known, limit, Comparator.reverseOrder());
  }

  /** 한 캐릭터의 그 효과 종류 4티어 보석 레벨(높은 순). 보석을 못 읽었으면 null, 없으면 빈 목록. */
  static List<Integer> gemLevels(Sample sample, String mode) {
    if (sample.gems() == null) return null;
    var levels = new ArrayList<Integer>();
    for (var gem : sample.gems()) {
      if (gem == null || gem.level() == null || !mode.equals(gem.mode())) continue;
      if (!GEM_KINDS.contains(gem.kind())) continue;
      levels.add(gem.level());
    }
    levels.sort(Comparator.reverseOrder());
    return List.copyOf(levels);
  }

  /**
   * 캐릭터 한 명이 {@link #aggregate}의 어느 칸에 드는지(M26 2단계). 집계와 같은 키 함수를 그대로 써서 웹이 규칙을 다시 구현하지 않게 한다. 값이
   * null이면 그 항목의 분모에서 빠지는 캐릭터다(못 읽음). 팔찌는 아이템 레벨이 braceletItemLevelMin 미만이면
   * braceletEligible=false이고 값이 null이다.
   */
  public record CharacterCells(
      String classEngraving,
      List<String> engravingCombo,
      List<String> engravings,
      Integer evolutionPoints,
      Integer enlightenmentPoints,
      Integer leapPoints,
      List<Integer> damageGems,
      List<Integer> cooldownGems,
      CardSet cardSet,
      TopStat topStat,
      boolean braceletEligible,
      List<String> braceletStats) {}

  /** 고정 조건 최대 개수(PM 판정 D3). 각인·팔찌 특성은 값 하나가 조건 하나다. */
  public static final int MAX_FIXES = 3;

  /**
   * 고정 조건(M26 2단계). 걸린 조건을 모두 만족하는 표본만 남긴다. 판정은 집계·캐릭터 칸과 같은 키 함수다. null·빈 목록은 그 조건이 없는 것이다.
   *
   * @param engravings 이 각인을 모두 가진 캐릭터
   * @param cardSet 가장 큰 카드 세트 이름
   * @param topStat 주력 전투 특성
   * @param braceletStats 팔찌에 이 전투 특성 줄이 모두 있는 캐릭터(팔찌 기준 레벨 이상만)
   */
  public record Fix(
      List<String> engravings,
      String cardSet,
      String topStat,
      Integer evolutionPoints,
      Integer enlightenmentPoints,
      Integer leapPoints,
      List<String> braceletStats) {
    public static final Fix NONE = new Fix(List.of(), null, null, null, null, null, List.of());

    public Fix {
      engravings = engravings == null ? List.of() : List.copyOf(new TreeSet<>(engravings));
      braceletStats = braceletStats == null ? List.of() : List.copyOf(new TreeSet<>(braceletStats));
    }

    /** 걸린 조건 수. */
    public int count() {
      int count = engravings.size() + braceletStats.size();
      for (var value :
          java.util.Arrays.asList(
              cardSet, topStat, evolutionPoints, enlightenmentPoints, leapPoints))
        if (value != null) count++;
      return count;
    }

    public boolean isEmpty() {
      return count() == 0;
    }

    public boolean matches(Sample sample, BigDecimal braceletItemLevelMin) {
      if (!engravings.isEmpty()) {
        var combo = combo(sample);
        if (combo == null || !combo.containsAll(engravings)) return false;
      }
      if (cardSet != null) {
        var cards = ClassBuilds.cardSet(sample);
        if (cards == null || !cardSet.equals(cards.set())) return false;
      }
      if (topStat != null) {
        var top = ClassBuilds.topStat(sample);
        if (top == null || !topStat.equals(top.stat())) return false;
      }
      if (evolutionPoints != null && !evolutionPoints.equals(point(sample, Points::evolution)))
        return false;
      if (enlightenmentPoints != null
          && !enlightenmentPoints.equals(point(sample, Points::enlightenment))) return false;
      if (leapPoints != null && !leapPoints.equals(point(sample, Points::leap))) return false;
      if (!braceletStats.isEmpty()) {
        boolean eligible =
            sample.itemLevel() != null && sample.itemLevel().compareTo(braceletItemLevelMin) >= 0;
        var stats = eligible ? ClassBuilds.braceletStats(sample) : null;
        if (stats == null || !stats.containsAll(braceletStats)) return false;
      }
      return true;
    }

    /** 캐시 키 조각(값이 정렬돼 같은 조건은 같은 키). */
    public String cacheKey() {
      if (isEmpty()) return "-";
      return String.join(",", engravings)
          + "|"
          + cardSet
          + "|"
          + topStat
          + "|"
          + evolutionPoints
          + "|"
          + enlightenmentPoints
          + "|"
          + leapPoints
          + "|"
          + String.join(",", braceletStats);
    }
  }

  public static CharacterCells cellsOf(Sample sample, BigDecimal braceletItemLevelMin) {
    boolean braceletEligible =
        sample.itemLevel() != null && sample.itemLevel().compareTo(braceletItemLevelMin) >= 0;
    var combo = combo(sample);
    return new CharacterCells(
        sample.classEngraving(),
        combo,
        combo,
        point(sample, Points::evolution),
        point(sample, Points::enlightenment),
        point(sample, Points::leap),
        gemLevels(sample, "DAMAGE"),
        gemLevels(sample, "COOLDOWN"),
        cardSet(sample),
        topStat(sample),
        braceletEligible,
        braceletEligible ? distinct(braceletStats(sample)) : null);
  }

  /** 집계(multi)는 한 캐릭터의 같은 값을 한 번만 센다. 칸도 같게 겹친 값을 뺀다(순서 유지). */
  private static List<String> distinct(List<String> values) {
    return values == null ? null : List.copyOf(new java.util.LinkedHashSet<>(values));
  }

  /** 한 캐릭터의 주력 특성. tie는 가장 큰 값이 둘 이상이라 고정 순서의 앞 특성을 골랐다는 뜻이다. */
  public record TopStat(String stat, boolean tie) {}

  /** 6종 중 값이 가장 큰 특성 하나. 같은 값이면 고정 순서의 앞 특성. 6종 중 하나라도 못 읽었으면 null. */
  static TopStat topStat(Sample sample) {
    if (sample.stats() == null) return null;
    var values = sample.stats().values();
    if (values.contains(null)) return null;
    int best = 0;
    for (int i = 1; i < values.size(); i++) if (values.get(i) > values.get(best)) best = i;
    int max = values.get(best);
    return new TopStat(
        COMBAT_STATS.get(best), values.stream().filter(value -> value == max).count() > 1);
  }

  /** 6종 중 값이 가장 큰 특성 하나. 같은 값이면 고정 순서의 앞 특성이고 ties로 센다. 6종 중 하나라도 못 읽었으면 분모에서 뺀다. */
  private static TopStatSection topStats(List<Sample> samples) {
    var counts = new HashMap<String, Long>();
    long known = 0;
    long ties = 0;
    for (var sample : samples) {
      var top = topStat(sample);
      if (top == null) continue;
      if (top.tie()) ties++;
      known++;
      counts.merge(top.stat(), 1L, Long::sum);
    }
    var section = section(counts, known, COMBAT_STATS.size(), ClassBuilds::compareStatsThenNames);
    return new TopStatSection(known, ties, section.hidden(), section.cells());
  }

  private static <T> Section<T> section(
      Map<T, Long> counts, long known, int limit, Comparator<T> tie) {
    var cells = new ArrayList<Cell<T>>();
    counts.entrySet().stream()
        .filter(entry -> entry.getValue() >= MIN_CELL)
        .sorted(
            Comparator.<Map.Entry<T, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey, tie))
        .limit(limit)
        .forEach(
            entry ->
                cells.add(
                    new Cell<>(entry.getKey(), entry.getValue(), share(entry.getValue(), known))));
    long hidden =
        counts.values().stream().filter(count -> count < MIN_CELL).mapToLong(Long::longValue).sum();
    return new Section<>(
        known, new Hidden(hidden, known == 0 ? null : share(hidden, known)), List.copyOf(cells));
  }

  private static Integer point(Sample sample, Function<Points, Integer> tree) {
    return sample.points() == null ? null : tree.apply(sample.points());
  }

  /** 각인 이름을 정렬한 집합. 각인을 못 읽었거나 비었으면 null. */
  private static List<String> combo(Sample sample) {
    if (sample.engravings() == null) return null;
    var names = new TreeSet<String>();
    for (var name : sample.engravings()) if (name != null && !name.isBlank()) names.add(name);
    return names.isEmpty() ? null : List.copyOf(names);
  }

  /** 팔찌에 있는 전투 특성 이름. 팔찌를 못 읽었으면 null, 전투 특성 줄이 없으면 빈 목록. */
  static List<String> braceletStats(Sample sample) {
    if (sample.braceletLines() == null) return null;
    var stats = new ArrayList<String>();
    for (var line : sample.braceletLines()) {
      if (line == null) continue;
      var matcher = BRACELET_STAT.matcher(line.strip());
      if (matcher.find()) stats.add(matcher.group(1));
    }
    return stats;
  }

  private static CardSet cardSet(Sample sample) {
    var cards = sample.cards();
    if (cards == null || cards.set() == null || cards.pieces() == null) return null;
    return new CardSet(cards.set(), cards.pieces(), cards.awakening());
  }

  private static int compareNames(List<String> left, List<String> right) {
    return String.join("|", left).compareTo(String.join("|", right));
  }

  /** 전투 특성은 고정 순서, 그 밖의 이름은 가나다순. */
  private static int compareStatsThenNames(String left, String right) {
    int l = COMBAT_STATS.indexOf(left);
    int r = COMBAT_STATS.indexOf(right);
    if (l >= 0 && r >= 0) return Integer.compare(l, r);
    return left.compareTo(right);
  }

  private static int compareCards(CardSet left, CardSet right) {
    return Comparator.comparing(CardSet::set)
        .thenComparing(CardSet::pieces, Comparator.reverseOrder())
        .thenComparing(CardSet::awakening, Comparator.nullsLast(Comparator.reverseOrder()))
        .compare(left, right);
  }

  private static BigDecimal share(long part, long whole) {
    return BigDecimal.valueOf(part)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
  }

  /** 항목 정의. 응답에 그대로 내려 화면이 한 줄로 보인다. */
  public static Map<String, String> definitions() {
    var definitions = new LinkedHashMap<String, String>();
    definitions.put(
        "sample",
        "최근 확인한 캐릭터 중 장비 요약이 있는 캐릭터(캐릭터마다 마지막 요약 1개). 기본 최소 아이템 레벨 1680, itemLevelMax만 1680 미만이면 1640(실제 값은 meta.conditions.itemLevelMin). 처리정지 캐릭터는 통계 어디에도 없음");
    definitions.put("classEngravings", "직업 각인별 인원. 직업 각인 필터와 무관하게 직업 전체 표본");
    definitions.put("engravingCombos", "각인 이름 집합이 같은 캐릭터 수");
    definitions.put("engravings", "그 각인을 가진 캐릭터 수 / 각인을 읽은 캐릭터 수(여러 각인에 셈)");
    definitions.put("arkPassivePoints", "진화·깨달음·도약 포인트 값별 인원");
    definitions.put("gems", "4티어 보석(겁화·작열·광휘) 1개 단위, 효과 종류(피해·재사용 대기시간)별 레벨 분포");
    definitions.put("cardSets", "가장 큰 카드 세트의 이름·단계·각성 합계");
    definitions.put(
        "topStats", "치명·특화·신속·제압·인내·숙련 중 값이 가장 큰 특성 하나. 같으면 이 순서의 앞 특성이고 ties로 셈. 6종을 다 읽은 캐릭터만");
    definitions.put(
        "braceletStats",
        "팔찌에 그 전투 특성 줄이 있는 캐릭터 수 / 팔찌를 읽은 캐릭터 수(여러 특성에 셈, 합이 100% 넘음). 아이템 레벨 braceletItemLevelMin 이상만. unread는 팔찌를 못 읽은 수(0이면 분모 = 조건에 맞는 전체)");
    definitions.put(
        "details",
        "코어 이름·스킬 상세를 저장한 표본(장비 요약 v2)만 셈. detailedSamples는 그 수, coverage는 표본(장비 요약이 있는 캐릭터, meta.population) 대비 %. 그 수가 30 미만이면 칸이 비어 있음(status COLLECTING)");
    definitions.put("arkGridCores", "질서·혼돈 × 해·달·별 6칸마다 장착한 코어 이름별 인원");
    definitions.put("skills", "그 스킬을 레벨 2 이상 올린 캐릭터 수 / 스킬을 읽은 캐릭터 수(여러 스킬에 셈)");
    definitions.put(
        "skillDetails",
        "skills 칸에 든 스킬마다 사용자(users) 대비 트라이포드(단계·이름, 여러 개에 셈)·룬 이름별 인원. 룬을 안 낀 사용자는 분모에만 듦");
    definitions.put(
        "cells",
        "인원(보석은 개수) "
            + MIN_CELL
            + " 미만인 칸은 목록에서 빼고 그 칸들의 count 합을 hidden(count·share)으로 줌(여러 값에 세는 engravings·braceletStats는 사람 수가 아님). share는 known 대비 %(소수 둘째 자리)");
    return Collections.unmodifiableMap(definitions);
  }
}
