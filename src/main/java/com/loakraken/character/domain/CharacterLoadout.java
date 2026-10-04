package com.loakraken.character.domain;

import java.util.List;

public record CharacterLoadout(
    List<Equipment> equipment,
    Engravings engravings,
    Gems gems,
    Cards cards,
    ArkPassive arkPassive,
    ArkGrid arkGrid) {
  public CharacterLoadout {
    equipment = equipment == null ? List.of() : List.copyOf(equipment);
  }

  public record Equipment(
      String type, String name, String icon, String grade, EquipmentDetail detail) {}

  public record EquipmentDetail(
      Integer refineLevel,
      Integer quality,
      Integer itemLevel,
      Integer tier,
      PointBonus arkPassivePoint,
      List<EffectGroup> effects,
      List<StoneEngraving> stoneEngravings,
      List<PolishEffect> polishEffects) {
    public static final String PARSER_VERSION = "equipment-tooltip-v2";

    public EquipmentDetail {
      effects = effects == null ? List.of() : List.copyOf(effects);
      stoneEngravings = stoneEngravings == null ? List.of() : List.copyOf(stoneEngravings);
      polishEffects = polishEffects == null ? List.of() : List.copyOf(polishEffects);
    }
  }

  public enum PolishGrade {
    HIGH,
    MID,
    LOW
  }

  public record PolishEffect(String name, String value, PolishGrade grade) {}

  public record PointBonus(String name, Integer value) {}

  public record EffectGroup(String title, List<String> lines) {
    public EffectGroup {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  public record StoneEngraving(String name, Integer level) {}

  public record Engravings(
      List<Engraving> slots,
      List<EngravingEffect> effects,
      List<PassiveEngraving> arkPassiveEffects) {
    public Engravings {
      slots = slots == null ? List.of() : List.copyOf(slots);
      effects = effects == null ? List.of() : List.copyOf(effects);
      arkPassiveEffects = arkPassiveEffects == null ? List.of() : List.copyOf(arkPassiveEffects);
    }
  }

  public record Engraving(Integer slot, String name, String icon) {}

  public record EngravingEffect(String icon, String name, String description) {}

  public record PassiveEngraving(
      Integer abilityStoneLevel, String grade, Integer level, String name, String description) {}

  public record Gems(List<Gem> items, GemEffects effects) {
    public Gems {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record Gem(Integer slot, String name, String icon, Integer level, String grade) {}

  public record GemEffects(String description, List<GemSkill> skills) {
    public GemEffects {
      skills = skills == null ? List.of() : List.copyOf(skills);
    }
  }

  public record GemSkill(
      Integer gemSlot, String name, List<String> description, String option, String icon) {
    public GemSkill {
      description = description == null ? List.of() : List.copyOf(description);
    }
  }

  public record Cards(List<Card> items, List<CardSet> effects) {
    public Cards {
      items = items == null ? List.of() : List.copyOf(items);
      effects = effects == null ? List.of() : List.copyOf(effects);
    }
  }

  public record Card(
      Integer slot,
      String name,
      String icon,
      Integer awakeCount,
      Integer awakeTotal,
      String grade) {}

  public record CardSet(Integer index, List<Integer> cardSlots, List<Effect> items) {
    public CardSet {
      cardSlots = cardSlots == null ? List.of() : List.copyOf(cardSlots);
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record Effect(String name, String description) {}

  public record ArkPassive(
      String title, Boolean enabled, List<PassivePoint> points, List<PassiveEffect> effects) {
    public ArkPassive {
      points = points == null ? List.of() : List.copyOf(points);
      effects = effects == null ? List.of() : List.copyOf(effects);
    }
  }

  public record PassivePoint(String name, Integer value, String description) {}

  public record PassiveEffect(String name, String description, String icon) {}

  public record ArkGrid(List<GridSlot> slots, List<GridEffect> effects) {
    public ArkGrid {
      slots = slots == null ? List.of() : List.copyOf(slots);
      effects = effects == null ? List.of() : List.copyOf(effects);
    }
  }

  public record GridSlot(
      Integer index, String icon, String name, Integer point, String grade, List<GridGem> gems) {
    public GridSlot {
      gems = gems == null ? List.of() : List.copyOf(gems);
    }
  }

  public record GridEffect(String name, Integer level) {}

  public record GridGem(Integer index, String icon, Boolean active, String grade) {}
}
