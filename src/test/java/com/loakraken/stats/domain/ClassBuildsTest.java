package com.loakraken.stats.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassBuildsTest {
  private static final BigDecimal BRACELET_MIN = new BigDecimal("1680");
  private static final List<String> ENGRAVINGS =
      List.of("원한", "아드레날린", "예리한 둔기", "돌격대장", "저주받은 인형");

  private static ClassBuilds.Sample sample(
      String classEngraving,
      List<String> engravings,
      List<ClassBuilds.Gem> gems,
      ClassBuilds.CombatStats stats,
      List<String> bracelet,
      BigDecimal itemLevel) {
    return new ClassBuilds.Sample(
        itemLevel,
        classEngraving,
        engravings,
        gems,
        new ClassBuilds.CardSet("세상을 구하는 빛", 6, 30),
        new ClassBuilds.Points(140, 101, 70),
        stats,
        bracelet);
  }

  private static ClassBuilds.CombatStats stats(int crit, int spec, int swift) {
    return new ClassBuilds.CombatStats(crit, spec, swift, 60, 60, 60);
  }

  private static List<ClassBuilds.Sample> repeat(int count, ClassBuilds.Sample sample) {
    return new ArrayList<>(Collections.nCopies(count, sample));
  }

  @Test
  void 각인_조합과_개별_각인은_이름_집합으로_세고_5명_미만_칸은_뺀다() {
    var samples =
        repeat(
            6, sample("광기", ENGRAVINGS, List.of(), stats(600, 1800, 60), List.of(), BRACELET_MIN));
    var other = new ArrayList<>(ENGRAVINGS.subList(0, 4));
    other.add("기습의 대가");
    // 순서가 달라도 같은 조합이다.
    samples.addAll(
        repeat(
            5,
            sample(
                "광기", other.reversed(), List.of(), stats(600, 1800, 60), List.of(), BRACELET_MIN)));
    samples.addAll(
        repeat(4, sample("광기", List.of("원한", "타격의 대가"), List.of(), null, null, BRACELET_MIN)));
    // 각인을 못 읽은 캐릭터는 분모에서 빠진다.
    samples.add(sample("광기", null, null, null, null, BRACELET_MIN));

    var result = ClassBuilds.aggregate(samples, samples, 10, BRACELET_MIN);

    assertThat(result.engravingCombos().known()).isEqualTo(15);
    assertThat(result.engravingCombos().cells())
        .extracting(ClassBuilds.Cell::count)
        .containsExactly(6L, 5L);
    assertThat(result.engravingCombos().cells().get(0).key())
        .containsExactly("돌격대장", "아드레날린", "예리한 둔기", "원한", "저주받은 인형");
    assertThat(result.engravingCombos().cells().get(0).share()).isEqualByComparingTo("40.00");
    // 개별 각인: 원한 15명, 아드레날린·예리한 둔기·돌격대장 11명, 저주받은 인형 6명, 기습의 대가 5명, 타격의 대가 4명(숨김).
    assertThat(result.engravings().cells())
        .extracting(ClassBuilds.Cell::key)
        .containsExactly("원한", "돌격대장", "아드레날린", "예리한 둔기", "저주받은 인형", "기습의 대가");
    assertThat(result.engravings().cells().get(0).share()).isEqualByComparingTo("100.00");
    // 숨긴 칸(5 미만)의 합: 원한·타격의 대가 조합 4명, 타격의 대가 각인 4명.
    assertThat(result.engravingCombos().hidden())
        .isEqualTo(new ClassBuilds.Hidden(4, new BigDecimal("26.67")));
    assertThat(result.engravings().hidden())
        .isEqualTo(new ClassBuilds.Hidden(4, new BigDecimal("26.67")));
    assertThat(ClassBuilds.Result.empty().topStats().hidden().share()).isNull();
  }

  @Test
  void 보석은_효과_종류로_묶고_광휘를_넣고_3티어와_효과_모름은_뺀다() {
    var gems =
        List.of(
            new ClassBuilds.Gem("겁화", 10, "DAMAGE"),
            new ClassBuilds.Gem("광휘", 10, "DAMAGE"),
            new ClassBuilds.Gem("작열", 8, "COOLDOWN"),
            new ClassBuilds.Gem("광휘", 8, "COOLDOWN"),
            new ClassBuilds.Gem("멸화", 10, "DAMAGE"),
            new ClassBuilds.Gem("겁화", 9, null));
    var samples = repeat(5, sample("광기", ENGRAVINGS, gems, null, null, BRACELET_MIN));

    var result = ClassBuilds.aggregate(samples, samples, 10, BRACELET_MIN);

    assertThat(result.damageGems().known()).isEqualTo(10);
    assertThat(result.damageGems().cells())
        .containsExactly(new ClassBuilds.Cell<>(10, 10L, new BigDecimal("100.00")));
    assertThat(result.cooldownGems().known()).isEqualTo(10);
    assertThat(result.cooldownGems().cells().get(0).key()).isEqualTo(8);
  }

  @Test
  void 주력_특성은_6종_중_가장_큰_하나이고_동률은_고정_순서와_ties로_센다() {
    var samples =
        repeat(6, sample("광기", ENGRAVINGS, null, stats(600, 1800, 60), null, BRACELET_MIN));
    samples.addAll(
        repeat(5, sample("광기", ENGRAVINGS, null, stats(1000, 1000, 60), null, BRACELET_MIN)));
    samples.addAll(
        repeat(5, sample("광기", ENGRAVINGS, null, stats(60, 600, 1800), null, BRACELET_MIN)));
    // 6종 중 하나라도 못 읽으면 분모에서 빠진다.
    samples.add(
        sample(
            "광기",
            ENGRAVINGS,
            null,
            new ClassBuilds.CombatStats(600, null, 60, 60, 60, 60),
            null,
            BRACELET_MIN));

    var top = ClassBuilds.aggregate(samples, samples, 10, BRACELET_MIN).topStats();

    assertThat(top.known()).isEqualTo(16);
    assertThat(top.ties()).isEqualTo(5);
    assertThat(top.cells())
        .extracting(ClassBuilds.Cell::key, ClassBuilds.Cell::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("특화", 6L),
            org.assertj.core.groups.Tuple.tuple("치명", 5L),
            org.assertj.core.groups.Tuple.tuple("신속", 5L));
    assertThat(top.cells().get(0).share()).isEqualByComparingTo("37.50");
  }

  @Test
  void 팔찌_전투_특성은_줄이_있는_캐릭터를_여러_특성에_세고_기준_레벨_미만은_뺀다() {
    var lines = List.of("특화 +81", "치명 +70", "치명타 피해 +8.40%", "추가 피해가 2.5% 증가한다.");
    var samples = repeat(6, sample("광기", ENGRAVINGS, null, null, lines, new BigDecimal("1700")));
    samples.addAll(
        repeat(5, sample("광기", ENGRAVINGS, null, null, List.of("특화 +99"), BRACELET_MIN)));
    // 전투 특성 줄이 없는 팔찌도 분모에 든다.
    samples.addAll(
        repeat(2, sample("광기", ENGRAVINGS, null, null, List.of("보스 피해 +3.0%"), BRACELET_MIN)));
    // 기준 레벨 미만·팔찌 못 읽음은 빠진다.
    samples.addAll(
        repeat(
            9, sample("광기", ENGRAVINGS, null, null, List.of("신속 +99"), new BigDecimal("1679.99"))));
    samples.add(sample("광기", ENGRAVINGS, null, null, null, new BigDecimal("1700")));

    var bracelet = ClassBuilds.aggregate(samples, samples, 10, BRACELET_MIN).braceletStats();

    assertThat(bracelet.known()).isEqualTo(13);
    assertThat(bracelet.unread()).isEqualTo(1);
    assertThat(bracelet.cells())
        .extracting(ClassBuilds.Cell::key, ClassBuilds.Cell::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("특화", 11L),
            org.assertj.core.groups.Tuple.tuple("치명", 6L));
    assertThat(bracelet.cells().get(0).share()).isEqualByComparingTo("84.62");
  }

  @Test
  void 직업_각인_비율은_필터와_무관하게_전체_표본으로_센다() {
    var all = repeat(6, sample("광기", ENGRAVINGS, null, null, null, BRACELET_MIN));
    all.addAll(repeat(5, sample("비기", ENGRAVINGS, null, null, null, BRACELET_MIN)));
    all.add(sample(null, ENGRAVINGS, null, null, null, BRACELET_MIN));
    var filtered = all.stream().filter(s -> "비기".equals(s.classEngraving())).toList();

    var result = ClassBuilds.aggregate(all, filtered, 10, BRACELET_MIN);

    assertThat(result.classEngravings().known()).isEqualTo(11);
    assertThat(result.classEngravings().cells())
        .extracting(ClassBuilds.Cell::key)
        .containsExactly("광기", "비기");
    assertThat(result.engravingCombos().known()).isEqualTo(5);
  }

  @Test
  void limit은_칸_수를_자른다() {
    var samples = new ArrayList<ClassBuilds.Sample>();
    for (int points = 0; points < 4; points++)
      for (int i = 0; i < 5; i++)
        samples.add(
            new ClassBuilds.Sample(
                BRACELET_MIN,
                "광기",
                ENGRAVINGS,
                null,
                null,
                new ClassBuilds.Points(100 + points, 101, 70),
                null,
                null));

    var result = ClassBuilds.aggregate(samples, samples, 2, BRACELET_MIN);

    assertThat(result.evolutionPoints().known()).isEqualTo(20);
    assertThat(result.evolutionPoints().cells())
        .extracting(ClassBuilds.Cell::key)
        .containsExactly(100, 101);
  }

  private static ClassBuilds.Sample detailed(
      List<ClassBuilds.Core> cores, List<ClassBuilds.SkillUse> skills) {
    return new ClassBuilds.Sample(
        BRACELET_MIN, "광기", ENGRAVINGS, null, null, null, null, null, true, cores, skills);
  }

  private static List<ClassBuilds.Core> cores(String orderSun) {
    var cores = new ArrayList<ClassBuilds.Core>();
    cores.add(new ClassBuilds.Core("질서", "해", orderSun));
    cores.add(new ClassBuilds.Core("질서", "달", "흐름의 결"));
    cores.add(new ClassBuilds.Core("혼돈", "해", null));
    return cores;
  }

  private static ClassBuilds.SkillUse windCut(String secondTier, String rune) {
    return new ClassBuilds.SkillUse(
        "윈드 컷",
        List.of(new ClassBuilds.TripodKey(1, "빠른 준비"), new ClassBuilds.TripodKey(2, secondTier)),
        rune);
  }

  @Test
  void 상세_항목은_v2_표본이_30명_미만이면_칸을_비우고_표본_수와_비율만_준다() {
    var samples = repeat(20, sample("광기", ENGRAVINGS, null, null, null, BRACELET_MIN));
    samples.addAll(repeat(29, detailed(cores("살상연희"), List.of(windCut("강화된 일격", "질풍")))));

    var details = ClassBuilds.details(samples, ClassBuilds.DEFAULT_LIMIT, 30);

    assertThat(details.ready()).isFalse();
    assertThat(details.detailedSamples()).isEqualTo(29);
    assertThat(details.coverage()).isEqualByComparingTo("59.18");
    assertThat(details.cores()).isEmpty();
    assertThat(details.skills().cells()).isEmpty();
    assertThat(details.skillDetails()).isEmpty();
  }

  @Test
  void 상세_항목은_v2_표본만_코어_이름_스킬_트라이포드_룬으로_센다() {
    var samples = repeat(10, sample("광기", ENGRAVINGS, null, null, null, BRACELET_MIN));
    samples.addAll(repeat(20, detailed(cores("살상연희"), List.of(windCut("강화된 일격", "질풍")))));
    samples.addAll(repeat(7, detailed(cores("불타는 일격"), List.of(windCut("약점 포착", null)))));
    var rare =
        List.of(windCut("약점 포착", null), new ClassBuilds.SkillUse("서프라이즈 어택", List.of(), "출혈"));
    samples.addAll(repeat(3, detailed(cores("불타는 일격"), rare)));

    var details = ClassBuilds.details(samples, ClassBuilds.DEFAULT_LIMIT, 30);

    assertThat(details.ready()).isTrue();
    assertThat(details.detailedSamples()).isEqualTo(30);
    assertThat(details.coverage()).isEqualByComparingTo("75.00");
    assertThat(details.cores())
        .extracting(ClassBuilds.CoreSection::kind, ClassBuilds.CoreSection::position)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("질서", "해"),
            org.assertj.core.groups.Tuple.tuple("질서", "달"),
            org.assertj.core.groups.Tuple.tuple("질서", "별"),
            org.assertj.core.groups.Tuple.tuple("혼돈", "해"),
            org.assertj.core.groups.Tuple.tuple("혼돈", "달"),
            org.assertj.core.groups.Tuple.tuple("혼돈", "별"));
    var orderSun = details.cores().get(0).names();
    assertThat(orderSun.known()).isEqualTo(30);
    assertThat(orderSun.cells())
        .extracting(ClassBuilds.Cell::key, ClassBuilds.Cell::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("살상연희", 20L),
            org.assertj.core.groups.Tuple.tuple("불타는 일격", 10L));
    // 이름을 못 읽은 칸(혼돈 해)과 없는 칸(질서 별)은 분모에서 빠진다.
    assertThat(details.cores().get(2).names().known()).isZero();
    assertThat(details.cores().get(3).names().known()).isZero();

    // 3명뿐인 스킬은 칸에서 빠지고(hidden) 상세도 없다.
    assertThat(details.skills().known()).isEqualTo(30);
    assertThat(details.skills().cells()).extracting(ClassBuilds.Cell::key).containsExactly("윈드 컷");
    assertThat(details.skills().hidden().count()).isEqualTo(3);
    assertThat(details.skillDetails()).hasSize(1);
    var windCut = details.skillDetails().get(0);
    assertThat(windCut.users()).isEqualTo(30);
    assertThat(windCut.tripods().cells())
        .extracting(ClassBuilds.Cell::key, ClassBuilds.Cell::count)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(new ClassBuilds.TripodKey(1, "빠른 준비"), 30L),
            org.assertj.core.groups.Tuple.tuple(new ClassBuilds.TripodKey(2, "강화된 일격"), 20L),
            org.assertj.core.groups.Tuple.tuple(new ClassBuilds.TripodKey(2, "약점 포착"), 10L));
    // 룬을 안 낀 10명은 분모에만 든다.
    assertThat(windCut.runes().known()).isEqualTo(30);
    assertThat(windCut.runes().cells())
        .extracting(ClassBuilds.Cell::key, ClassBuilds.Cell::share)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("질풍", new BigDecimal("66.67")));
  }
}
