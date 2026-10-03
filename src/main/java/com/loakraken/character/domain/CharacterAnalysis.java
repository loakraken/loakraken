package com.loakraken.character.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.TreeMap;

public record CharacterAnalysis(
    int equipmentCount,
    GemSummary gems,
    CardSummary cards,
    EngravingSummary engravings,
    PassiveSummary arkPassive,
    GridSummary arkGrid) {

  public static CharacterAnalysis from(CharacterLoadout loadout) {
    return new CharacterAnalysis(
        loadout.equipment().size(),
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
            .toList());
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
    for (var slot : grid.slots()) {
      for (var gem : slot.gems()) {
        if (gem.active() == null) unknown++;
        else if (gem.active()) active++;
        else inactive++;
      }
    }
    return new GridSummary(grid.slots().size(), active, inactive, unknown);
  }

  public record GemSummary(
      int count,
      int unknownLevelCount,
      Integer minLevel,
      Integer maxLevel,
      BigDecimal averageLevel,
      List<LevelCount> levelDistribution) {
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
      int coreCount, int activeGemCount, int inactiveGemCount, int unknownGemCount) {}
}
