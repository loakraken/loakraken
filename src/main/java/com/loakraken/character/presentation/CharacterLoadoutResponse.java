package com.loakraken.character.presentation;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "공식 API의 구조화된 전투정보. null 섹션은 제공된 데이터가 없음을 뜻하며 Tooltip은 포함하지 않습니다.")
public record CharacterLoadoutResponse(
    List<EquipmentResponse> equipment,
    @NullableResponse EngravingsResponse engravings,
    @NullableResponse GemsResponse gems,
    @NullableResponse CardsResponse cards,
    @NullableResponse ArkPassiveResponse arkPassive,
    @NullableResponse ArkGridResponse arkGrid) {
  static CharacterLoadoutResponse from(CharacterLoadout value) {
    return new CharacterLoadoutResponse(
        value.equipment().stream().map(EquipmentResponse::from).toList(),
        EngravingsResponse.from(value.engravings()),
        GemsResponse.from(value.gems()),
        CardsResponse.from(value.cards()),
        ArkPassiveResponse.from(value.arkPassive()),
        ArkGridResponse.from(value.arkGrid()));
  }

  public record EquipmentResponse(
      @NullableResponse String type,
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse String grade) {
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

  public record EngravingResponse(
      @NullableResponse Integer slot,
      @NullableResponse String name,
      @NullableResponse String icon) {
    static EngravingResponse from(CharacterLoadout.Engraving value) {
      if (value == null) return null;
      return new EngravingResponse(value.slot(), value.name(), value.icon());
    }
  }

  public record EngravingEffectResponse(
      @NullableResponse String icon,
      @NullableResponse String name,
      @NullableResponse String description) {
    static EngravingEffectResponse from(CharacterLoadout.EngravingEffect value) {
      if (value == null) return null;
      return new EngravingEffectResponse(value.icon(), value.name(), value.description());
    }
  }

  public record PassiveEngravingResponse(
      @NullableResponse Integer abilityStoneLevel,
      @NullableResponse String grade,
      @NullableResponse Integer level,
      @NullableResponse String name,
      @NullableResponse String description) {
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

  public record GemsResponse(
      List<GemResponse> items, @NullableResponse GemEffectsResponse effects) {
    static GemsResponse from(CharacterLoadout.Gems value) {
      if (value == null) return null;
      return new GemsResponse(
          value.items().stream().map(GemResponse::from).toList(),
          GemEffectsResponse.from(value.effects()));
    }
  }

  public record GemResponse(
      @NullableResponse Integer slot,
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse Integer level,
      @NullableResponse String grade) {
    static GemResponse from(CharacterLoadout.Gem value) {
      if (value == null) return null;
      return new GemResponse(
          value.slot(), value.name(), value.icon(), value.level(), value.grade());
    }
  }

  public record GemEffectsResponse(
      @NullableResponse String description, List<GemSkillResponse> skills) {
    static GemEffectsResponse from(CharacterLoadout.GemEffects value) {
      if (value == null) return null;
      return new GemEffectsResponse(
          value.description(), value.skills().stream().map(GemSkillResponse::from).toList());
    }
  }

  public record GemSkillResponse(
      @NullableResponse Integer gemSlot,
      @NullableResponse String name,
      List<String> description,
      @NullableResponse String option,
      @NullableResponse String icon) {
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
      @NullableResponse Integer slot,
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse Integer awakeCount,
      @NullableResponse Integer awakeTotal,
      @NullableResponse String grade) {
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
      @NullableResponse Integer index, List<Integer> cardSlots, List<EffectResponse> items) {
    static CardSetResponse from(CharacterLoadout.CardSet value) {
      if (value == null) return null;
      return new CardSetResponse(
          value.index(),
          value.cardSlots(),
          value.items().stream().map(EffectResponse::from).toList());
    }
  }

  public record EffectResponse(
      @NullableResponse String name, @NullableResponse String description) {
    static EffectResponse from(CharacterLoadout.Effect value) {
      if (value == null) return null;
      return new EffectResponse(value.name(), value.description());
    }
  }

  public record ArkPassiveResponse(
      @NullableResponse String title,
      @NullableResponse Boolean enabled,
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

  public record PassivePointResponse(
      @NullableResponse String name,
      @NullableResponse Integer value,
      @NullableResponse String description) {
    static PassivePointResponse from(CharacterLoadout.PassivePoint value) {
      if (value == null) return null;
      return new PassivePointResponse(value.name(), value.value(), value.description());
    }
  }

  public record PassiveEffectResponse(
      @NullableResponse String name,
      @NullableResponse String description,
      @NullableResponse String icon) {
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
      @NullableResponse Integer index,
      @NullableResponse String icon,
      @NullableResponse String name,
      @NullableResponse Integer point,
      @NullableResponse String grade,
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

  public record GridEffectResponse(@NullableResponse String name, @NullableResponse Integer level) {
    static GridEffectResponse from(CharacterLoadout.GridEffect value) {
      if (value == null) return null;
      return new GridEffectResponse(value.name(), value.level());
    }
  }

  public record GridGemResponse(
      @NullableResponse Integer index,
      @NullableResponse String icon,
      @NullableResponse Boolean active,
      @NullableResponse String grade) {
    static GridGemResponse from(CharacterLoadout.GridGem value) {
      if (value == null) return null;
      return new GridGemResponse(value.index(), value.icon(), value.active(), value.grade());
    }
  }
}
