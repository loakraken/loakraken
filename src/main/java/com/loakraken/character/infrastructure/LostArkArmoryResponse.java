package com.loakraken.character.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterObservationCoverage;
import com.loakraken.character.domain.ObservationState;
import java.util.List;
import java.util.Map;
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
    @JsonProperty("ArkGrid") ArkGrid arkGrid,
    @JsonProperty("ArmorySkills") List<Skill> skills,
    @JsonProperty("ArmoryAvatars") List<Avatar> avatars,
    @JsonProperty("Collectibles") List<Collectible> collectibles) {
  CharacterObservationCoverage coverage(List<LostArkSiblingResponse> siblings) {
    return new CharacterObservationCoverage(
        ObservationState.collection(equipment),
        section(
            engravings,
            Map.of(
                "slots",
                    ObservationState.collection(engravings == null ? null : engravings.slots()),
                "effects",
                    ObservationState.collection(engravings == null ? null : engravings.effects()),
                "arkPassiveEffects",
                    ObservationState.collection(
                        engravings == null ? null : engravings.arkPassiveEffects()))),
        section(
            gems, Map.of("items", ObservationState.collection(gems == null ? null : gems.items()))),
        section(
            cards,
            Map.of(
                "items", ObservationState.collection(cards == null ? null : cards.items()),
                "effects", ObservationState.collection(cards == null ? null : cards.effects()))),
        section(
            arkPassive,
            Map.of(
                "points",
                    ObservationState.collection(arkPassive == null ? null : arkPassive.points()),
                "effects",
                    ObservationState.collection(arkPassive == null ? null : arkPassive.effects()))),
        section(
            arkGrid,
            Map.of(
                "slots", ObservationState.collection(arkGrid == null ? null : arkGrid.slots()),
                "effects",
                    ObservationState.collection(arkGrid == null ? null : arkGrid.effects()))),
        siblings == null ? null : ObservationState.collection(siblings),
        ObservationState.collection(skills),
        ObservationState.collection(avatars),
        ObservationState.collection(collectibles));
  }

  private CharacterObservationCoverage.Section section(
      Object value, Map<String, ObservationState> collections) {
    return new CharacterObservationCoverage.Section(
        value == null ? ObservationState.NOT_PROVIDED : ObservationState.PROVIDED, collections);
  }

  CharacterLoadout toLoadout() {
    return new CharacterLoadout(
        map(equipment, Equipment::toDomain),
        engravings == null ? null : engravings.toDomain(),
        gems == null ? null : gems.toDomain(),
        cards == null ? null : cards.toDomain(),
        arkPassive == null ? null : arkPassive.toDomain(),
        arkGrid == null ? null : arkGrid.toDomain(),
        map(skills, Skill::toDomain),
        map(avatars, Avatar::toDomain),
        map(collectibles, Collectible::toDomain));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Equipment(
      @JsonProperty("Type") String type,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Grade") String grade,
      @JsonProperty("Tooltip") String tooltip) {
    CharacterLoadout.Equipment toDomain() {
      return new CharacterLoadout.Equipment(
          type, LostArkText.plain(name), icon, grade, LostArkEquipmentTooltip.parse(name, tooltip));
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
      return new CharacterLoadout.Engraving(slot, LostArkText.plain(name), icon);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record EngravingEffect(
      @JsonProperty("Icon") String icon,
      @JsonProperty("Name") String name,
      @JsonProperty("Description") String description) {
    CharacterLoadout.EngravingEffect toDomain() {
      return new CharacterLoadout.EngravingEffect(
          icon, LostArkText.plain(name), LostArkText.plain(description));
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
          abilityStoneLevel, grade, level, LostArkText.plain(name), LostArkText.plain(description));
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
      return new CharacterLoadout.Gem(slot, LostArkText.plain(name), icon, level, grade);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GemEffects(
      @JsonProperty("Description") String description,
      @JsonProperty("Skills") List<GemSkill> skills) {
    CharacterLoadout.GemEffects toDomain() {
      return new CharacterLoadout.GemEffects(
          LostArkText.plain(description), map(skills, GemSkill::toDomain));
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
          gemSlot,
          LostArkText.plain(name),
          LostArkText.plain(description),
          LostArkText.plain(option),
          icon);
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
      return new CharacterLoadout.Card(
          slot, LostArkText.plain(name), icon, awakeCount, awakeTotal, grade);
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
      return new CharacterLoadout.Effect(LostArkText.plain(name), LostArkText.plain(description));
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
          LostArkText.plain(title),
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
      return new CharacterLoadout.PassivePoint(
          LostArkText.plain(name), value, LostArkText.plain(description));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PassiveEffect(
      @JsonProperty("Name") String name,
      @JsonProperty("Description") String description,
      @JsonProperty("Icon") String icon,
      @JsonProperty("ToolTip") String tooltip) {
    CharacterLoadout.PassiveEffect toDomain() {
      return new CharacterLoadout.PassiveEffect(
          LostArkText.plain(name),
          LostArkText.plain(description),
          icon,
          LostArkSkillTooltip.passiveEffect(tooltip));
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
      @JsonProperty("Tooltip") String tooltip,
      @JsonProperty("Gems") List<GridGem> gems) {
    CharacterLoadout.GridSlot toDomain() {
      return new CharacterLoadout.GridSlot(
          index,
          icon,
          LostArkText.plain(name),
          point,
          grade,
          map(gems, GridGem::toDomain),
          LostArkArkGridTooltip.core(tooltip));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GridEffect(
      @JsonProperty("Name") String name,
      @JsonProperty("Level") Integer level,
      @JsonProperty("Tooltip") String tooltip) {
    CharacterLoadout.GridEffect toDomain() {
      return LostArkArkGridTooltip.effect(LostArkText.plain(name), level, tooltip);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record GridGem(
      @JsonProperty("Index") Integer index,
      @JsonProperty("Icon") String icon,
      @JsonProperty("IsActive") Boolean active,
      @JsonProperty("Grade") String grade,
      @JsonProperty("Tooltip") String tooltip) {
    CharacterLoadout.GridGem toDomain() {
      return new CharacterLoadout.GridGem(
          index, icon, active, grade, LostArkArkGridTooltip.gem(tooltip));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Skill(
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Level") Integer level,
      @JsonProperty("Type") String type,
      @JsonProperty("Tripods") List<Tripod> tripods,
      @JsonProperty("Rune") Rune rune,
      @JsonProperty("Tooltip") String tooltip) {
    CharacterLoadout.Skill toDomain() {
      var parsed = LostArkSkillTooltip.parse(tooltip);
      return new CharacterLoadout.Skill(
          LostArkText.plain(name),
          icon,
          level,
          LostArkText.plain(type),
          parsed.partBreakLevel(),
          parsed.counter(),
          tripods == null
              ? List.of()
              : tripods.stream()
                  .filter(tripod -> tripod != null && Boolean.TRUE.equals(tripod.selected()))
                  .map(tripod -> tripod.toDomain())
                  .toList(),
          rune == null ? null : rune.toDomain(),
          parsed.gemEffects(),
          parsed.attackType());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Avatar(
      @JsonProperty("Type") String type,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Grade") String grade,
      @JsonProperty("IsSet") Boolean isSet,
      @JsonProperty("IsInner") Boolean isInner,
      @JsonProperty("Tooltip") String tooltip) {
    CharacterLoadout.Avatar toDomain() {
      var detail = LostArkEquipmentTooltip.parse(name, tooltip);
      return new CharacterLoadout.Avatar(
          LostArkText.plain(type),
          LostArkText.plain(name),
          icon,
          grade,
          isSet,
          isInner,
          detail == null
              ? List.of()
              : detail.effects().stream().flatMap(group -> group.lines().stream()).toList());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Collectible(
      @JsonProperty("Type") String type,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Point") Integer point,
      @JsonProperty("MaxPoint") Integer maxPoint,
      @JsonProperty("CollectiblePoints") List<CollectiblePoint> items) {
    CharacterLoadout.Collectible toDomain() {
      return new CharacterLoadout.Collectible(
          LostArkText.plain(type), icon, point, maxPoint, map(items, CollectiblePoint::toDomain));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record CollectiblePoint(
      @JsonProperty("PointName") String name,
      @JsonProperty("Point") Integer point,
      @JsonProperty("MaxPoint") Integer maxPoint) {
    CharacterLoadout.CollectibleItem toDomain() {
      return new CharacterLoadout.CollectibleItem(LostArkText.plain(name), point, maxPoint);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Tripod(
      @JsonProperty("Tier") Integer tier,
      @JsonProperty("Slot") Integer slot,
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("IsSelected") Boolean selected,
      @JsonProperty("Tooltip") String tooltip) {
    CharacterLoadout.Tripod toDomain() {
      return new CharacterLoadout.Tripod(
          tier,
          slot,
          LostArkText.plain(name),
          icon,
          tooltip == null ? null : LostArkText.plain(tooltip));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Rune(
      @JsonProperty("Name") String name,
      @JsonProperty("Icon") String icon,
      @JsonProperty("Grade") String grade) {
    CharacterLoadout.Rune toDomain() {
      return new CharacterLoadout.Rune(LostArkText.plain(name), icon, grade);
    }
  }

  private static <T, R> List<R> map(List<T> source, Function<T, R> mapper) {
    return source == null
        ? List.of()
        : source.stream().filter(Objects::nonNull).map(mapper).toList();
  }
}
