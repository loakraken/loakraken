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
 * <p>실패할 때마다 기본 확률의 10%가 오르며(최대 10회), 장인의 기운은 시도 최종 성공률 ÷ 2.15(0.01%p에서 버림)만큼 쌓여 100%가 되면 다음 시도가 확정
 * 성공이다.
 */
public record RefiningPlan(
    List<StagePlan> stages,
    List<Quantity> expectedMaterials,
    List<Quantity> worstCaseMaterials,
    BigDecimal expectedGold,
    long worstCaseGold) {
  public static final String SHARD = "운명의 파편";

  /**
   * 실패 한 번에 쌓이는 장인의 기운(0.01%p 단위, 버림) = floor(시도 최종 성공률(만분율) × 100 ÷ 215), 정확한 십진 계산. 성공률 ÷ 2.15를
   * 소수 둘째 자리에서 버린 값으로, 실측 규칙이고 게임 화면 캡처(3.000% → +1.39%, 3.150% → +1.46%)와 같다. 근사 × 0.465는 경계에서
   * 0.01%p씩 달라지는 성공률이 있어(계산 검증이 도달 가능한 480개 가운데 33개 확인, 예: 6.450% → 3.00%, × 0.465면 2.99%) 쓰지 않는다.
   * 성공률은 소수 여섯째 자리로 맞춰 부동소수 오차(1950.0000000000002 같은 값)를 지운다.
   */
  static int artisanGainHundredths(double rateBp) {
    return BigDecimal.valueOf(rateBp)
        .setScale(6, RoundingMode.HALF_UP)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(215), 0, RoundingMode.DOWN)
        .intValueExact();
  }

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
      int startFailedAttempts,
      boolean growthCost,
      boolean expResearch) {
    /** 성장(경험치) 비용 없이(#200 이전과 같은 값). */
    public Options(
        int fromStage,
        int toStage,
        boolean useBreath,
        boolean useBook,
        boolean growthSupport,
        int startArtisanEnergyBp,
        int startFailedAttempts) {
      this(
          fromStage,
          toStage,
          useBreath,
          useBook,
          growthSupport,
          startArtisanEnergyBp,
          startFailedAttempts,
          false,
          false);
    }

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
      long worstCaseGold,
      Growth growth) {
    public StagePlan {
      expectedMaterials = List.copyOf(expectedMaterials);
      worstCaseMaterials = List.copyOf(worstCaseMaterials);
    }
  }

  public record Quantity(String name, BigDecimal amount) {}

  /**
   * 이 단계의 성장(경험치) 비용(단계당 1회). shard는 영지 연구 적용 뒤 값이고 재료 합계(운명의 파편)에도 들어간다. silver는 수량 표시용이라 합계에 넣지
   * 않는다. confirmed=false면 값이 갈려 계산에서 뺐다는 뜻이다(shard 0).
   */
  public record Growth(BigDecimal shard, long silver, boolean confirmed, boolean research) {}

  public static RefiningPlan calculate(
      RefiningRules.Track track, RefiningRules.Grade grade, Options options) {
    return calculate(track, grade, options, null);
  }

  /** growthCosts가 있고 options.growthCost()면 단계마다 성장 비용(#200)을 더한다. */
  public static RefiningPlan calculate(
      RefiningRules.Track track,
      RefiningRules.Grade grade,
      Options options,
      GrowthCosts growthCosts) {
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
              first ? options.startFailedAttempts() : 0,
              options.growthCost() && growthCosts != null
                  ? growth(growthCosts, grade, track.equipmentType(), target, options.expResearch())
                  : null);
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
      int startFailures,
      Growth growth) {
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
      artisan += artisanGainHundredths(rateBp) / 10_000.0;
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
    // 성장 파편은 시도 수와 관계없이 단계당 한 번이라 기대·최악 모두 같은 값을 재련 파편에 더한다(#200).
    var growthShard = growth == null ? BigDecimal.ZERO : growth.shard();
    expectedMaterials.add(new Quantity(SHARD, scale(rule.shard() * expected).add(growthShard)));
    worstMaterials.add(
        new Quantity(SHARD, scale((double) rule.shard() * attempts).add(growthShard)));
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
        Math.round(rule.gold() * goldRate * attempts),
        growth);
  }

  private static Growth growth(
      GrowthCosts costs,
      RefiningRules.Grade grade,
      RefiningRules.EquipmentType equipmentType,
      int target,
      boolean expResearch) {
    var stage = costs.stage(grade, equipmentType, target).orElse(null);
    if (stage == null) return null;
    if (!stage.confirmed()) return new Growth(BigDecimal.ZERO, 0, false, false);
    boolean research = expResearch && costs.researchApplies(grade, target);
    var shard =
        research
            ? BigDecimal.valueOf(stage.shard())
                .multiply(BigDecimal.valueOf(costs.research().rateBp()))
                .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(stage.shard()).setScale(2, RoundingMode.HALF_UP);
    return new Growth(shard, stage.silver(), true, research);
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
