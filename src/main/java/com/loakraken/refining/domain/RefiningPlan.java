package com.loakraken.refining.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 현재 단계에서 목표 단계까지의 시도 수 분포와 재료 소모량을 계산한다.
 *
 * <p>실패할 때마다 기본 확률의 10%가 오르며(최대 10회), 장인의 기운은 시도 최종 성공률 × 0.465만큼 쌓여 100%가 되면 다음 시도가 확정 성공이다.
 */
public record RefiningPlan(
    List<StagePlan> stages,
    List<Quantity> expectedMaterials,
    List<Quantity> worstCaseMaterials,
    BigDecimal expectedGold,
    long worstCaseGold) {
  public static final String SHARD = "운명의 파편";
  static final double ARTISAN_RATE = 0.465;
  static final int MAX_BONUS_FAILURES = 10;
  private static final int MAX_ATTEMPTS = 10_000;
  private static final int GROWTH_SUPPORT_MAX_STAGE = 18;

  public RefiningPlan {
    stages = List.copyOf(stages);
    expectedMaterials = List.copyOf(expectedMaterials);
    worstCaseMaterials = List.copyOf(worstCaseMaterials);
  }

  public record Options(
      int fromStage,
      int toStage,
      boolean useBreath,
      boolean useBook,
      boolean growthSupport,
      int startArtisanEnergyBp,
      int startFailedAttempts) {
    public Options {
      if (toStage <= fromStage) throw new IllegalArgumentException("목표 단계는 현재 단계보다 커야 합니다.");
      if (startArtisanEnergyBp < 0 || startArtisanEnergyBp > 10_000)
        throw new IllegalArgumentException("장인의 기운은 0~100% 범위여야 합니다.");
      if (startFailedAttempts < 0 || startFailedAttempts > MAX_BONUS_FAILURES)
        throw new IllegalArgumentException("실패 횟수는 0~10 범위여야 합니다.");
    }
  }

  public record StagePlan(
      int targetStage,
      int firstAttemptSuccessRateBp,
      BigDecimal expectedAttempts,
      int medianAttempts,
      int p90Attempts,
      int maxAttempts,
      List<Quantity> expectedMaterials,
      List<Quantity> worstCaseMaterials,
      BigDecimal expectedGold,
      long worstCaseGold) {
    public StagePlan {
      expectedMaterials = List.copyOf(expectedMaterials);
      worstCaseMaterials = List.copyOf(worstCaseMaterials);
    }
  }

  public record Quantity(String name, BigDecimal amount) {}

  public static RefiningPlan calculate(
      RefiningRules.Track track, RefiningRules.Grade grade, Options options) {
    if (options.fromStage() < track.minTargetStage() - 1
        || options.toStage() > track.maxTargetStage())
      throw new IllegalArgumentException("지원하지 않는 재련 단계 범위입니다.");
    var stages = new ArrayList<StagePlan>();
    var expectedTotal = new LinkedHashMap<String, BigDecimal>();
    var worstTotal = new LinkedHashMap<String, BigDecimal>();
    var expectedGold = BigDecimal.ZERO;
    long worstGold = 0;
    for (int target = options.fromStage() + 1; target <= options.toStage(); target++) {
      boolean first = target == options.fromStage() + 1;
      var plan =
          stage(
              track.stage(target),
              grade,
              options,
              first ? options.startArtisanEnergyBp() / 10_000.0 : 0,
              first ? options.startFailedAttempts() : 0);
      stages.add(plan);
      plan.expectedMaterials()
          .forEach(
              quantity -> expectedTotal.merge(quantity.name(), quantity.amount(), BigDecimal::add));
      plan.worstCaseMaterials()
          .forEach(
              quantity -> worstTotal.merge(quantity.name(), quantity.amount(), BigDecimal::add));
      expectedGold = expectedGold.add(plan.expectedGold());
      worstGold += plan.worstCaseGold();
    }
    return new RefiningPlan(
        stages, quantities(expectedTotal), quantities(worstTotal), expectedGold, worstGold);
  }

  private static StagePlan stage(
      RefiningRules.Stage rule,
      RefiningRules.Grade grade,
      Options options,
      double startArtisan,
      int startFailures) {
    var breath = options.useBreath() ? rule.breath() : null;
    var book = options.useBook() ? rule.book() : null;
    double artisan = startArtisan;
    int failures = startFailures;
    double reach = 1;
    double expected = 0;
    double expectedAuxiliary = 0;
    double cumulative = 0;
    int median = 0, p90 = 0, attempts = 0, worstAuxiliary = 0, firstRateBp = 0;
    while (attempts < MAX_ATTEMPTS) {
      boolean guaranteed = artisan >= 1 - 1e-9;
      double rateBp =
          guaranteed
              ? 10_000
              : Math.min(
                  10_000,
                  rule.baseRateBp() * (1 + 0.1 * Math.min(failures, MAX_BONUS_FAILURES))
                      + (breath == null ? 0 : (double) breath.rateBp() * breath.maxCount())
                      + (book == null ? 0 : book.rateBp()));
      double probability = rateBp / 10_000;
      boolean auxiliary = !guaranteed && (breath != null || book != null);
      attempts++;
      if (attempts == 1) firstRateBp = (int) Math.round(rateBp);
      expected += reach;
      if (auxiliary) {
        expectedAuxiliary += reach;
        worstAuxiliary++;
      }
      cumulative += reach * probability;
      if (median == 0 && cumulative >= 0.5 - 1e-12) median = attempts;
      if (p90 == 0 && cumulative >= 0.9 - 1e-12) p90 = attempts;
      reach *= 1 - probability;
      if (probability >= 1) break;
      artisan += Math.round(probability * ARTISAN_RATE * 100_000) / 100_000.0;
      failures++;
    }
    double goldRate =
        options.growthSupport()
                && grade == RefiningRules.Grade.UPHWA
                && rule.targetStage() <= GROWTH_SUPPORT_MAX_STAGE
            ? 0.8
            : 1;
    var expectedMaterials = new ArrayList<Quantity>();
    var worstMaterials = new ArrayList<Quantity>();
    for (var material : rule.materials()) {
      expectedMaterials.add(quantity(material.name(), material.count() * expected));
      worstMaterials.add(quantity(material.name(), (double) material.count() * attempts));
    }
    expectedMaterials.add(quantity(SHARD, rule.shard() * expected));
    worstMaterials.add(quantity(SHARD, (double) rule.shard() * attempts));
    if (breath != null) {
      expectedMaterials.add(quantity(breath.name(), breath.maxCount() * expectedAuxiliary));
      worstMaterials.add(quantity(breath.name(), (double) breath.maxCount() * worstAuxiliary));
    }
    if (book != null) {
      expectedMaterials.add(quantity(book.name(), expectedAuxiliary));
      worstMaterials.add(quantity(book.name(), worstAuxiliary));
    }
    return new StagePlan(
        rule.targetStage(),
        firstRateBp,
        scale(expected),
        median,
        p90,
        attempts,
        expectedMaterials,
        worstMaterials,
        scale(rule.gold() * goldRate * expected),
        Math.round(rule.gold() * goldRate * attempts));
  }

  private static Quantity quantity(String name, double amount) {
    return new Quantity(name, scale(amount));
  }

  private static BigDecimal scale(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }

  private static List<Quantity> quantities(Map<String, BigDecimal> totals) {
    return totals.entrySet().stream()
        .map(entry -> new Quantity(entry.getKey(), entry.getValue()))
        .toList();
  }
}
