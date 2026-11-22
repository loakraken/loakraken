package com.loakraken.refining.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 상급 재련 기대 비용(계산 검증 명세 2026-11-22 §1·§3·§4). 재료 이름은 시험용이다(실제 이름은 규칙 파일). */
class AdvancedRefineCostTest {
  private static final AdvancedRefineRules.GrowthSupport SUPPORT =
      new AdvancedRefineRules.GrowthSupport(List.of(0, 10), 0.3, 0.3, 0.1, 0.1, 0.5);

  /**
   * 방어구 0~10 기본(성장 지원 전) 1회: 석 500 · 돌파석 12 · 아비도스 15 · 파편 3,000 · 골드 950 · 숨결 12 · 책 1. 다른 구간은 시험용
   * 값.
   */
  private static AdvancedRefineRules rules() {
    var armor =
        List.of(
            band(0, 500, 12, 15, 3000, 950, 12),
            band(10, 900, 16, 16, 6000, 1800, 18),
            band(20, 1000, 18, 17, 7000, 2000, 20),
            band(30, 1200, 23, 19, 8000, 2400, 24));
    return new AdvancedRefineRules(
        "test",
        Map.of(),
        AdvancedRefineChainTest.ODDS,
        SUPPORT,
        Map.of("ARMOR", armor),
        List.of("enhancedAncestors"));
  }

  private static AdvancedRefineRules.Band band(
      int start, long stone, long leap, long fusion, long shard, long gold, long breath) {
    return new AdvancedRefineRules.Band(
        start,
        List.of(
            new RefiningRules.Material("석", stone),
            new RefiningRules.Material("돌파석", leap),
            new RefiningRules.Material("아비도스", fusion),
            new RefiningRules.Material(RefiningPlan.SHARD, shard)),
        gold,
        new RefiningRules.Material("숨결", breath),
        "책 " + start,
        null,
        start != 0);
  }

  private static final Map<String, BigDecimal> PRICES =
      Map.of(
          "석",
          new BigDecimal("1"),
          "돌파석",
          new BigDecimal("20"),
          "아비도스",
          new BigDecimal("80"),
          RefiningPlan.SHARD,
          new BigDecimal("0.5"),
          "숨결",
          new BigDecimal("300"),
          "책 0",
          new BigDecimal("2000"),
          "책 10",
          new BigDecimal("3000"),
          "책 20",
          new BigDecimal("4000"),
          "책 30",
          new BigDecimal("5000"));

  @Test
  void 성장_지원은_영에서_십_구간_방어구_1회_재료를_공식_화면_값으로_줄인다() {
    var result = AdvancedRefineCost.expected(rules(), "ARMOR", 0, 10, PRICES::get);
    var band = result.bands().getFirst();
    double baseTurns = band.turns().paid() + band.turns().ancestor() + band.turns().enhanced();
    var per = BigDecimal.valueOf(baseTurns);
    // 공식 화면(성장 지원 뒤): 150 · 4 · 5 · 300 · 골드 475.
    assertThat(band.materials().get("석").divide(per, MathContext.DECIMAL64).doubleValue())
        .isCloseTo(150, within(1e-6));
    assertThat(band.materials().get("돌파석").divide(per, MathContext.DECIMAL64).doubleValue())
        .isCloseTo(4, within(1e-6));
    assertThat(band.materials().get("아비도스").divide(per, MathContext.DECIMAL64).doubleValue())
        .isCloseTo(5, within(1e-6));
    assertThat(
            band.materials()
                .get(RefiningPlan.SHARD)
                .divide(per, MathContext.DECIMAL64)
                .doubleValue())
        .isCloseTo(300, within(1e-6));
    assertThat(band.gold().divide(per, MathContext.DECIMAL64).doubleValue())
        .isCloseTo(475, within(1e-6));
    assertThat(AdvancedRefineCost.ceil(12, 0.3)).isEqualTo(4);
    // 정책을 고르는 기준: 고른 정책의 기대 골드가 모든 정책 중 가장 적다.
    assertThat(result.expectedGold()).isPositive();
    assertThat(result.tempering()).isEmpty();
    assertThat(result.policyLimited()).isFalse();
  }

  @Test
  void 책_시세가_없으면_책을_쓰는_정책은_고르지_않는다() {
    var prices = new java.util.HashMap<>(PRICES);
    prices.remove("책 0");
    var result = AdvancedRefineCost.expected(rules(), "ARMOR", 0, 10, prices::get);
    var policy = result.bands().getFirst().policy();
    assertThat(policy.normal() & 2).isZero();
    assertThat(policy.ancestor() & 2).isZero();
    assertThat(result.policyLimited()).isTrue();
  }

  @Test
  void 구간을_나눠_더하고_십_이십_단계에서는_담금질을_표시한다() {
    var result = AdvancedRefineCost.expected(rules(), "ARMOR", 9, 21, PRICES::get);
    assertThat(result.bands())
        .extracting(b -> b.fromStage() + "→" + b.toStage())
        .containsExactly("9→10", "10→20", "20→21");
    assertThat(result.tempering()).containsExactly(10, 20);
    assertThat(result.expectedGold())
        .isEqualByComparingTo(
            result.bands().stream()
                .map(AdvancedRefineCost.BandResult::expectedGold)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    // 21단계부터는 강화 선조 확률이 출처 하나라 표시한다.
    assertThat(result.singleSource()).isTrue();
    assertThat(AdvancedRefineCost.expected(rules(), "ARMOR", 0, 5, PRICES::get).singleSource())
        .isFalse();
  }

  @Test
  void 기본_재료_시세가_없으면_계산하지_않는다() {
    var prices = new java.util.HashMap<>(PRICES);
    prices.remove("돌파석");
    assertThat(AdvancedRefineCost.expected(rules(), "ARMOR", 0, 10, prices::get)).isNull();
  }
}
