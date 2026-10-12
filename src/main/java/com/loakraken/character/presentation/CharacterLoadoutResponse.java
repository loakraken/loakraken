package com.loakraken.character.presentation;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.SkillCooldown;
import com.loakraken.common.api.NullableResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
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
      @Schema(description = "해석 규칙 버전", example = "equipment-tooltip-v5") String parserVersion,
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
          List<BraceletEffectResponse> braceletEffects,
      @Schema(description = "공식 Tooltip 등급·부위 줄(예: 고대 머리 방어구). 이전 캐시면 null") @NullableResponse
          String titleLine,
      @Schema(description = "귀속·거래 줄 원문 순서(예: 캐릭터 귀속됨, 거래 제한 아이템 레벨 1680, 거래 2회 가능, 거래가능)")
          List<String> tradeLines,
      @Schema(description = "현재 단계 재련 경험치 막대. 없는 장비·이전 캐시면 null") @NullableResponse
          RefineExpResponse refineExp,
      @Schema(
              description =
                  "상급 재련 단계. 공식 Tooltip \"[상급 재련] N단계\"(1단계 이상일 때만 나옴), 재련 단계가 있는 4티어 장비에 문구가 없으면 0, 그 밖·이전 캐시면 null")
          @NullableResponse
          Integer advancedRefineLevel) {
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
          value.braceletEffects().stream().map(BraceletEffectResponse::from).toList(),
          value.titleLine(),
          value.tradeLines(),
          value.refineExp() == null
              ? null
              : new RefineExpResponse(value.refineExp().current(), value.refineExp().max()),
          value.advancedRefineLevel());
    }
  }

  public record RefineExpResponse(
      @Schema(description = "현재 경험치. 공식 값이 없으면 null") @NullableResponse Long current,
      @Schema(description = "최대 경험치") long max) {}

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
      @NullableResponse String grade,
      @Schema(description = "공식 Tooltip 등급 줄(예: '전설 보석')") @NullableResponse String titleLine,
      @Schema(description = "공식 Tooltip 아이템 레벨") @NullableResponse Integer itemLevel,
      @Schema(description = "공식 Tooltip 티어") @NullableResponse Integer tier,
      @Schema(description = "공식 Tooltip 귀속·거래 줄(예: '캐릭터 귀속됨', '거래 불가')") List<String> tradeLines) {
    static GemResponse from(CharacterLoadout.Gem value) {
      if (value == null) return null;
      return new GemResponse(
          value.slot(),
          value.name(),
          value.icon(),
          value.level(),
          value.grade(),
          value.titleLine(),
          value.itemLevel(),
          value.tier(),
          value.tradeLines());
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
      @Schema(description = "스킬 Tooltip의 보석 효과 줄") List<SkillGemEffectResponse> gemEffects,
      @Schema(
              description =
                  "Tooltip의 \"공격 타입\". BACK_ATTACK(백 어택)·HEAD_ATTACK(헤드 어택)·NONE(없음). Tooltip을 해석할 수 없거나 갱신 전 캐시(최대 5분)면 null. 스킬별 치명타 적중률은 crit.observedRate + BACK_ATTACK이면 10%p다",
              allowableValues = {"BACK_ATTACK", "HEAD_ATTACK", "NONE"})
          @NullableResponse
          String attackType,
      @Schema(
              description =
                  "보석 반영 쿨타임(공식 재사용 대기시간과 장착 보석의 재사용 대기시간 감소만 반영). 제목 줄에 재사용 대기시간이 없으면(갱신 전 캐시 등) null")
          @NullableResponse
          SkillCooldownResponse cooldown) {
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
                          tripod.tier(),
                          tripod.slot(),
                          tripod.name(),
                          tripod.icon(),
                          tripod.effect()))
              .toList(),
          value.rune() == null
              ? null
              : new RuneResponse(
                  value.rune().name(),
                  value.rune().icon(),
                  value.rune().grade(),
                  value.rune().effect()),
          value.gemEffects().stream()
              .map(effect -> new SkillGemEffectResponse(effect.text(), effect.kind()))
              .toList(),
          value.attackType() == null ? null : value.attackType().name(),
          SkillCooldownResponse.from(SkillCooldown.of(value)));
    }
  }

  public record SkillCooldownResponse(
      @Schema(description = "공식 Tooltip 재사용 대기시간(초, 선택한 트라이포드 반영)", example = "8.00")
          BigDecimal cooldownSec,
      @Schema(description = "이 스킬 보석 효과의 재사용 대기시간 감소 % 합(없으면 0)", example = "16.00")
          BigDecimal gemCooldownReductionPct,
      @Schema(
              description = "cooldownSec × (1 − gemCooldownReductionPct/100), 소수 둘째 자리",
              example = "6.72")
          BigDecimal cooldownWithGemsSec,
      @Schema(description = "계산 근거 문장") String basis) {
    static SkillCooldownResponse from(SkillCooldown value) {
      if (value == null) return null;
      return new SkillCooldownResponse(
          value.cooldownSec(),
          value.gemCooldownReductionPct(),
          value.cooldownWithGemsSec(),
          SkillCooldown.BASIS);
    }
  }

  public record TripodResponse(
      @Schema(description = "0부터 시작하는 공식 단계") @NullableResponse Integer tier,
      @NullableResponse Integer slot,
      @NullableResponse String name,
      @NullableResponse String icon,
      @Schema(description = "트라이포드 효과 공식 원문. 이전 캐시면 null") @NullableResponse String effect) {}

  public record RuneResponse(
      @NullableResponse String name,
      @NullableResponse String icon,
      @NullableResponse String grade,
      @Schema(description = "공식 Tooltip '스킬 룬 효과' 원문. 이전 캐시면 null") @NullableResponse
          String effect) {}

  public record SkillGemEffectResponse(
      @Schema(example = "[블레이드] 윈드 컷 피해 28.00% 증가") String text,
      @Schema(description = "피해 N% 증가=DAMAGE, 재사용 대기시간 N% 감소=COOLDOWN, 그 외 null") @NullableResponse
          CharacterLoadout.GemEffectKind kind) {}

  public record ArkGridResponse(
      List<GridSlotResponse> slots,
      List<GridEffectResponse> effects,
      @Schema(description = "코어 공급 의지력 합과 활성 젬 필요 의지력 합. 코어 상세가 없으면 null") @NullableResponse
          WillpowerResponse willpower) {
    static ArkGridResponse from(CharacterLoadout.ArkGrid value) {
      if (value == null) return null;
      var slots = value.slots().stream().map(GridSlotResponse::from).toList();
      Integer supply = null;
      Integer used = null;
      for (var slot : slots) {
        if (slot == null || slot.supplyWillpower() == null) continue;
        supply = (supply == null ? 0 : supply) + slot.supplyWillpower();
        used =
            (used == null ? 0 : used) + (slot.usedWillpower() == null ? 0 : slot.usedWillpower());
      }
      return new ArkGridResponse(
          slots,
          value.effects().stream().map(GridEffectResponse::from).toList(),
          supply == null ? null : new WillpowerResponse(used, supply));
    }
  }

  public record WillpowerResponse(int used, int supply) {}

  public record GridSlotResponse(
      @NullableResponse Integer index,
      @NullableResponse String icon,
      @NullableResponse String name,
      @NullableResponse Integer point,
      @NullableResponse String grade,
      List<GridGemResponse> gems,
      @Schema(description = "코어 타입(예: 질서 - 해). 코어 Tooltip을 해석할 수 없으면 null") @NullableResponse
          String coreType,
      @Schema(description = "코어 공급 의지력") @NullableResponse Integer supplyWillpower,
      @Schema(description = "활성 젬의 필요 의지력 합") @NullableResponse Integer usedWillpower,
      @Schema(description = "포인트 구간 옵션(오름차순). active는 현재 포인트로 달성했는지")
          List<CoreOptionResponse> options,
      @Schema(description = "달성한 구간 수(예: 4/6의 4)") int activeOptionCount,
      @Schema(description = "전체 구간 수") int optionCount,
      @Schema(description = "코어 옵션 발동 조건") List<String> conditions) {
    static GridSlotResponse from(CharacterLoadout.GridSlot value) {
      if (value == null) return null;
      var core = value.core();
      int point = value.point() == null ? 0 : value.point();
      var options =
          core == null
              ? List.<CoreOptionResponse>of()
              : core.options().stream()
                  .map(
                      option ->
                          new CoreOptionResponse(
                              option.threshold(), option.text(), option.threshold() <= point))
                  .toList();
      Integer used = null;
      if (core != null && core.supplyWillpower() != null) {
        used = 0;
        for (var gem : value.gems())
          if (gem != null
              && Boolean.TRUE.equals(gem.active())
              && gem.detail() != null
              && gem.detail().requiredWillpower() != null) used += gem.detail().requiredWillpower();
      }
      return new GridSlotResponse(
          value.index(),
          value.icon(),
          value.name(),
          value.point(),
          value.grade(),
          value.gems().stream().map(GridGemResponse::from).toList(),
          core == null ? null : core.coreType(),
          core == null ? null : core.supplyWillpower(),
          used,
          options,
          (int) options.stream().filter(CoreOptionResponse::active).count(),
          options.size(),
          core == null ? List.of() : core.conditions());
    }
  }

  public record CoreOptionResponse(int threshold, String text, boolean active) {}

  public record GridEffectResponse(
      @NullableResponse String name,
      @NullableResponse Integer level,
      @Schema(description = "공식 Tooltip 문구(예: 공격력 +1.02%)") @NullableResponse String value,
      @Schema(description = "value의 % 수치") @NullableResponse java.math.BigDecimal percent) {
    static GridEffectResponse from(CharacterLoadout.GridEffect value) {
      if (value == null) return null;
      return new GridEffectResponse(value.name(), value.level(), value.value(), value.percent());
    }
  }

  public record GridGemResponse(
      @NullableResponse Integer index,
      @NullableResponse String icon,
      @NullableResponse Boolean active,
      @NullableResponse String grade,
      @Schema(description = "젬 이름(예: 질서의 젬 : 안정)") @NullableResponse String name,
      @NullableResponse String gemType,
      @Schema(description = "젬 기본 정보의 젬 포인트") @NullableResponse Integer gemPoint,
      @Schema(description = "코어에 더하는 질서·혼돈 포인트") @NullableResponse Integer corePoint,
      @NullableResponse Integer requiredWillpower,
      @NullableResponse Integer baseWillpower,
      @NullableResponse Integer willpowerEfficiency,
      List<GemOptionResponse> options) {
    static GridGemResponse from(CharacterLoadout.GridGem value) {
      if (value == null) return null;
      var detail = value.detail();
      return new GridGemResponse(
          value.index(),
          value.icon(),
          value.active(),
          value.grade(),
          detail == null ? null : detail.name(),
          detail == null ? null : detail.gemType(),
          detail == null ? null : detail.gemPoint(),
          detail == null ? null : detail.corePoint(),
          detail == null ? null : detail.requiredWillpower(),
          detail == null ? null : detail.baseWillpower(),
          detail == null ? null : detail.willpowerEfficiency(),
          detail == null
              ? List.of()
              : detail.options().stream()
                  .map(
                      option ->
                          new GemOptionResponse(
                              option.name(), option.level(), option.effect(), option.percent()))
                  .toList());
    }
  }

  public record GemOptionResponse(
      String name,
      @NullableResponse Integer level,
      @Schema(description = "효과 문구(예: 공격력 +0.03%)") @NullableResponse String effect,
      @NullableResponse java.math.BigDecimal percent) {}
}
