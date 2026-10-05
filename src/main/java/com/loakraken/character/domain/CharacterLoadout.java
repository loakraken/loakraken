package com.loakraken.character.domain;

import java.util.List;

public record CharacterLoadout(
    List<Equipment> equipment,
    Engravings engravings,
    Gems gems,
    Cards cards,
    ArkPassive arkPassive,
    ArkGrid arkGrid,
    List<Skill> skills,
    List<Avatar> avatars,
    List<Collectible> collectibles) {
  public CharacterLoadout {
    equipment = equipment == null ? List.of() : List.copyOf(equipment);
    skills = skills == null ? List.of() : List.copyOf(skills);
    avatars = avatars == null ? List.of() : List.copyOf(avatars);
    collectibles = collectibles == null ? List.of() : List.copyOf(collectibles);
  }

  public CharacterLoadout(
      List<Equipment> equipment,
      Engravings engravings,
      Gems gems,
      Cards cards,
      ArkPassive arkPassive,
      ArkGrid arkGrid,
      List<Skill> skills) {
    this(equipment, engravings, gems, cards, arkPassive, arkGrid, skills, List.of(), List.of());
  }

  public record Avatar(
      String type,
      String name,
      String icon,
      String grade,
      Boolean isSet,
      Boolean isInner,
      List<String> effects) {
    public Avatar {
      effects = effects == null ? List.of() : List.copyOf(effects);
    }
  }

  public record Collectible(
      String type, String icon, Integer point, Integer maxPoint, List<CollectibleItem> items) {
    public Collectible {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  public record CollectibleItem(String name, Integer point, Integer maxPoint) {}

  public CharacterLoadout(
      List<Equipment> equipment,
      Engravings engravings,
      Gems gems,
      Cards cards,
      ArkPassive arkPassive,
      ArkGrid arkGrid) {
    this(equipment, engravings, gems, cards, arkPassive, arkGrid, List.of());
  }

  public record Skill(
      String name,
      String icon,
      Integer level,
      String type,
      Integer partBreakLevel,
      Boolean counter,
      List<Tripod> tripods,
      Rune rune,
      List<SkillGemEffect> gemEffects) {
    public Skill {
      tripods = tripods == null ? List.of() : List.copyOf(tripods);
      gemEffects = gemEffects == null ? List.of() : List.copyOf(gemEffects);
    }
  }

  public record Tripod(Integer tier, Integer slot, String name, String icon) {}

  public record Rune(String name, String icon, String grade) {}

  public enum GemEffectKind {
    DAMAGE,
    COOLDOWN
  }

  public record SkillGemEffect(String text, GemEffectKind kind) {}

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
      List<PolishEffect> polishEffects,
      List<BraceletEffect> braceletEffects) {
    public static final String PARSER_VERSION = "equipment-tooltip-v3";

    public EquipmentDetail {
      effects = effects == null ? List.of() : List.copyOf(effects);
      stoneEngravings = stoneEngravings == null ? List.of() : List.copyOf(stoneEngravings);
      polishEffects = polishEffects == null ? List.of() : List.copyOf(polishEffects);
      braceletEffects = braceletEffects == null ? List.of() : List.copyOf(braceletEffects);
    }
  }

  public enum EffectGrade {
    HIGH,
    MID,
    LOW
  }

  public record PolishEffect(String name, String value, EffectGrade grade) {}

  public record BraceletEffect(String text, EffectGrade grade) {}

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
