package com.loakraken.character.presentation;

import com.loakraken.character.domain.CharacterLoadout;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공식 API의 구조화된 전투정보. null 섹션은 제공된 데이터가 없음을 뜻하며 Tooltip은 포함하지 않습니다.")
public record CharacterLoadoutResponse(
    List<EquipmentResponse> equipment,
    EngravingsResponse engravings,
    GemsResponse gems,
    CardsResponse cards,
    ArkPassiveResponse arkPassive,
    ArkGridResponse arkGrid) {
  static CharacterLoadoutResponse from(CharacterLoadout value) {
    return new CharacterLoadoutResponse(
        value.equipment().stream().map(EquipmentResponse::from).toList(),
        EngravingsResponse.from(value.engravings()),
        GemsResponse.from(value.gems()),
        CardsResponse.from(value.cards()),
        ArkPassiveResponse.from(value.arkPassive()),
        ArkGridResponse.from(value.arkGrid()));
  }

  public record EquipmentResponse(String type, String name, String icon, String grade) {
    static EquipmentResponse from(CharacterLoadout.Equipment value) {
      if (value == null) return null;
      return new EquipmentResponse(value.type(), value.name(), value.icon(), value.grade());
    }
  }

  public record EngravingsResponse(
      List<EngravingResponse> slots,
      List<EngravingEffectResponse> effects,
      List<PassiveEngravingResponse> arkPassiveEffects) {
    static EngravingsResponse from(CharacterLoadout.Engravings value) {
      if (value == null) return null;
      return new EngravingsResponse(
          value.slots().stream().map(EngravingResponse::from).toList(),
          value.effects().stream().map(EngravingEffectResponse::from).toList(),
          value.arkPassiveEffects().stream().map(PassiveEngravingResponse::from).toList());
    }
  }

  public record EngravingResponse(Integer slot, String name, String icon) {
    static EngravingResponse from(CharacterLoadout.Engraving value) {
      if (value == null) return null;
      return new EngravingResponse(value.slot(), value.name(), value.icon());
    }
  }

  public record EngravingEffectResponse(String icon, String name, String description) {
    static EngravingEffectResponse from(CharacterLoadout.EngravingEffect value) {
      if (value == null) return null;
      return new EngravingEffectResponse(value.icon(), value.name(), value.description());
    }
  }

  public record PassiveEngravingResponse(
      Integer abilityStoneLevel, String grade, Integer level, String name, String description) {
    static PassiveEngravingResponse from(CharacterLoadout.PassiveEngraving value) {
      if (value == null) return null;
      return new PassiveEngravingResponse(
          value.abilityStoneLevel(),
          value.grade(),
          value.level(),
          value.name(),
          value.description());
    }
  }

  public record GemsResponse(List<GemResponse> items, GemEffectsResponse effects) {
    static GemsResponse from(CharacterLoadout.Gems value) {
      if (value == null) return null;
      return new GemsResponse(
          value.items().stream().map(GemResponse::from).toList(),
          GemEffectsResponse.from(value.effects()));
    }
  }

  public record GemResponse(Integer slot, String name, String icon, Integer level, String grade) {
    static GemResponse from(CharacterLoadout.Gem value) {
      if (value == null) return null;
      return new GemResponse(
          value.slot(), value.name(), value.icon(), value.level(), value.grade());
    }
  }

  public record GemEffectsResponse(String description, List<GemSkillResponse> skills) {
    static GemEffectsResponse from(CharacterLoadout.GemEffects value) {
      if (value == null) return null;
      return new GemEffectsResponse(
          value.description(), value.skills().stream().map(GemSkillResponse::from).toList());
    }
  }

  public record GemSkillResponse(
      Integer gemSlot, String name, List<String> description, String option, String icon) {
    static GemSkillResponse from(CharacterLoadout.GemSkill value) {
      if (value == null) return null;
      return new GemSkillResponse(
          value.gemSlot(), value.name(), value.description(), value.option(), value.icon());
    }
  }

  public record CardsResponse(List<CardResponse> items, List<CardSetResponse> effects) {
    static CardsResponse from(CharacterLoadout.Cards value) {
      if (value == null) return null;
      return new CardsResponse(
          value.items().stream().map(CardResponse::from).toList(),
          value.effects().stream().map(CardSetResponse::from).toList());
    }
  }

  public record CardResponse(
      Integer slot,
      String name,
      String icon,
      Integer awakeCount,
      Integer awakeTotal,
      String grade) {
    static CardResponse from(CharacterLoadout.Card value) {
      if (value == null) return null;
      return new CardResponse(
          value.slot(),
          value.name(),
          value.icon(),
          value.awakeCount(),
          value.awakeTotal(),
          value.grade());
    }
  }

  public record CardSetResponse(
      Integer index, List<Integer> cardSlots, List<EffectResponse> items) {
    static CardSetResponse from(CharacterLoadout.CardSet value) {
      if (value == null) return null;
      return new CardSetResponse(
          value.index(),
          value.cardSlots(),
          value.items().stream().map(EffectResponse::from).toList());
    }
  }

  public record EffectResponse(String name, String description) {
    static EffectResponse from(CharacterLoadout.Effect value) {
      if (value == null) return null;
      return new EffectResponse(value.name(), value.description());
    }
  }

  public record ArkPassiveResponse(
      String title,
      Boolean enabled,
      List<PassivePointResponse> points,
      List<PassiveEffectResponse> effects) {
    static ArkPassiveResponse from(CharacterLoadout.ArkPassive value) {
      if (value == null) return null;
      return new ArkPassiveResponse(
          value.title(),
          value.enabled(),
          value.points().stream().map(PassivePointResponse::from).toList(),
          value.effects().stream().map(PassiveEffectResponse::from).toList());
    }
  }

  public record PassivePointResponse(String name, Integer value, String description) {
    static PassivePointResponse from(CharacterLoadout.PassivePoint value) {
      if (value == null) return null;
      return new PassivePointResponse(value.name(), value.value(), value.description());
    }
  }

  public record PassiveEffectResponse(String name, String description, String icon) {
    static PassiveEffectResponse from(CharacterLoadout.PassiveEffect value) {
      if (value == null) return null;
      return new PassiveEffectResponse(value.name(), value.description(), value.icon());
    }
  }

  public record ArkGridResponse(List<GridSlotResponse> slots, List<GridEffectResponse> effects) {
    static ArkGridResponse from(CharacterLoadout.ArkGrid value) {
      if (value == null) return null;
      return new ArkGridResponse(
          value.slots().stream().map(GridSlotResponse::from).toList(),
          value.effects().stream().map(GridEffectResponse::from).toList());
    }
  }

  public record GridSlotResponse(
      Integer index,
      String icon,
      String name,
      Integer point,
      String grade,
      List<GridGemResponse> gems) {
    static GridSlotResponse from(CharacterLoadout.GridSlot value) {
      if (value == null) return null;
      return new GridSlotResponse(
          value.index(),
          value.icon(),
          value.name(),
          value.point(),
          value.grade(),
          value.gems().stream().map(GridGemResponse::from).toList());
    }
  }

  public record GridEffectResponse(String name, Integer level) {
    static GridEffectResponse from(CharacterLoadout.GridEffect value) {
      if (value == null) return null;
      return new GridEffectResponse(value.name(), value.level());
    }
  }

  public record GridGemResponse(Integer index, String icon, Boolean active, String grade) {
    static GridGemResponse from(CharacterLoadout.GridGem value) {
      if (value == null) return null;
      return new GridGemResponse(value.index(), value.icon(), value.active(), value.grade());
    }
  }
}
