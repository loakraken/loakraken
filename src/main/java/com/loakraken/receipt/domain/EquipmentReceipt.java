package com.loakraken.receipt.domain;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.refining.domain.GrowthCosts;
import com.loakraken.refining.domain.RefiningOptimal;
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
 * 숨결(책 조합은 assumeBook일 때 업화 11~20·전율 12~19단계) 사용·미사용 조합 중 기대 비용이 가장 싼 쪽을 쓴다. 업화 18단계 이하는 성장 지원을
 * 적용한다. 재료는 현재 최저가 1개당 골드로 환산한다.
 */
public final class EquipmentReceipt {
  public static final int FROM_STAGE = 10;

  /** 등급별 산정 시작 단계. 업화는 +10부터, 전율은 +11부터 센다(로아와·일로아 모두 전율 +11 시작, 계산 검증 10-10 09:02 #218 A). */
  static int fromStage(RefiningRules.Grade grade) {
    return grade == RefiningRules.Grade.JEONYUL ? 11 : FROM_STAGE;
  }

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
    PRICE_UNAVAILABLE,
    /** 성장(경험치) 비용 값이 사이트마다 갈려 그 단계만 빼고 계산한 부위. 2026-10-10 전율 21단계 확정 뒤로 쓰지 않는다(계약 호환으로 남김). */
    GROWTH_COST_UNCONFIRMED,
    /** 전율 20→21단계 한계 해방(고통의 가시·해방 비용). 공식 수치가 없어 산정하지 않은 부위. */
    LIMIT_BREAK_UNPRICED
  }

  /** 전율은 20단계 도달 뒤 한계 해방으로 21단계가 된다(공지 13339, 2026-01-07). */
  static final int JEONYUL_LIMIT_BREAK_STAGE = 21;

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
      for (int stage = fromStage(target.track().grade()) + 1; stage <= target.toStage(); stage++) {
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
    return calculate(rules, items, unitPrices, false, null);
  }

  public static Result calculate(
      RefiningRules rules,
      List<CharacterLoadout.Equipment> items,
      Map<String, BigDecimal> unitPrices,
      boolean assumeBook) {
    return calculate(rules, items, unitPrices, assumeBook, null);
  }

  /**
   * assumeBook이 false면 책(재봉술·야금술)을 쓰지 않는 조합만 본다. 게임 재련 창에 "특수 재료 별 효과는 확률에 따라 적용되지 않을 수 있습니다"라고 나오고
   * 그 확률이 공개되지 않아, 확인된 값만 내기 위해서다(PM 총괄 10-10 03:00). true면 책 효과가 항상 적용된다고 가정한다.
   *
   * <p>단계마다 회차별 최적 전략(#284, {@link RefiningOptimal})으로 시도마다 숨결·책 사용 여부를 골라 기대 비용이 가장 작은 값을 쓴다.
   */
  public static Result calculate(
      RefiningRules rules,
      List<CharacterLoadout.Equipment> items,
      Map<String, BigDecimal> unitPrices,
      boolean assumeBook,
      GrowthCosts growthCosts) {
    return calculate(rules, items, unitPrices, assumeBook, growthCosts, new java.util.HashMap<>());
  }

  /** cache: 같은 시세·같은 트랙·단계(방어구 5부위, 원정대의 여러 캐릭터)는 한 번만 계산한다. 한 영수증 안에서만 나눠 쓴다. */
  public static Result calculate(
      RefiningRules rules,
      List<CharacterLoadout.Equipment> items,
      Map<String, BigDecimal> unitPrices,
      boolean assumeBook,
      GrowthCosts growthCosts,
      Map<String, java.util.Optional<StageCost>> cache) {
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
                item.type(), item.name(), Reason.UNSUPPORTED_GRADE, unsupportedGradeMessage(item)));
        continue;
      }
      int from = fromStage(grade);
      if (stage <= from) {
        parts.add(
            new Part(item.type(), item.name(), grade, from, stage, BigDecimal.ZERO, List.of()));
        continue;
      }
      var target = target(rules, item);
      if (target == null) {
        excluded.add(
            new Excluded(
                item.type(), item.name(), Reason.UNSUPPORTED_STAGE, "재련 규칙이 지원하지 않는 단계입니다."));
        continue;
      }
      var stages = new ArrayList<StageCost>();
      var total = BigDecimal.ZERO;
      boolean priced = true;
      for (int next = from + 1; next <= stage; next++) {
        int targetStage = next;
        var cost =
            cache
                .computeIfAbsent(
                    target.track().equipmentType() + ":" + grade + ":" + next,
                    key ->
                        java.util.Optional.ofNullable(
                            cheapest(
                                target.track(),
                                grade,
                                targetStage,
                                unitPrices,
                                assumeBook,
                                growthCosts)))
                .orElse(null);
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
              from,
              stage,
              total.setScale(0, RoundingMode.HALF_UP),
              List.copyOf(stages)));
      if (growthCosts != null)
        for (int next = from + 1; next <= stage; next++) {
          var growthStage = growthCosts.stage(grade, target.track().equipmentType(), next);
          if (growthStage.isPresent() && !growthStage.get().confirmed())
            excluded.add(
                new Excluded(
                    item.type(),
                    item.name(),
                    Reason.GROWTH_COST_UNCONFIRMED,
                    "목표 "
                        + next
                        + "단계 성장 비용은 확인한 값이 엇갈려("
                        + String.format("%,d", growthStage.get().shard())
                        + " 파편 또는 0) 게임 확인 전까지 빼고 계산했습니다."));
        }
      if (grade == RefiningRules.Grade.JEONYUL
          && from < JEONYUL_LIMIT_BREAK_STAGE
          && stage >= JEONYUL_LIMIT_BREAK_STAGE)
        excluded.add(
            new Excluded(
                item.type(),
                item.name(),
                Reason.LIMIT_BREAK_UNPRICED,
                "「" + item.name() + "」: 21단계 한계 해방 비용(고통의 가시·해방 비용)은 공식 수치가 없어 산정하지 않았습니다."));
    }
    // 상급 재련: 공식 Tooltip "[상급 재련] N단계"(equipment-tooltip-v5)로 부위마다 알린다. 단계를 모르는 이전 캐시 부위가 있으면
    // 캐릭터마다 한 줄로 알린다. 비용은 재련 v2 전까지 산정하지 않는다.
    boolean unknownAdvanced = false;
    for (var item : items) {
      if (item == null || item.type() == null || grade(item) == null) continue;
      if (!WEAPON.equals(item.type()) && !ARMOR.contains(item.type())) continue;
      Integer level = item.detail() == null ? null : item.detail().advancedRefineLevel();
      if (level == null) unknownAdvanced = true;
      else if (level > 0)
        excluded.add(
            new Excluded(
                item.type(),
                item.name(),
                Reason.ADVANCED_REFINING,
                "「" + item.name() + "」 상급 재련 " + level + "단계: 아직 산정하지 않습니다(재련 v2 준비 중)."));
    }
    if (unknownAdvanced)
      excluded.add(
          new Excluded(null, null, Reason.ADVANCED_REFINING, "상급 재련은 아직 산정하지 않습니다(재련 v2 준비 중)."));
    var gold = parts.stream().map(Part::gold).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Result(List.copyOf(parts), List.copyOf(excluded), gold);
  }

  /**
   * 회차별 최적 계산 결과를 요청 사이에 나눠 쓴다(PM-백엔드 #287 P1). 키는 트랙·단계·성장 지원·책 허용과 그 단계 재료 시세 값이라, 시세가 바뀌면 새로
   * 계산한다. 크기는 512개로 제한한다(오래 안 쓴 것부터 버림).
   */
  private static final Map<String, java.util.Optional<RefiningOptimal.Result>> OPTIMAL_CACHE =
      java.util.Collections.synchronizedMap(
          new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(
                Map.Entry<String, java.util.Optional<RefiningOptimal.Result>> eldest) {
              return size() > 512;
            }
          });

  private static String optimalKey(
      RefiningRules.Track track,
      RefiningRules.Stage rule,
      boolean growth,
      boolean bookAllowed,
      Map<String, BigDecimal> unitPrices) {
    var key = new StringBuilder();
    key.append(track.grade()).append(':').append(track.equipmentType()).append(':');
    key.append(rule.targetStage()).append(':').append(growth).append(':').append(bookAllowed);
    for (var material : rule.materials())
      key.append('|').append(material.name()).append('=').append(unitPrices.get(material.name()));
    key.append('|')
        .append(RefiningPlan.SHARD)
        .append('=')
        .append(unitPrices.get(RefiningPlan.SHARD));
    if (rule.breath() != null) key.append('|').append(unitPrices.get(rule.breath().name()));
    if (bookAllowed) key.append('|').append(unitPrices.get(rule.book().name()));
    return key.toString();
  }

  /** 한 단계의 회차별 최적 기대 비용(#284). 기본 재료 시세가 없으면 null. 성장 비용(단계당 1회 파편)은 시도 수와 무관해 더한다. */
  private static StageCost cheapest(
      RefiningRules.Track track,
      RefiningRules.Grade grade,
      int stage,
      Map<String, BigDecimal> unitPrices,
      boolean assumeBook,
      GrowthCosts growthCosts) {
    var rule = track.stage(stage);
    boolean growth = grade == RefiningRules.Grade.UPHWA && stage <= GROWTH_SUPPORT_MAX_STAGE;
    boolean bookAllowed = assumeBook && rule.book() != null;
    var optimal =
        OPTIMAL_CACHE
            .computeIfAbsent(
                optimalKey(track, rule, growth, bookAllowed, unitPrices),
                key ->
                    java.util.Optional.ofNullable(
                        RefiningOptimal.calculate(
                            rule,
                            growth ? 0.8 : 1,
                            unitPrices,
                            rule.breath() != null,
                            bookAllowed)))
            .orElse(null);
    if (optimal == null) return null;
    var gold = BigDecimal.valueOf(optimal.expectedGold());
    if (growthCosts != null) {
      var plan =
          RefiningPlan.calculate(
              track,
              grade,
              new RefiningPlan.Options(stage - 1, stage, false, false, growth, 0, 0, true, true),
              growthCosts);
      var stageGrowth = plan.stages().getFirst().growth();
      if (stageGrowth != null && stageGrowth.shard().signum() > 0) {
        var shardPrice = unitPrices.get(RefiningPlan.SHARD);
        if (shardPrice == null) return null;
        gold = gold.add(stageGrowth.shard().multiply(shardPrice));
      }
    }
    return new StageCost(
        stage,
        gold.setScale(0, RoundingMode.HALF_UP),
        optimal.usesBreath(),
        optimal.usesBook(),
        growth);
  }

  private record Target(RefiningRules.Track track, int toStage) {}

  private static Target target(RefiningRules rules, CharacterLoadout.Equipment item) {
    if (item == null || item.type() == null) return null;
    if (!WEAPON.equals(item.type()) && !ARMOR.contains(item.type())) return null;
    var grade = grade(item);
    int stage = stage(item);
    if (grade == null || stage <= fromStage(grade)) return null;
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
    if (fromStage(grade) + 1 < track.minTargetStage() || stage > track.maxTargetStage())
      return null;
    return new Target(track, stage);
  }

  /** 그 부위 장비 이름으로 알린다(고정 예시를 쓰지 않는다, PM-백엔드 10-10 11:06 P2). */
  static String unsupportedGradeMessage(CharacterLoadout.Equipment item) {
    String name = item.name() == null || item.name().isBlank() ? item.type() : item.name();
    return "「" + name + "」: 업화·전율 장비가 아니라 산정하지 않습니다.";
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
