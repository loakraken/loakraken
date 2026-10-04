package com.loakraken.gem.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public record GemSynthesisPlan(
    int baseLevel,
    int targetLevel,
    long targetQuantity,
    long ownedTargetUsed,
    long newTargetQuantity,
    long additionalBaseQuantity,
    long synthesisCount,
    List<InventoryUsage> inventory,
    List<Step> steps) {
  public GemSynthesisPlan {
    inventory = List.copyOf(inventory);
    steps = List.copyOf(steps);
  }

  public static GemSynthesisPlan calculate(
      int baseLevel, int targetLevel, long targetQuantity, List<OwnedGem> owned) {
    if (baseLevel < 1
        || baseLevel >= targetLevel
        || targetLevel > 10
        || targetQuantity < 1
        || targetQuantity > 1000
        || owned == null
        || owned.size() > 10) {
      throw new IllegalArgumentException("잘못된 보석 합성 조건");
    }
    var quantities = new HashMap<Integer, Long>();
    for (var gem : owned) {
      if (gem == null
          || gem.level() < baseLevel
          || gem.level() > targetLevel
          || quantities.putIfAbsent(gem.level(), gem.quantity()) != null) {
        throw new IllegalArgumentException("잘못된 보유 보석 목록");
      }
    }
    var usage = new ArrayList<InventoryUsage>();
    var steps = new ArrayList<Step>();
    long needed = targetQuantity;
    long synthesisCount = 0;
    long targetUsed = 0;
    for (int level = targetLevel; level >= baseLevel; level--) {
      long available = quantities.getOrDefault(level, 0L);
      long used = Math.min(needed, available);
      usage.add(new InventoryUsage(level, available, used, available - used));
      if (level == targetLevel) targetUsed = used;
      needed -= used;
      if (level > baseLevel) {
        if (needed > 0) {
          steps.add(new Step(level - 1, level, Math.multiplyExact(needed, 3), needed));
          synthesisCount = Math.addExact(synthesisCount, needed);
        }
        needed = Math.multiplyExact(needed, 3);
      }
    }
    usage.sort(Comparator.comparingInt(InventoryUsage::level));
    steps.sort(Comparator.comparingInt(Step::outputLevel));
    return new GemSynthesisPlan(
        baseLevel,
        targetLevel,
        targetQuantity,
        targetUsed,
        targetQuantity - targetUsed,
        needed,
        synthesisCount,
        usage,
        steps);
  }

  public record OwnedGem(int level, long quantity) {
    public OwnedGem {
      if (level < 1 || level > 10 || quantity < 0 || quantity > 999_999_999L)
        throw new IllegalArgumentException("잘못된 보유 보석 수량");
    }
  }

  public record InventoryUsage(
      int level, long providedQuantity, long usedQuantity, long remainingQuantity) {}

  public record Step(int inputLevel, int outputLevel, long inputQuantity, long outputQuantity) {}
}
