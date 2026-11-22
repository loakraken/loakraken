package com.loakraken.refining.application;

import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.refining.domain.SpecialRefineRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 특수 재련 돌파석 1개 가치(M42, 계산 검증 'M42 특재 효율 근거 확인 2026-11-22'). 가치 = 그 단계(fromStage → +1) 일반 재련 기대
 * 비용(회차별 최적, 보장 시도에는 숨결 없음) ÷ 평균 필요량(1회 소모량 ÷ 공식 기본 확률). 일반 재련 풀이는 /plans OPTIMAL과 같은 풀이·캐시·계산 자리를
 * 쓴다(공식 호출 없음).
 */
@Service
public class SpecialStoneValueService {
  public static final String UNSOURCED = "SPECIAL_REFINE_UNSOURCED";
  static final String UNSOURCED_REASON = "이 장비·단계의 특수 재련 1회 소모량은 아직 출처를 확인하지 않아 계산하지 않았습니다.";
  static final String PRICE_MISSING_REASON = "재료 시세가 없어 계산하지 않았습니다. 그 재료 가격을 직접 넣으면 계산합니다.";

  private final RefiningPlanService plans;
  private final SpecialRefineRules special;

  public SpecialStoneValueService(RefiningPlanService plans, SpecialRefineRules special) {
    this.plans = plans;
    this.special = special;
  }

  public record Target(
      RefiningRules.Grade grade, RefiningRules.EquipmentType type, int fromStage) {}

  public enum Status {
    OK,
    UNSUPPORTED,
    PRICE_MISSING
  }

  public record MaterialLine(
      String name, BigDecimal amount, BigDecimal unitPriceGold, BigDecimal gold) {}

  public record BreathLine(String name, int max, BigDecimal unitPriceGold) {}

  public record NormalTry(
      List<MaterialLine> materials,
      long refineGold,
      BreathLine breath,
      long costWithoutBreathGold,
      Long costWithMaxBreathGold) {}

  public record Row(
      Target target,
      Status status,
      String reasonCode,
      String reason,
      Integer perTry,
      Integer baseRateBp,
      BigDecimal averageNeeded,
      BigDecimal expectedNormalAttempts,
      BigDecimal expectedNormalCostGold,
      BigDecimal valuePerStoneGold,
      NormalTry normalTry,
      List<String> missingPrices) {}

  public SpecialRefineRules rules() {
    return special;
  }

  /** 출처가 있는(계산할 수 있는) 대상인가. */
  public boolean sourced(Target target) {
    return target.type() != RefiningRules.EquipmentType.ARM_GUARD
        && special.perTry(target.grade(), target.type(), target.fromStage()).isPresent();
  }

  /** 계산할 수 있는 대상들이 쓰는 재료 이름(사용자 가격 이름 검사용). */
  public Set<String> names(List<Target> targets) {
    var names = new LinkedHashSet<String>();
    for (var target : targets) if (sourced(target)) names.addAll(stageNames(stage(target)));
    return names;
  }

  /** 단계 일반 재련 1회에 쓰는 이름(재료·파편·숨결·책). */
  static List<String> stageNames(RefiningRules.Stage stage) {
    var names = new ArrayList<String>();
    for (var material : stage.materials()) names.add(material.name());
    if (stage.shard() > 0) names.add(RefiningPlan.SHARD);
    if (stage.breath() != null) names.add(stage.breath().name());
    if (stage.book() != null) names.add(stage.book().name());
    return names;
  }

  private RefiningRules.Stage stage(Target target) {
    return plans.rules().track(target.grade(), target.type()).stage(target.fromStage() + 1);
  }

  /** prices = 이름 → 1개 골드(사용자 가격으로 시세를 덮은 값). 요청 순서대로 한 줄씩. */
  public List<Row> values(List<Target> targets, Map<String, BigDecimal> prices) {
    var rows = new ArrayList<Row>();
    for (var target : targets) rows.add(row(target, prices));
    return List.copyOf(rows);
  }

  private Row row(Target target, Map<String, BigDecimal> prices) {
    if (!sourced(target))
      return new Row(
          target,
          Status.UNSUPPORTED,
          UNSOURCED,
          UNSOURCED_REASON,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          List.of());
    var stage = stage(target);
    int perTry = special.perTry(target.grade(), target.type(), target.fromStage()).orElseThrow();
    // 평균 필요량 = 1회 소모량 ÷ 기본 확률(만분율).
    var average =
        BigDecimal.valueOf(perTry)
            .multiply(BigDecimal.valueOf(10_000))
            .divide(BigDecimal.valueOf(stage.baseRateBp()), 6, RoundingMode.HALF_UP);
    var missing = new ArrayList<String>();
    for (var name : stageNames(stage)) if (prices.get(name) == null) missing.add(name);
    var normal = missing.isEmpty() ? normalTry(stage, prices) : null;
    if (!missing.isEmpty())
      return new Row(
          target,
          Status.PRICE_MISSING,
          null,
          PRICE_MISSING_REASON,
          perTry,
          stage.baseRateBp(),
          average,
          null,
          null,
          null,
          null,
          List.copyOf(missing));
    // 일반 재련 fromStage → +1, 회차별 최적(시도마다 숨결·책을 골라 기대 비용 최소, 보장 시도에는 숨결 없음).
    var options =
        new RefiningPlan.Options(
            target.fromStage(), target.fromStage() + 1, false, true, true, 0, 0, false, true);
    var optimal = plans.optimal(target.grade(), target.type(), options, prices);
    if (optimal == null)
      return new Row(
          target,
          Status.PRICE_MISSING,
          null,
          PRICE_MISSING_REASON,
          perTry,
          stage.baseRateBp(),
          average,
          null,
          null,
          null,
          null,
          stageNames(stage));
    var cost = BigDecimal.valueOf(optimal.stages().getFirst().expectedCostGold());
    return new Row(
        target,
        Status.OK,
        null,
        null,
        perTry,
        stage.baseRateBp(),
        average,
        optimal.plan().stages().getFirst().expectedAttempts().setScale(2, RoundingMode.HALF_UP),
        cost.setScale(2, RoundingMode.HALF_UP),
        cost.divide(average, 1, RoundingMode.HALF_UP),
        normal,
        List.of());
  }

  /** 일반 재련 1회 내역(재료 × 1개 값, 파편, 재련 골드, 숨결). */
  private static NormalTry normalTry(RefiningRules.Stage stage, Map<String, BigDecimal> prices) {
    var lines = new ArrayList<MaterialLine>();
    var total = BigDecimal.ZERO;
    for (var material : stage.materials()) {
      var unit = prices.get(material.name());
      var gold = unit.multiply(BigDecimal.valueOf(material.count()));
      lines.add(
          new MaterialLine(material.name(), BigDecimal.valueOf(material.count()), unit, gold));
      total = total.add(gold);
    }
    if (stage.shard() > 0) {
      var unit = prices.get(RefiningPlan.SHARD);
      var gold = unit.multiply(BigDecimal.valueOf(stage.shard()));
      lines.add(
          new MaterialLine(RefiningPlan.SHARD, BigDecimal.valueOf(stage.shard()), unit, gold));
      total = total.add(gold);
    }
    total = total.add(BigDecimal.valueOf(stage.gold()));
    BreathLine breath = null;
    Long withBreath = null;
    if (stage.breath() != null) {
      var unit = prices.get(stage.breath().name());
      breath = new BreathLine(stage.breath().name(), stage.breath().maxCount(), unit);
      withBreath =
          total
              .add(unit.multiply(BigDecimal.valueOf(stage.breath().maxCount())))
              .setScale(0, RoundingMode.HALF_UP)
              .longValueExact();
    }
    return new NormalTry(
        List.copyOf(lines),
        stage.gold(),
        breath,
        total.setScale(0, RoundingMode.HALF_UP).longValueExact(),
        withBreath);
  }
}
