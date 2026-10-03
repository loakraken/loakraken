package com.loakraken.character.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.character.domain.CharacterLoadout;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkArmoryResponse(
    @JsonProperty("ArmoryProfile") LostArkProfileResponse profile,
    @JsonProperty("ArmoryEquipment") List<Equipment> equipment,
    @JsonProperty("ArmoryEngraving") Engravings engravings,
    @JsonProperty("ArmoryGem") Gems gems,
    @JsonProperty("ArmoryCard") Cards cards,
    @JsonProperty("ArkPassive") ArkPassive arkPassive,
    @JsonProperty("ArkGrid") ArkGrid arkGrid) {
  CharacterLoadout toLoadout() {
    return new CharacterLoadout(
        map(equipment, Equipment::toDomain),
        engravings == null ? null : engravings.toDomain(),
        gems == null ? null : gems.toDomain(),
        cards == null ? null : cards.toDomain(),
        arkPassive == null ? null : arkPassive.toDomain(),
        arkGrid == null ? null : arkGrid.toDomain());
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Equipment(
      @JsonProperty("Type") String type,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Grade") String grade) {
    CharacterLoadout.Equipment toDomain() {
      return new CharacterLoadout.Equipment(type, name, icon, grade);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Engravings(
      @JsonProperty("Engravings") List<Engraving> slots,
      @JsonProperty("Effects") List<EngravingEffect> effects,
      @JsonProperty("ArkPassiveEffects") List<PassiveEngraving> arkPassiveEffects) {
    CharacterLoadout.Engravings toDomain() {
      return new CharacterLoadout.Engravings(
          map(slots, Engraving::toDomain),
          map(effects, EngravingEffect::toDomain),
          map(arkPassiveEffects, PassiveEngraving::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Engraving(
      @JsonProperty("Slot") Integer slot,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon) {
    CharacterLoadout.Engraving toDomain() {
      return new CharacterLoadout.Engraving(slot, name, icon);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record EngravingEffect(
      @JsonProperty("Icon") String icon,
      @JsonProperty("Name") String name,
      @JsonProperty("Description") String description) {
    CharacterLoadout.EngravingEffect toDomain() {
      return new CharacterLoadout.EngravingEffect(icon, name, description);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PassiveEngraving(
      @JsonProperty("AbilityStoneLevel") Integer abilityStoneLevel,
      @JsonProperty("Grade") String grade,
      @JsonProperty("Level") Integer level,
      @JsonProperty("Name") String name,
      @JsonProperty("Description") String description) {
    CharacterLoadout.PassiveEngraving toDomain() {
      return new CharacterLoadout.PassiveEngraving(
          abilityStoneLevel, grade, level, name, description);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Gems(@JsonProperty("Gems") List<Gem> items, @JsonProperty("Effects") GemEffects effects) {
    CharacterLoadout.Gems toDomain() {
      return new CharacterLoadout.Gems(
          map(items, Gem::toDomain), effects == null ? null : effects.toDomain());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Gem(
      @JsonProperty("Slot") Integer slot,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Level") Integer level,
      @JsonProperty("Grade") String grade) {
    CharacterLoadout.Gem toDomain() {
      return new CharacterLoadout.Gem(slot, name, icon, level, grade);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GemEffects(
      @JsonProperty("Description") String description,
      @JsonProperty("Skills") List<GemSkill> skills) {
    CharacterLoadout.GemEffects toDomain() {
      return new CharacterLoadout.GemEffects(description, map(skills, GemSkill::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GemSkill(
      @JsonProperty("GemSlot") Integer gemSlot,
      @JsonProperty("Name") String name,
      @JsonProperty("Description") List<String> description,
      @JsonProperty("Option") String option,
      @JsonProperty("Icon") String icon) {
    CharacterLoadout.GemSkill toDomain() {
      return new CharacterLoadout.GemSkill(
          gemSlot, name, map(description, Function.identity()), option, icon);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Cards(
      @JsonProperty("Cards") List<Card> items, @JsonProperty("Effects") List<CardSet> effects) {
    CharacterLoadout.Cards toDomain() {
      return new CharacterLoadout.Cards(
          map(items, Card::toDomain), map(effects, CardSet::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Card(
      @JsonProperty("Slot") Integer slot,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("AwakeCount") Integer awakeCount,
      @JsonProperty("AwakeTotal") Integer awakeTotal,
      @JsonProperty("Grade") String grade) {
    CharacterLoadout.Card toDomain() {
      return new CharacterLoadout.Card(slot, name, icon, awakeCount, awakeTotal, grade);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CardSet(
      @JsonProperty("Index") Integer index,
      @JsonProperty("CardSlots") List<Integer> cardSlots,
      @JsonProperty("Items") List<Effect> items) {
    CharacterLoadout.CardSet toDomain() {
      return new CharacterLoadout.CardSet(
          index, map(cardSlots, Function.identity()), map(items, Effect::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Effect(
      @JsonProperty("Name") String name, @JsonProperty("Description") String description) {
    CharacterLoadout.Effect toDomain() {
      return new CharacterLoadout.Effect(name, description);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ArkPassive(
      @JsonProperty("Title") String title,
      @JsonProperty("IsArkPassive") Boolean enabled,
      @JsonProperty("Points") List<PassivePoint> points,
      @JsonProperty("Effects") List<PassiveEffect> effects) {
    CharacterLoadout.ArkPassive toDomain() {
      return new CharacterLoadout.ArkPassive(
          title,
          enabled,
          map(points, PassivePoint::toDomain),
          map(effects, PassiveEffect::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PassivePoint(
      @JsonProperty("Name") String name,
      @JsonProperty("Value") Integer value,
      @JsonProperty("Description") String description) {
    CharacterLoadout.PassivePoint toDomain() {
      return new CharacterLoadout.PassivePoint(name, value, description);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PassiveEffect(
      @JsonProperty("Name") String name,
      @JsonProperty("Description") String description,
      @JsonProperty("Icon") String icon) {
    CharacterLoadout.PassiveEffect toDomain() {
      return new CharacterLoadout.PassiveEffect(name, description, icon);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ArkGrid(
      @JsonProperty("Slots") List<GridSlot> slots,
      @JsonProperty("Effects") List<GridEffect> effects) {
    CharacterLoadout.ArkGrid toDomain() {
      return new CharacterLoadout.ArkGrid(
          map(slots, GridSlot::toDomain), map(effects, GridEffect::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GridSlot(
      @JsonProperty("Index") Integer index,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Name") String name,
      @JsonProperty("Point") Integer point,
      @JsonProperty("Grade") String grade,
      @JsonProperty("Gems") List<GridGem> gems) {
    CharacterLoadout.GridSlot toDomain() {
      return new CharacterLoadout.GridSlot(
          index, icon, name, point, grade, map(gems, GridGem::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GridEffect(@JsonProperty("Name") String name, @JsonProperty("Level") Integer level) {
    CharacterLoadout.GridEffect toDomain() {
      return new CharacterLoadout.GridEffect(name, level);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GridGem(
      @JsonProperty("Index") Integer index,
      @JsonProperty("Icon") String icon,
      @JsonProperty("IsActive") Boolean active,
      @JsonProperty("Grade") String grade) {
    CharacterLoadout.GridGem toDomain() {
      return new CharacterLoadout.GridGem(index, icon, active, grade);
    }
  }

  private static <T, R> List<R> map(List<T> source, Function<T, R> mapper) {
    return source == null
        ? List.of()
        : source.stream().filter(Objects::nonNull).map(mapper).toList();
  }
}
