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
    @NullableResponse ArkGridResponse arkGrid,
    @Schema(description = "공식 전투 스킬 전체(기본 스킬 포함). 목록이 미제공이면 빈 배열이며 coverage.skills로 구분합니다.")
        List<SkillResponse> skills) {
  static CharacterLoadoutResponse from(CharacterLoadout value) {
    return new CharacterLoadoutResponse(
        value.equipment().stream().map(EquipmentResponse::from).toList(),
        EngravingsResponse.from(value.engravings()),
        GemsResponse.from(value.gems()),
        CardsResponse.from(value.cards()),
        ArkPassiveResponse.from(value.arkPassive()),
        ArkGridResponse.from(value.arkGrid()),
        value.skills().stream().map(SkillResponse::from).toList());
  }

  public record EquipmentResponse(
      @NullableResponse String type,
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse String grade,
      @Schema(description = "Tooltip에서 해석한 장비 정보. Tooltip이 없거나 해석할 수 없으면 null입니다.")
          @NullableResponse
          EquipmentDetailResponse detail) {
    static EquipmentResponse from(CharacterLoadout.Equipment value) {
      if (value == null) return null;
      return new EquipmentResponse(
          value.type(),
          value.name(),
          value.icon(),
          value.grade(),
          EquipmentDetailResponse.from(value.detail()));
    }
  }

  @Schema(
      description =
          "공식 Tooltip에서 확인된 값만 담습니다. 확인되지 않은 값은 null이며 추정하지 않습니다. 엘릭서·초월·상급 재련은 아직 해석하지 않습니다.")
  public record EquipmentDetailResponse(
      @Schema(description = "해석 규칙 버전", example = "equipment-tooltip-v3") String parserVersion,
      @Schema(description = "장비 이름의 +N 재련 단계") @NullableResponse Integer refineLevel,
      @Schema(description = "품질 0~100. 품질이 적용되지 않는 장비는 null") @NullableResponse Integer quality,
      @Schema(description = "장비 아이템 레벨. 장신구 등 표시되지 않는 장비는 null") @NullableResponse
          Integer itemLevel,
      @NullableResponse Integer tier,
      @Schema(description = "장비가 제공하는 아크패시브 포인트") @NullableResponse
          PointBonusResponse arkPassivePoint,
      @Schema(description = "효과 묶음. 제목과 줄 단위 일반 텍스트") List<EffectGroupResponse> effects,
      @Schema(description = "어빌리티 스톤의 무작위 각인") List<StoneEngravingResponse> stoneEngravings,
      @Schema(description = "장신구 연마 효과와 공식 Tooltip 색 기준 등급. 연마 효과가 없는 장비는 빈 목록")
          List<PolishEffectResponse> polishEffects,
      @Schema(description = "팔찌 효과와 공식 Tooltip 색 기준 등급. 팔찌가 아니면 빈 목록")
          List<BraceletEffectResponse> braceletEffects) {
    static EquipmentDetailResponse from(CharacterLoadout.EquipmentDetail value) {
      if (value == null) return null;
      return new EquipmentDetailResponse(
          CharacterLoadout.EquipmentDetail.PARSER_VERSION,
          value.refineLevel(),
          value.quality(),
          value.itemLevel(),
          value.tier(),
          PointBonusResponse.from(value.arkPassivePoint()),
          value.effects().stream().map(EffectGroupResponse::from).toList(),
          value.stoneEngravings().stream().map(StoneEngravingResponse::from).toList(),
          value.polishEffects().stream().map(PolishEffectResponse::from).toList(),
          value.braceletEffects().stream().map(BraceletEffectResponse::from).toList());
    }
  }

  public record PointBonusResponse(String name, Integer value) {
    static PointBonusResponse from(CharacterLoadout.PointBonus value) {
      if (value == null) return null;
      return new PointBonusResponse(value.name(), value.value());
    }
  }

  public record EffectGroupResponse(String title, List<String> lines) {
    static EffectGroupResponse from(CharacterLoadout.EffectGroup value) {
      return new EffectGroupResponse(value.title(), value.lines());
    }
  }

  @Schema(
      description =
          "등급은 공식 Tooltip 수치 색(FE9600=HIGH, CE43FC=MID, 00B5FF=LOW)으로 정하며 공식 장신구 연마 확률표의 3단계(0.7%/3.0%/6.3%)와 대응합니다. 알 수 없는 색은 null입니다.")
  public record PolishEffectResponse(
      @Schema(example = "추가 피해") String name,
      @Schema(example = "+2.60%") String value,
      @NullableResponse CharacterLoadout.EffectGrade grade) {
    static PolishEffectResponse from(CharacterLoadout.PolishEffect value) {
      return new PolishEffectResponse(value.name(), value.value(), value.grade());
    }
  }

  @Schema(
      description =
          "팔찌 효과 하나(두 줄 효과 포함)와 등급. 등급은 효과의 첫 색 수치(FE9600=HIGH, CE43FC=MID, 00B5FF=LOW)로 정하며 공식 팔찌 확률표의 특수 효과 3단계와 대응합니다. 전투 특성처럼 구간형 수치이거나 알 수 없는 색은 null입니다.")
  public record BraceletEffectResponse(
      @Schema(example = "치명타 피해 +8.40%") String text,
      @NullableResponse CharacterLoadout.EffectGrade grade) {
    static BraceletEffectResponse from(CharacterLoadout.BraceletEffect value) {
      return new BraceletEffectResponse(value.text(), value.grade());
    }
  }

  public record StoneEngravingResponse(String name, Integer level) {
    static StoneEngravingResponse from(CharacterLoadout.StoneEngraving value) {
      return new StoneEngravingResponse(value.name(), value.level());
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
      @NullableResponse String icon,
      @Schema(description = "공식 노드 Tooltip의 효과 문구(수치 포함). 제공되지 않으면 null") @NullableResponse
          String effect) {
    static PassiveEffectResponse from(CharacterLoadout.PassiveEffect value) {
      if (value == null) return null;
      return new PassiveEffectResponse(
          value.name(), value.description(), value.icon(), value.effect());
    }
  }

  public record SkillResponse(
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse Integer level,
      @Schema(description = "공식 스킬 유형 문자열(일반·콤보·차지·지점·홀딩 등)") @NullableResponse String type,
      @Schema(description = "Tooltip의 \"부위 파괴 : 레벨 N\". 없으면 null") @NullableResponse
          Integer partBreakLevel,
      @Schema(description = "Tooltip에 \"카운터 : 가능\"이 있으면 true. Tooltip을 해석할 수 없으면 null")
          @NullableResponse
          Boolean counter,
      @Schema(description = "선택된 트라이포드만. 공식 응답에 트라이포드 레벨은 없습니다.") List<TripodResponse> tripods,
      @NullableResponse RuneResponse rune,
      @Schema(description = "스킬 Tooltip의 보석 효과 줄") List<SkillGemEffectResponse> gemEffects) {
    static SkillResponse from(CharacterLoadout.Skill value) {
      return new SkillResponse(
          value.name(),
          value.icon(),
          value.level(),
          value.type(),
          value.partBreakLevel(),
          value.counter(),
          value.tripods().stream()
              .map(
                  tripod ->
                      new TripodResponse(
                          tripod.tier(), tripod.slot(), tripod.name(), tripod.icon()))
              .toList(),
          value.rune() == null
              ? null
              : new RuneResponse(value.rune().name(), value.rune().icon(), value.rune().grade()),
          value.gemEffects().stream()
              .map(effect -> new SkillGemEffectResponse(effect.text(), effect.kind()))
              .toList());
    }
  }

  public record TripodResponse(
      @Schema(description = "0부터 시작하는 공식 단계") @NullableResponse Integer tier,
      @NullableResponse Integer slot,
      @NullableResponse String name,
      @NullableResponse String icon) {}

  public record RuneResponse(
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse String grade) {}

  public record SkillGemEffectResponse(
      @Schema(example = "[블레이드] 윈드 컷 피해 28.00% 증가") String text,
      @Schema(description = "피해 N% 증가=DAMAGE, 재사용 대기시간 N% 감소=COOLDOWN, 그 외 null") @NullableResponse
          CharacterLoadout.GemEffectKind kind) {}

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
