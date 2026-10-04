package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public record CharacterAnalysis(
    int equipmentCount,
    EquipmentSummary equipment,
    GemSummary gems,
    CardSummary cards,
    EngravingSummary engravings,
    PassiveSummary arkPassive,
    GridSummary arkGrid) {

  public static final String WEAPON_ARMOR = "WEAPON_ARMOR";
  public static final String ACCESSORY = "ACCESSORY";
  private static final Set<String> WEAPON_ARMOR_TYPES = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final Set<String> ACCESSORY_TYPES = Set.of("목걸이", "귀걸이", "반지");
  private static final Pattern GEM_DAMAGE = Pattern.compile("^피해 [0-9.]+% 증가$");
  private static final Pattern GEM_COOLDOWN = Pattern.compile("^재사용 대기시간 [0-9.]+% 감소$");

  public static CharacterAnalysis from(CharacterLoadout loadout) {
    return new CharacterAnalysis(
        loadout.equipment().size(),
        equipment(loadout.equipment()),
        gems(loadout.gems()),
        cards(loadout.cards()),
        loadout.engravings() == null
            ? null
            : new EngravingSummary(
                loadout.engravings().slots().size(),
                loadout.engravings().effects().size(),
                loadout.engravings().arkPassiveEffects().size()),
        passive(loadout.arkPassive()),
        grid(loadout.arkGrid()));
  }

  private static EquipmentSummary equipment(List<CharacterLoadout.Equipment> equipment) {
    int detailCount = 0;
    var refineLevels = new TreeMap<Integer, Integer>();
    int refineCount = 0;
    var quality = new QualityStats();
    var weaponArmor = new QualityStats();
    var accessory = new QualityStats();
    var points = new LinkedHashMap<String, Integer>();
    for (var item : equipment) {
      var detail = item.detail();
      if (detail == null) continue;
      detailCount++;
      if (detail.refineLevel() != null) {
        refineLevels.merge(detail.refineLevel(), 1, Integer::sum);
        refineCount++;
      }
      if (detail.quality() != null) {
        quality.add(detail.quality());
        if (WEAPON_ARMOR_TYPES.contains(item.type())) weaponArmor.add(detail.quality());
        else if (ACCESSORY_TYPES.contains(item.type())) accessory.add(detail.quality());
      }
      var point = detail.arkPassivePoint();
      if (point != null && point.name() != null && point.value() != null)
        points.merge(point.name(), point.value(), Integer::sum);
    }
    return new EquipmentSummary(
        detailCount,
        equipment.size() - detailCount,
        refineCount,
        refineLevels.isEmpty() ? null : refineLevels.firstKey(),
        refineLevels.isEmpty() ? null : refineLevels.lastKey(),
        refineLevels.entrySet().stream()
            .map(entry -> new LevelCount(entry.getKey(), entry.getValue()))
            .toList(),
        quality.count,
        quality.min,
        quality.max,
        quality.average(),
        points.entrySet().stream()
            .map(entry -> new PointValue(entry.getKey(), entry.getValue()))
            .toList(),
        List.of(weaponArmor.toGroup(WEAPON_ARMOR), accessory.toGroup(ACCESSORY)));
  }

  private static final class QualityStats {
    int count;
    Integer min;
    Integer max;
    long sum;

    void add(int value) {
      count++;
      sum += value;
      min = min == null ? value : Math.min(min, value);
      max = max == null ? value : Math.max(max, value);
    }

    BigDecimal average() {
      return count == 0
          ? null
          : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    QualityGroup toGroup(String group) {
      return new QualityGroup(group, count, min, max, average());
    }
  }

  private static GemSummary gems(CharacterLoadout.Gems gems) {
    if (gems == null) return null;
    var counts = new TreeMap<Integer, Integer>();
    long sum = 0;
    int known = 0;
    for (var gem : gems.items()) {
      if (gem.level() == null || gem.level() <= 0) continue;
      counts.merge(gem.level(), 1, Integer::sum);
      sum += gem.level();
      known++;
    }
    int damage = 0, cooldown = 0, unknownEffect = 0;
    if (gems.effects() != null) {
      for (var skill : gems.effects().skills()) {
        boolean isDamage =
            skill.description().stream().anyMatch(line -> GEM_DAMAGE.matcher(line).matches());
        boolean isCooldown =
            skill.description().stream().anyMatch(line -> GEM_COOLDOWN.matcher(line).matches());
        if (isDamage && !isCooldown) damage++;
        else if (isCooldown && !isDamage) cooldown++;
        else unknownEffect++;
      }
    }
    return new GemSummary(
        gems.items().size(),
        gems.items().size() - known,
        counts.isEmpty() ? null : counts.firstKey(),
        counts.isEmpty() ? null : counts.lastKey(),
        known == 0
            ? null
            : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(known), 2, RoundingMode.HALF_UP),
        counts.entrySet().stream()
            .map(entry -> new LevelCount(entry.getKey(), entry.getValue()))
            .toList(),
        damage,
        cooldown,
        unknownEffect);
  }

  private static CardSummary cards(CharacterLoadout.Cards cards) {
    if (cards == null) return null;
    long total = 0;
    int unknown = 0;
    for (var card : cards.items()) {
      if (card.awakeCount() == null
          || card.awakeCount() < 0
          || (card.awakeTotal() != null && card.awakeCount() > card.awakeTotal())) {
        unknown++;
      } else total += card.awakeCount();
    }
    return new CardSummary(cards.items().size(), unknown, total);
  }

  private static PassiveSummary passive(CharacterLoadout.ArkPassive passive) {
    if (passive == null) return null;
    return new PassiveSummary(
        passive.enabled(),
        passive.points().stream()
            .map(
                point ->
                    new PointValue(
                        point.name(),
                        point.value() == null || point.value() < 0 ? null : point.value()))
            .toList());
  }

  private static GridSummary grid(CharacterLoadout.ArkGrid grid) {
    if (grid == null) return null;
    int active = 0, inactive = 0, unknown = 0;
    Integer order = null, chaos = null;
    for (var slot : grid.slots()) {
      if (slot.name() != null && slot.point() != null && slot.point() >= 0) {
        if (slot.name().startsWith("질서")) order = (order == null ? 0 : order) + slot.point();
        else if (slot.name().startsWith("혼돈")) chaos = (chaos == null ? 0 : chaos) + slot.point();
      }
      for (var gem : slot.gems()) {
        if (gem.active() == null) unknown++;
        else if (gem.active()) active++;
        else inactive++;
      }
    }
    return new GridSummary(grid.slots().size(), active, inactive, unknown, order, chaos);
  }

  public record EquipmentSummary(
      int detailCount,
      int detailUnavailableCount,
      int refineCount,
      Integer minRefineLevel,
      Integer maxRefineLevel,
      List<LevelCount> refineDistribution,
      int qualityCount,
      Integer minQuality,
      Integer maxQuality,
      BigDecimal averageQuality,
      List<PointValue> arkPassivePoints,
      List<QualityGroup> qualityGroups) {
    public EquipmentSummary {
      refineDistribution = List.copyOf(refineDistribution);
      arkPassivePoints = List.copyOf(arkPassivePoints);
      qualityGroups = List.copyOf(qualityGroups);
    }
  }

  public record QualityGroup(
      String group, int count, Integer min, Integer max, BigDecimal average) {}

  public record GemSummary(
      int count,
      int unknownLevelCount,
      Integer minLevel,
      Integer maxLevel,
      BigDecimal averageLevel,
      List<LevelCount> levelDistribution,
      int damageEffectCount,
      int cooldownEffectCount,
      int unknownEffectCount) {
    public GemSummary {
      levelDistribution = List.copyOf(levelDistribution);
    }
  }

  public record LevelCount(int level, int count) {}

  public record CardSummary(int count, int unknownAwakeningCount, long knownAwakeningTotal) {}

  public record EngravingSummary(int slotCount, int legacyEffectCount, int arkPassiveEffectCount) {}

  public record PassiveSummary(Boolean enabled, List<PointValue> points) {
    public PassiveSummary {
      points = List.copyOf(points);
    }
  }

  public record PointValue(String name, Integer value) {}

  public record GridSummary(
      int coreCount,
      int activeGemCount,
      int inactiveGemCount,
      int unknownGemCount,
      Integer orderPoints,
      Integer chaosPoints) {}
}
