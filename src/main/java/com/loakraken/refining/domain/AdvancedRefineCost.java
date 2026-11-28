package com.loakraken.refining.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 상급 재련 기대 비용(계산 검증 명세 2026-11-22 §2·§3). 지금 단계에서 목표 단계까지 10단계 구간마다 기대 턴 수를 구하고(구간은 담금질 상한에서 끊겨
 * 독립), 턴 종류별 1회 비용을 곱해 더한다.
 *
 * <ul>
 *   <li>유료 일반 턴 = 기본 재료 + 골드 + 선택 재료, 무료 일반 턴(테메르) = 선택 재료만, 선조·강화 선조 턴 = 기본 재료 + 골드 + 선택 재료.
 *   <li>정책은 턴 종류마다 K 하나(일반·선조, 21단계부터 강화 선조까지). 기대 골드가 가장 적은 정책, 같으면 기대 턴이 적은 쪽.
 *   <li>선택 재료(숨결·책) 시세가 없으면 그 재료가 드는 K는 고르지 않는다. 기본 재료 시세가 없으면 계산하지 않는다(null).
 *   <li>시작은 구간 안 경험치 (단계 − 구간 시작) × 100, 구슬 0 가정(공식 API는 단계만 준다).
 * </ul>
 */
public final class AdvancedRefineCost {
  private AdvancedRefineCost() {}

  /** 구간 하나의 결과: 고른 정책, 기대 턴, 기대 골드(재료값 + 골드), 기대 재료 개수. */
  public record BandResult(
      int bandStart,
      int fromStage,
      int toStage,
      AdvancedRefineChain.Policy policy,
      AdvancedRefineChain.Turns turns,
      BigDecimal expectedGold,
      Map<String, BigDecimal> materials,
      BigDecimal gold,
      BigDecimal silver,
      boolean singleSource) {}

  /** 전체 결과. tempering = 지나는 담금질 단계(10·20·30에서 다음으로 갈 때, 재료는 금액에 넣지 않음). */
  public record Result(
      List<BandResult> bands,
      BigDecimal expectedGold,
      Map<String, BigDecimal> materials,
      BigDecimal silver,
      List<Integer> tempering,
      boolean singleSource,
      boolean policyLimited) {}

  /**
   * fromStage(지금, 0~39)에서 toStage(1~40, fromStage보다 큼)까지. unitPrice는 재료 이름 → 1개 골드(없으면 null). 기본 재료
   * 시세가 없거나 그 구간 표가 없으면 null.
   */
  public static Result expected(
      AdvancedRefineRules rules,
      String type,
      int fromStage,
      int toStage,
      Function<String, BigDecimal> unitPrice) {
    if (fromStage < 0 || toStage > 40 || toStage <= fromStage)
      throw new IllegalArgumentException("단계는 0 ≤ 지금 < 목표 ≤ 40입니다.");
    var bands = new ArrayList<BandResult>();
    var tempering = new ArrayList<Integer>();
    boolean limited = false;
    int stage = fromStage;
    while (stage < toStage) {
      int start = stage / 10 * 10;
      int end = Math.min(start + 10, toStage);
      // 10·20·30단계에서 다음으로 가려면 담금질이 먼저다(귀속 재료라 금액에 넣지 않음).
      if (stage == start && start > 0) tempering.add(start);
      var band = rules.band(type, stage);
      if (band == null) return null;
      var result = band(rules, band, stage, end, unitPrice);
      if (result == null) return null;
      limited |= result.limited();
      bands.add(result.value());
      stage = end;
    }
    var total = BigDecimal.ZERO;
    var materials = new LinkedHashMap<String, BigDecimal>();
    var silver = BigDecimal.ZERO;
    boolean silverKnown = true;
    boolean single = false;
    for (var band : bands) {
      total = total.add(band.expectedGold());
      band.materials().forEach((name, count) -> materials.merge(name, count, BigDecimal::add));
      if (band.silver() == null) silverKnown = false;
      else silver = silver.add(band.silver());
      single |= band.singleSource();
    }
    return new Result(
        List.copyOf(bands),
        total,
        Map.copyOf(materials),
        silverKnown ? silver : null,
        List.copyOf(tempering),
        single || rules.singleSource().contains("enhancedAncestors") && toStage > 20,
        limited);
  }

  private record Picked(BandResult value, boolean limited) {}

  private static Picked band(
      AdvancedRefineRules rules,
      AdvancedRefineRules.Band band,
      int fromStage,
      int toStage,
      Function<String, BigDecimal> unitPrice) {
    boolean supported = rules.supported(band.start());
    var gs = rules.growthSupport();
    // 기본 재료 1회분(성장 지원 반영)과 그 골드 값.
    var baseCounts = new LinkedHashMap<String, BigDecimal>();
    var baseValue = BigDecimal.ZERO;
    for (var material : band.materials()) {
      boolean shard = RefiningPlan.SHARD.equals(material.name());
      long count =
          supported ? ceil(material.count(), shard ? gs.shard() : gs.material()) : material.count();
      var price = unitPrice.apply(material.name());
      if (price == null) return null;
      baseCounts.put(material.name(), BigDecimal.valueOf(count));
      baseValue = baseValue.add(price.multiply(BigDecimal.valueOf(count)));
    }
    long gold = supported ? ceil(band.gold(), gs.gold()) : band.gold();
    baseValue = baseValue.add(BigDecimal.valueOf(gold));
    Long silver =
        band.silver() == null ? null : supported ? ceil(band.silver(), gs.silver()) : band.silver();
    long breathCount =
        band.breath() == null
            ? 0
            : supported ? ceil(band.breath().count(), gs.breath()) : band.breath().count();
    var breathPrice = band.breath() == null ? null : unitPrice.apply(band.breath().name());
    var bookPrice = band.book() == null ? null : unitPrice.apply(band.book());
    // K별 선택 재료 값(시세가 없는 재료가 드는 K는 null = 고르지 않음).
    var choice = new BigDecimal[4];
    for (int k = 0; k < 4; k++) {
      boolean breath = (k & 1) != 0;
      boolean book = (k & 2) != 0;
      if ((breath && breathPrice == null) || (book && bookPrice == null)) continue;
      var value = BigDecimal.ZERO;
      if (breath) value = value.add(breathPrice.multiply(BigDecimal.valueOf(breathCount)));
      if (book) value = value.add(bookPrice);
      choice[k] = value;
    }
    boolean limited = choice[1] == null || choice[2] == null || choice[3] == null;
    int startExp = (fromStage - band.start()) * 100;
    int targetExp = (toStage - band.start()) * 100;
    boolean high = band.start() >= 20;
    BandResult best = null;
    for (var policy : AdvancedRefineChain.Policy.family(high)) {
      if (choice[policy.normal()] == null || choice[policy.ancestor()] == null) continue;
      if (high && choice[policy.enhanced()] == null) continue;
      var turns =
          AdvancedRefineChain.expected(rules.odds(), band.start(), startExp, targetExp, policy);
      var normalChoice = choice[policy.normal()];
      var value =
          baseValue
              .add(normalChoice)
              .multiply(BigDecimal.valueOf(turns.paid()))
              .add(normalChoice.multiply(BigDecimal.valueOf(turns.free())))
              .add(
                  baseValue
                      .add(choice[policy.ancestor()])
                      .multiply(BigDecimal.valueOf(turns.ancestor())))
              .add(
                  high
                      ? baseValue
                          .add(choice[policy.enhanced()])
                          .multiply(BigDecimal.valueOf(turns.enhanced()))
                      : BigDecimal.ZERO);
      if (best != null
          && (value.compareTo(best.expectedGold()) > 0
              || (value.compareTo(best.expectedGold()) == 0
                  && turns.total() >= best.turns().total()))) continue;
      // 기대 재료: 기본 재료는 유료·선조·강화 턴마다, 숨결·책은 그 턴의 K대로(무료 턴 포함).
      double baseTurns = turns.paid() + turns.ancestor() + turns.enhanced();
      var counts = new LinkedHashMap<String, BigDecimal>();
      baseCounts.forEach(
          (name, count) -> counts.put(name, count.multiply(BigDecimal.valueOf(baseTurns))));
      double breathTurns =
          ((policy.normal() & 1) != 0 ? turns.paid() + turns.free() : 0)
              + ((policy.ancestor() & 1) != 0 ? turns.ancestor() : 0)
              + (high && (policy.enhanced() & 1) != 0 ? turns.enhanced() : 0);
      double bookTurns =
          ((policy.normal() & 2) != 0 ? turns.paid() + turns.free() : 0)
              + ((policy.ancestor() & 2) != 0 ? turns.ancestor() : 0)
              + (high && (policy.enhanced() & 2) != 0 ? turns.enhanced() : 0);
      if (breathTurns > 0)
        counts.merge(
            band.breath().name(), BigDecimal.valueOf(breathTurns * breathCount), BigDecimal::add);
      if (bookTurns > 0) counts.merge(band.book(), BigDecimal.valueOf(bookTurns), BigDecimal::add);
      best =
          new BandResult(
              band.start(),
              fromStage,
              toStage,
              policy,
              turns,
              value,
              Map.copyOf(counts),
              BigDecimal.valueOf(gold * baseTurns),
              silver == null ? null : BigDecimal.valueOf(silver * baseTurns),
              band.singleSource());
    }
    return best == null ? null : new Picked(best, limited);
  }

  /** 개수 × 배율을 올림(공식 성장 지원 '올림'). */
  static long ceil(long count, double rate) {
    return BigDecimal.valueOf(count)
        .multiply(BigDecimal.valueOf(rate))
        .setScale(0, java.math.RoundingMode.CEILING)
        .longValueExact();
  }
}
