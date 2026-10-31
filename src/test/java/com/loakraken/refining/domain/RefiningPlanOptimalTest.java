package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 재련 계획 OPTIMAL: 단계마다 회차별 최적 풀이(스펙업·영수증과 같은 엔진)의 시도 분포·수량·기대 비용. */
class RefiningPlanOptimalTest {
  private static final RefiningRules RULES = rules();

  /** 계산 검증 10-25 비교 기준 같은 시각 시세(업화 방어구 11~12단계). */
  private static final Map<String, BigDecimal> PRICES =
      Map.of(
          "운명의 수호석",
          new BigDecimal("0.32"),
          "운명의 돌파석",
          new BigDecimal("5"),
          "아비도스 융화 재료",
          new BigDecimal("197"),
          RefiningPlan.SHARD,
          new BigDecimal("0.092"),
          "빙하의 숨결",
          new BigDecimal("430"),
          "재봉술 : 업화 [11-14]",
          new BigDecimal("2000"));

  @Test
  void 단계_기대_비용은_회차별_최적_풀이와_같고_성장_지원을_적용한다() {
    var track = RULES.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    var optimal =
        RefiningPlan.optimal(
            track,
            RefiningRules.Grade.UPHWA,
            new RefiningPlan.Options(10, 12, false, true, true, 0, 0),
            null,
            PRICES);
    assertThat(optimal).isNotNull();
    // 기존 회차별 최적 기준값(성장 지원 20%): 18,095 + 18,866 = 36,962.
    double total = 0;
    for (var stage : optimal.stages()) total += stage.expectedCostGold();
    assertThat(Math.round(total)).isEqualTo(36_962);
    for (int i = 0; i < 2; i++) {
      var rule = track.stage(11 + i);
      var direct = RefiningOptimal.calculate(rule, 0.8, PRICES, true, true);
      var plan = optimal.plan().stages().get(i);
      var extra = optimal.stages().get(i);
      assertThat(extra.expectedCostGold()).isEqualTo(direct.expectedGold());
      assertThat(plan.expectedAttempts().doubleValue())
          .isCloseTo(direct.expectedAttempts(), org.assertj.core.data.Offset.offset(0.005));
      assertThat(extra.expectedBreaths()).isEqualTo(direct.expectedBreaths());
      assertThat(extra.expectedBooks()).isEqualTo(direct.expectedBooks());
      // 회차 묶음이 실패 경로 회차를 빈틈없이 덮고, 최대 시도 = 경로 + 확정 회차.
      int covered = 0;
      for (var run : extra.breathPath()) {
        assertThat(run.fromAttempt()).isEqualTo(covered + 1);
        covered = run.toAttempt();
      }
      assertThat(covered).isEqualTo(direct.steps().size());
      assertThat(plan.maxAttempts()).isBetween(covered, covered + 1);
      assertThat(plan.medianAttempts()).isLessThanOrEqualTo(plan.p90Attempts());
      assertThat(plan.p90Attempts()).isLessThanOrEqualTo(plan.maxAttempts());
      assertThat(plan.firstAttemptSuccessRateBp()).isEqualTo(direct.firstRateBp());
    }
  }

  @Test
  void 첫_단계는_지금_장인의_기운에서_시작하고_100퍼센트면_한_번에_확정이다() {
    var track = RULES.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    var full =
        RefiningPlan.optimal(
            track,
            RefiningRules.Grade.UPHWA,
            new RefiningPlan.Options(10, 11, false, true, true, 10_000, 0),
            null,
            PRICES);
    var stage = full.plan().stages().getFirst();
    assertThat(stage.maxAttempts()).isEqualTo(1);
    assertThat(stage.expectedAttempts()).isEqualByComparingTo("1.00");
    assertThat(stage.firstAttemptSuccessRateBp()).isEqualTo(10_000);
    assertThat(full.stages().getFirst().breathPath()).isEmpty();
    // 기운이 있으면 처음부터보다 싸다
    var half =
        RefiningPlan.optimal(
            track,
            RefiningRules.Grade.UPHWA,
            new RefiningPlan.Options(10, 11, false, true, true, 5_000, 0),
            null,
            PRICES);
    var zero =
        RefiningPlan.optimal(
            track,
            RefiningRules.Grade.UPHWA,
            new RefiningPlan.Options(10, 11, false, true, true, 0, 0),
            null,
            PRICES);
    assertThat(half.stages().getFirst().expectedCostGold())
        .isLessThan(zero.stages().getFirst().expectedCostGold());
  }

  @Test
  void 필요한_시세가_하나라도_없으면_계산하지_않는다() {
    var track = RULES.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    var noBreath = new java.util.HashMap<>(PRICES);
    noBreath.remove("빙하의 숨결");
    assertThat(
            RefiningPlan.optimal(
                track,
                RefiningRules.Grade.UPHWA,
                new RefiningPlan.Options(10, 12, false, true, true, 0, 0),
                null,
                noBreath))
        .isNull();
    // 책을 쓰지 않으면 책 값은 필요 없다
    var noBook = new java.util.HashMap<>(PRICES);
    noBook.remove("재봉술 : 업화 [11-14]");
    assertThat(
            RefiningPlan.optimal(
                track,
                RefiningRules.Grade.UPHWA,
                new RefiningPlan.Options(10, 12, false, false, true, 0, 0),
                null,
                noBook))
        .isNotNull();
  }

  /**
   * 단계 풀이 캐시(코드 리뷰 10-25): 같은 시세·같은 시작 상태면 다시 풀지 않고, 시작 상태가 다르면 첫 단계만, 그 단계 시세가 바뀌면 그 단계만 다시 푼다.
   */
  @Test
  void 단계_풀이는_시세와_시작_상태가_같으면_캐시를_쓴다() {
    var track = RULES.track(RefiningRules.Grade.UPHWA, RefiningRules.EquipmentType.ARMOR);
    var entries = new java.util.HashMap<String, java.util.Optional<RefiningOptimal.Result>>();
    var solved = new java.util.concurrent.atomic.AtomicInteger();
    OptimalStageCache cache =
        (key, compute) ->
            entries.computeIfAbsent(
                key,
                ignored -> {
                  solved.incrementAndGet();
                  return java.util.Optional.ofNullable(compute.get());
                });
    var options = new RefiningPlan.Options(10, 12, false, true, true, 0, 0);
    var first =
        RefiningPlan.optimal(track, RefiningRules.Grade.UPHWA, options, null, PRICES, cache);
    assertThat(solved.get()).isEqualTo(2);
    var again =
        RefiningPlan.optimal(track, RefiningRules.Grade.UPHWA, options, null, PRICES, cache);
    assertThat(solved.get()).isEqualTo(2);
    assertThat(again.stages()).isEqualTo(first.stages());
    // 캐시를 쓴 결과는 캐시 없이 푼 결과와 같다
    assertThat(
            RefiningPlan.optimal(track, RefiningRules.Grade.UPHWA, options, null, PRICES).stages())
        .isEqualTo(first.stages());
    // 시작 상태(장인의 기운 46.50%·실패 3회)는 첫 단계만 바꾼다
    RefiningPlan.optimal(
        track,
        RefiningRules.Grade.UPHWA,
        new RefiningPlan.Options(10, 12, false, true, true, 4650, 3),
        null,
        PRICES,
        cache);
    assertThat(solved.get()).isEqualTo(3);
    // 두 단계가 함께 쓰는 숨결 시세가 바뀌면 두 단계 모두 다시 푼다
    var breath = new java.util.HashMap<>(PRICES);
    breath.put("빙하의 숨결", new BigDecimal("431"));
    RefiningPlan.optimal(track, RefiningRules.Grade.UPHWA, options, null, breath, cache);
    assertThat(solved.get()).isEqualTo(5);
    // 그 단계에 쓰지 않는 이름의 시세는 키에 넣지 않는다
    var unrelated = new java.util.HashMap<>(PRICES);
    unrelated.put("상관없는 재료", BigDecimal.ONE);
    RefiningPlan.optimal(track, RefiningRules.Grade.UPHWA, options, null, unrelated, cache);
    assertThat(solved.get()).isEqualTo(5);
  }

  /** OPTIMAL 계획도 성장 비용을 켜면 전율 방어구 21단계 한계 해방(파편 72,000 · 실링 720,000 · 고통의 가시 15)을 단계 성장 비용에 싣는다. */
  @Test
  void OPTIMAL도_전율_방어구_21단계_한계_해방_비용을_단계_성장_비용에_싣는다() throws IOException {
    GrowthCosts growth;
    try (var input =
        RefiningPlanOptimalTest.class.getResourceAsStream("/refining/t4-growth-costs.json")) {
      growth = JsonMapper.builder().build().readValue(input, GrowthCosts.class);
    }
    var track = RULES.track(RefiningRules.Grade.JEONYUL, RefiningRules.EquipmentType.ARMOR);
    var rule = track.stage(21);
    var prices = new java.util.HashMap<String, BigDecimal>();
    for (var material : rule.materials()) prices.put(material.name(), BigDecimal.ONE);
    prices.put(RefiningPlan.SHARD, new BigDecimal("0.1"));
    if (rule.breath() != null) prices.put(rule.breath().name(), new BigDecimal("400"));
    var optimal =
        RefiningPlan.optimal(
            track,
            RefiningRules.Grade.JEONYUL,
            new RefiningPlan.Options(20, 21, false, false, false, 0, 0, true, true),
            growth,
            prices);
    assertThat(optimal).isNotNull();
    var stage = optimal.plan().stages().getFirst().growth();
    assertThat(stage.shard()).isEqualByComparingTo("72000");
    assertThat(stage.silver()).isEqualTo(720_000);
    assertThat(stage.extras())
        .containsExactly(new RefiningPlan.Quantity("고통의 가시", BigDecimal.valueOf(15)));
    // 가시는 재료 합계에 넣지 않는다(거래 불가).
    assertThat(optimal.plan().expectedMaterials()).noneMatch(q -> q.name().equals("고통의 가시"));
  }

  @Test
  void 같은_선택이_이어지는_회차는_한_묶음이다() {
    var runs =
        RefiningPlan.runs(
            List.of(
                new RefiningOptimal.Step(20, true, 2000),
                new RefiningOptimal.Step(20, true, 2100),
                new RefiningOptimal.Step(4, false, 1300),
                new RefiningOptimal.Step(0, false, 1000)));
    assertThat(runs)
        .containsExactly(
            new RefiningPlan.BreathRun(1, 2, 20, true),
            new RefiningPlan.BreathRun(3, 3, 4, false),
            new RefiningPlan.BreathRun(4, 4, 0, false));
  }

  private static RefiningRules rules() {
    try (var input =
        RefiningPlanOptimalTest.class.getResourceAsStream("/refining/t4-refining-rules.json")) {
      return JsonMapper.builder().build().readValue(input, RefiningRules.class);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
