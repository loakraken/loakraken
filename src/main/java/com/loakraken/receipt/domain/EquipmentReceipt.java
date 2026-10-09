package com.loakraken.receipt.domain;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장비 재련 기대 비용(receipt-v1, 계산 검증 1차 규칙 6절). 부위마다 +10에서 현재 단계까지 t4-refining-v1 한 단계 기대 비용을 더한다. 단계마다
 * 숨결(업화 11~20단계는 책도) 사용·미사용 조합 중 기대 비용이 가장 싼 쪽을 쓴다. 업화 18단계 이하는 성장 지원을 적용한다. 재료는 현재 최저가 1개당 골드로
 * 환산한다.
 */
public final class EquipmentReceipt {
  public static final int FROM_STAGE = 10;
  private static final List<String> ARMOR = List.of("투구", "상의", "하의", "장갑", "어깨");
  private static final String WEAPON = "무기";
  private static final String ARM_GUARD = "완갑";
  private static final int GROWTH_SUPPORT_MAX_STAGE = 18;

  private EquipmentReceipt() {}

  public enum Reason {
    UNSUPPORTED_GRADE,
    UNSUPPORTED_STAGE,
    ARM_GUARD,
    ADVANCED_REFINING,
    PRICE_UNAVAILABLE
  }

  public record StageCost(
      int stage, BigDecimal gold, boolean breath, boolean book, boolean growthSupport) {}

  public record Part(
      String slot,
      String name,
      RefiningRules.Grade grade,
      int fromStage,
      int toStage,
      BigDecimal gold,
      List<StageCost> stages) {}

  public record Excluded(String slot, String name, Reason reason, String message) {}

  public record Result(List<Part> parts, List<Excluded> excluded, BigDecimal gold) {}

  /** 장비에서 산정할 수 있는 부위와 단계 범위. 가격을 묻기 전에 필요한 재료 이름을 모은다. */
  public static Set<String> materialNames(
      RefiningRules rules, List<CharacterLoadout.Equipment> items) {
    var names = new LinkedHashSet<String>();
    for (var item : items) {
      var target = target(rules, item);
      if (target == null) continue;
      for (int stage = FROM_STAGE + 1; stage <= target.toStage(); stage++) {
        var rule = target.track().stage(stage);
        for (var material : rule.materials()) names.add(material.name());
        if (rule.shard() > 0) names.add(RefiningPlan.SHARD);
        if (rule.breath() != null) names.add(rule.breath().name());
        if (rule.book() != null) names.add(rule.book().name());
      }
    }
    return names;
  }

  /** unitPrices: 재료 이름 → 1개당 골드. 없으면 그 재료를 쓰는 조합은 고를 수 없다. */
  public static Result calculate(
      RefiningRules rules,
      List<CharacterLoadout.Equipment> items,
      Map<String, BigDecimal> unitPrices) {
    return calculate(rules, items, unitPrices, false);
  }

  /**
   * assumeBook이 false면 책(재봉술·야금술)을 쓰지 않는 조합만 본다. 게임 재련 창에 "특수 재료 별 효과는 확률에 따라 적용되지 않을 수 있습니다"라고 나오고
   * 그 확률이 공개되지 않아, 확인된 값만 내기 위해서다(PM 총괄 10-10 03:00). true면 책 효과가 항상 적용된다고 가정한다(일로아·로아와와 같은 가정).
   */
  public static Result calculate(
      RefiningRules rules,
      List<CharacterLoadout.Equipment> items,
      Map<String, BigDecimal> unitPrices,
      boolean assumeBook) {
    var parts = new ArrayList<Part>();
    var excluded = new ArrayList<Excluded>();
    for (var item : items) {
      if (item == null || item.type() == null) continue;
      if (ARM_GUARD.equals(item.type())) {
        excluded.add(
            new Excluded(item.type(), item.name(), Reason.ARM_GUARD, "완갑은 1차 산정에서 제외합니다."));
        continue;
      }
      if (!WEAPON.equals(item.type()) && !ARMOR.contains(item.type())) continue;
      var grade = grade(item);
      int stage = stage(item);
      if (grade == null) {
        excluded.add(
            new Excluded(
                item.type(),
                item.name(),
                Reason.UNSUPPORTED_GRADE,
                "업화·전율 외 장비(결단 등)는 산정하지 않습니다."));
        continue;
      }
      if (stage <= FROM_STAGE) {
        parts.add(
            new Part(
                item.type(), item.name(), grade, FROM_STAGE, stage, BigDecimal.ZERO, List.of()));
        continue;
      }
      var target = target(rules, item);
      if (target == null) {
        excluded.add(
            new Excluded(
                item.type(),
                item.name(),
                Reason.UNSUPPORTED_STAGE,
                "재련 규칙(t4-refining-v1)이 지원하지 않는 단계입니다."));
        continue;
      }
      var stages = new ArrayList<StageCost>();
      var total = BigDecimal.ZERO;
      boolean priced = true;
      for (int next = FROM_STAGE + 1; next <= stage; next++) {
        var cost = cheapest(target.track(), grade, next, unitPrices, assumeBook);
        if (cost == null) {
          priced = false;
          break;
        }
        stages.add(cost);
        total = total.add(cost.gold());
      }
      if (!priced) {
        excluded.add(
            new Excluded(
                item.type(), item.name(), Reason.PRICE_UNAVAILABLE, "재련 재료 시세를 찾지 못했습니다."));
        continue;
      }
      parts.add(
          new Part(
              item.type(),
              item.name(),
              grade,
              FROM_STAGE,
              stage,
              total.setScale(0, RoundingMode.HALF_UP),
              List.copyOf(stages)));
    }
    // 상급 재련 단계는 공식 응답 해석이 아직 없어, 4티어 장비가 있으면 캐릭터마다 한 줄로 알린다.
    if (!parts.isEmpty())
      excluded.add(
          new Excluded(null, null, Reason.ADVANCED_REFINING, "상급 재련은 아직 산정하지 않습니다(재련 v2 준비 중)."));
    var gold = parts.stream().map(Part::gold).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Result(List.copyOf(parts), List.copyOf(excluded), gold);
  }

  private static StageCost cheapest(
      RefiningRules.Track track,
      RefiningRules.Grade grade,
      int stage,
      Map<String, BigDecimal> unitPrices,
      boolean assumeBook) {
    var rule = track.stage(stage);
    boolean growth = grade == RefiningRules.Grade.UPHWA && stage <= GROWTH_SUPPORT_MAX_STAGE;
    StageCost best = null;
    for (boolean breath : rule.breath() == null ? List.of(false) : List.of(false, true))
      for (boolean book :
          rule.book() == null || !assumeBook ? List.of(false) : List.of(false, true)) {
        var plan =
            RefiningPlan.calculate(
                track,
                grade,
                new RefiningPlan.Options(stage - 1, stage, breath, book, growth, 0, 0));
        var stagePlan = plan.stages().getFirst();
        var gold = stagePlan.expectedGold();
        boolean priced = true;
        for (var quantity : stagePlan.expectedMaterials()) {
          if (quantity.amount().signum() == 0) continue;
          var price = unitPrices.get(quantity.name());
          if (price == null) {
            priced = false;
            break;
          }
          gold = gold.add(quantity.amount().multiply(price));
        }
        if (!priced) continue;
        if (best == null || gold.compareTo(best.gold()) < 0)
          best = new StageCost(stage, gold, breath, book, growth);
      }
    return best == null
        ? null
        : new StageCost(
            best.stage(),
            best.gold().setScale(0, RoundingMode.HALF_UP),
            best.breath(),
            best.book(),
            best.growthSupport());
  }

  private record Target(RefiningRules.Track track, int toStage) {}

  private static Target target(RefiningRules rules, CharacterLoadout.Equipment item) {
    if (item == null || item.type() == null) return null;
    if (!WEAPON.equals(item.type()) && !ARMOR.contains(item.type())) return null;
    var grade = grade(item);
    int stage = stage(item);
    if (grade == null || stage <= FROM_STAGE) return null;
    var type =
        WEAPON.equals(item.type())
            ? RefiningRules.EquipmentType.WEAPON
            : RefiningRules.EquipmentType.ARMOR;
    RefiningRules.Track track;
    try {
      track = rules.track(grade, type);
    } catch (RuntimeException exception) {
      return null;
    }
    if (FROM_STAGE + 1 < track.minTargetStage() || stage > track.maxTargetStage()) return null;
    return new Target(track, stage);
  }

  /** 이름으로 등급을 정한다. 4티어가 아니면 null. */
  static RefiningRules.Grade grade(CharacterLoadout.Equipment item) {
    if (item.name() == null) return null;
    if (item.detail() != null && item.detail().tier() != null && item.detail().tier() != 4)
      return null;
    if (item.name().contains("업화")) return RefiningRules.Grade.UPHWA;
    if (item.name().contains("전율")) return RefiningRules.Grade.JEONYUL;
    return null;
  }

  private static int stage(CharacterLoadout.Equipment item) {
    var detail = item.detail();
    return detail == null || detail.refineLevel() == null ? 0 : detail.refineLevel();
  }
}
