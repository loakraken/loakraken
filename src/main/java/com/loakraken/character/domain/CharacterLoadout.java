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
      List<SkillGemEffect> gemEffects,
      AttackType attackType,
      String category,
      List<String> titleLines,
      List<String> infoLines,
      List<TripodOption> tripodOptions,
      Integer skillType) {
    /**
     * titleLines: 공식 Tooltip 제목 줄(스킬 종류·재사용 대기시간·분류, #299). infoLines: 현재 레벨 정보 줄(PvE·스킬 레벨·마나·부위
     * 파괴·무력화·공격 타입·슈퍼아머 등 원문 순서). 이전 캐시면 빈 목록. skillType: 공식 SkillType 코드 그대로(관측: 0 일반, 1 각성기,
     * 100·101 초각성 두 종류). 모르는 값도 그대로 두고, 이전 캐시면 null.
     */
    public Skill {
      tripods = tripods == null ? List.of() : List.copyOf(tripods);
      gemEffects = gemEffects == null ? List.of() : List.copyOf(gemEffects);
      titleLines = titleLines == null ? List.of() : List.copyOf(titleLines);
      infoLines = infoLines == null ? List.of() : List.copyOf(infoLines);
      tripodOptions = tripodOptions == null ? List.of() : List.copyOf(tripodOptions);
    }

    public Skill(
        String name,
        String icon,
        Integer level,
        String type,
        Integer partBreakLevel,
        Boolean counter,
        List<Tripod> tripods,
        Rune rune,
        List<SkillGemEffect> gemEffects,
        AttackType attackType,
        String category,
        List<String> titleLines,
        List<String> infoLines,
        List<TripodOption> tripodOptions) {
      this(
          name,
          icon,
          level,
          type,
          partBreakLevel,
          counter,
          tripods,
          rune,
          gemEffects,
          attackType,
          category,
          titleLines,
          infoLines,
          tripodOptions,
          null);
    }

    public Skill(
        String name,
        String icon,
        Integer level,
        String type,
        Integer partBreakLevel,
        Boolean counter,
        List<Tripod> tripods,
        Rune rune,
        List<SkillGemEffect> gemEffects,
        AttackType attackType,
        String category,
        List<String> titleLines,
        List<String> infoLines) {
      this(
          name,
          icon,
          level,
          type,
          partBreakLevel,
          counter,
          tripods,
          rune,
          gemEffects,
          attackType,
          category,
          titleLines,
          infoLines,
          List.of());
    }

    public Skill(
        String name,
        String icon,
        Integer level,
        String type,
        Integer partBreakLevel,
        Boolean counter,
        List<Tripod> tripods,
        Rune rune,
        List<SkillGemEffect> gemEffects,
        AttackType attackType,
        String category) {
      this(
          name,
          icon,
          level,
          type,
          partBreakLevel,
          counter,
          tripods,
          rune,
          gemEffects,
          attackType,
          category,
          List.of(),
          List.of());
    }

    public Skill(
        String name,
        String icon,
        Integer level,
        String type,
        Integer partBreakLevel,
        Boolean counter,
        List<Tripod> tripods,
        Rune rune,
        List<SkillGemEffect> gemEffects) {
      this(name, icon, level, type, partBreakLevel, counter, tripods, rune, gemEffects, null, null);
    }

    public Skill(
        String name,
        String icon,
        Integer level,
        String type,
        Integer partBreakLevel,
        Boolean counter,
        List<Tripod> tripods,
        Rune rune,
        List<SkillGemEffect> gemEffects,
        AttackType attackType) {
      this(
          name,
          icon,
          level,
          type,
          partBreakLevel,
          counter,
          tripods,
          rune,
          gemEffects,
          attackType,
          null);
    }
  }

  /** 스킬 Tooltip의 "공격 타입". 백 어택은 치명타 적중률 +10%p(비교 기준의 스킬별 치명 확률과 같은 규칙). */
  public enum AttackType {
    BACK_ATTACK,
    HEAD_ATTACK,
    NONE
  }

  /** 트라이포드 선택지 하나(#307): 공식 Tripods 전체(선택 여부 포함, 공식 순서). 효과 문장은 tripods[]의 선택된 것만 둔다. */
  public record TripodOption(
      Integer tier, Integer slot, String name, String icon, boolean selected) {}

  /** effect: 공식 트라이포드 Tooltip 효과 문구(태그 제거). 갱신 전 캐시나 해석할 수 없으면 null(#168). */
  public record Tripod(Integer tier, Integer slot, String name, String icon, String effect) {
    public Tripod(Integer tier, Integer slot, String name, String icon) {
      this(tier, slot, name, icon, null);
    }
  }

  /** effect: 공식 Tooltip "스킬 룬 효과" 줄, extraLines: 그 밖의 줄(설명·획득처). 캐시된 이전 값이면 null·빈 목록. */
  public record Rune(
      String name, String icon, String grade, String effect, List<String> extraLines) {
    public Rune {
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
    }

    public Rune(String name, String icon, String grade) {
      this(name, icon, grade, null, List.of());
    }
  }

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
      List<BraceletEffect> braceletEffects,
      String titleLine,
      List<String> tradeLines,
      RefineExp refineExp,
      List<String> extraLines,
      Integer advancedRefineLevel,
      List<String> extraLineSections) {
    public static final String PARSER_VERSION = "equipment-tooltip-v6";

    /** 나머지 줄의 앞 섹션 없이(v5 이전 값). */
    public EquipmentDetail(
        Integer refineLevel,
        Integer quality,
        Integer itemLevel,
        Integer tier,
        PointBonus arkPassivePoint,
        List<EffectGroup> effects,
        List<StoneEngraving> stoneEngravings,
        List<PolishEffect> polishEffects,
        List<BraceletEffect> braceletEffects,
        String titleLine,
        List<String> tradeLines,
        RefineExp refineExp,
        List<String> extraLines,
        Integer advancedRefineLevel) {
      this(
          refineLevel,
          quality,
          itemLevel,
          tier,
          arkPassivePoint,
          effects,
          stoneEngravings,
          polishEffects,
          braceletEffects,
          titleLine,
          tradeLines,
          refineExp,
          extraLines,
          advancedRefineLevel,
          List.of());
    }

    /** 상급 재련 단계 없이(v4 이전 값). */
    public EquipmentDetail(
        Integer refineLevel,
        Integer quality,
        Integer itemLevel,
        Integer tier,
        PointBonus arkPassivePoint,
        List<EffectGroup> effects,
        List<StoneEngraving> stoneEngravings,
        List<PolishEffect> polishEffects,
        List<BraceletEffect> braceletEffects,
        String titleLine,
        List<String> tradeLines,
        RefineExp refineExp,
        List<String> extraLines) {
      this(
          refineLevel,
          quality,
          itemLevel,
          tier,
          arkPassivePoint,
          effects,
          stoneEngravings,
          polishEffects,
          braceletEffects,
          titleLine,
          tradeLines,
          refineExp,
          extraLines,
          null);
    }

    public EquipmentDetail {
      tradeLines = tradeLines == null ? List.of() : List.copyOf(tradeLines);
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
      extraLineSections = extraLineSections == null ? List.of() : List.copyOf(extraLineSections);
      effects = effects == null ? List.of() : List.copyOf(effects);
      stoneEngravings = stoneEngravings == null ? List.of() : List.copyOf(stoneEngravings);
      polishEffects = polishEffects == null ? List.of() : List.copyOf(polishEffects);
      braceletEffects = braceletEffects == null ? List.of() : List.copyOf(braceletEffects);
    }

    /** 툴팁 줄(v4) 없이. */
    public EquipmentDetail(
        Integer refineLevel,
        Integer quality,
        Integer itemLevel,
        Integer tier,
        PointBonus arkPassivePoint,
        List<EffectGroup> effects,
        List<StoneEngraving> stoneEngravings,
        List<PolishEffect> polishEffects,
        List<BraceletEffect> braceletEffects) {
      this(
          refineLevel,
          quality,
          itemLevel,
          tier,
          arkPassivePoint,
          effects,
          stoneEngravings,
          polishEffects,
          braceletEffects,
          null,
          List.of(),
          null,
          List.of(),
          null);
    }
  }

  public enum EffectGrade {
    HIGH,
    MID,
    LOW
  }

  /** 공식 Tooltip "현재 단계 재련 경험치" 막대. current가 없으면 null. */
  public record RefineExp(Long current, long max) {}

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

  /** extraLines: 공식 Tooltip의 효과 외 줄(보석 레벨·조율·분해불가·획득처 등). */
  /** titleLine·itemLevel·tier·tradeLines는 공식 보석 Tooltip(#285, 툴팁 표시용). 없으면 null·빈 목록. */
  public record Gem(
      Integer slot,
      String name,
      String icon,
      Integer level,
      String grade,
      List<String> extraLines,
      String titleLine,
      Integer itemLevel,
      Integer tier,
      List<String> tradeLines) {
    public Gem {
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
      tradeLines = tradeLines == null ? List.of() : List.copyOf(tradeLines);
    }

    public Gem(
        Integer slot,
        String name,
        String icon,
        Integer level,
        String grade,
        List<String> extraLines) {
      this(slot, name, icon, level, grade, extraLines, null, null, null, List.of());
    }

    public Gem(Integer slot, String name, String icon, Integer level, String grade) {
      this(slot, name, icon, level, grade, List.of());
    }
  }

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

  /** extraLines: 공식 Tooltip의 설명·획득처 줄. */
  public record Card(
      Integer slot,
      String name,
      String icon,
      Integer awakeCount,
      Integer awakeTotal,
      String grade,
      List<String> extraLines) {
    public Card {
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
    }

    public Card(
        Integer slot,
        String name,
        String icon,
        Integer awakeCount,
        Integer awakeTotal,
        String grade) {
      this(slot, name, icon, awakeCount, awakeTotal, grade, List.of());
    }
  }

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

  /** effect: 공식 노드 Tooltip의 효과 문구. 없으면 null. */
  public record PassiveEffect(String name, String description, String icon, String effect) {
    public PassiveEffect(String name, String description, String icon) {
      this(name, description, icon, null);
    }
  }

  public record ArkGrid(List<GridSlot> slots, List<GridEffect> effects) {
    public ArkGrid {
      slots = slots == null ? List.of() : List.copyOf(slots);
      effects = effects == null ? List.of() : List.copyOf(effects);
    }
  }

  /** core: 코어 Tooltip 해석(arkgrid-tooltip-v1). 해석할 수 없으면 null. */
  /** lines: 공식 코어 Tooltip의 등급 줄·귀속·거래·나머지 줄(#288, item-tooltips 전용). */
  public record GridSlot(
      Integer index,
      String icon,
      String name,
      Integer point,
      String grade,
      List<GridGem> gems,
      CoreDetail core,
      TooltipLines lines) {
    public GridSlot {
      gems = gems == null ? List.of() : List.copyOf(gems);
    }

    public GridSlot(
        Integer index,
        String icon,
        String name,
        Integer point,
        String grade,
        List<GridGem> gems,
        CoreDetail core) {
      this(index, icon, name, point, grade, gems, core, null);
    }

    public GridSlot(
        Integer index, String icon, String name, Integer point, String grade, List<GridGem> gems) {
      this(index, icon, name, point, grade, gems, null);
    }
  }

  /** options는 포인트 구간 오름차순. conditions는 '코어 옵션 발동 조건' 줄. */
  public record CoreDetail(
      String coreType, Integer supplyWillpower, List<CoreOption> options, List<String> conditions) {
    public CoreDetail {
      options = options == null ? List.of() : List.copyOf(options);
      conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
  }

  public record CoreOption(int threshold, String text) {}

  /** value: 공식 Tooltip 문구(예: "공격력 +1.02%"), percent: 그 수치. 없으면 null. */
  public record GridEffect(String name, Integer level, String value, java.math.BigDecimal percent) {
    public GridEffect(String name, Integer level) {
      this(name, level, null, null);
    }
  }

  /** 툴팁 표시용 공식 Tooltip 줄(#288). titleLine은 등급 줄, tradeLines는 귀속·거래, extraLines는 나머지 줄을 원문 순서로. */
  public record TooltipLines(String titleLine, List<String> tradeLines, List<String> extraLines) {
    public TooltipLines {
      tradeLines = tradeLines == null ? List.of() : List.copyOf(tradeLines);
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
    }
  }

  /** detail: 젬 Tooltip 해석. 해석할 수 없으면 null. lines: 툴팁 표시용 줄(#288, item-tooltips 전용). */
  public record GridGem(
      Integer index,
      String icon,
      Boolean active,
      String grade,
      GemDetail detail,
      TooltipLines lines) {
    public GridGem(Integer index, String icon, Boolean active, String grade, GemDetail detail) {
      this(index, icon, active, grade, detail, null);
    }

    public GridGem(Integer index, String icon, Boolean active, String grade) {
      this(index, icon, active, grade, null);
    }
  }

  /** gemPoint: 젬 기본 정보의 젬 포인트, corePoint: 코어에 더하는 질서·혼돈 포인트. */
  public record GemDetail(
      String name,
      String gemType,
      Integer gemPoint,
      Integer requiredWillpower,
      Integer baseWillpower,
      Integer willpowerEfficiency,
      Integer corePoint,
      List<GemOption> options) {
    public GemDetail {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }

  public record GemOption(
      String name, Integer level, String effect, java.math.BigDecimal percent) {}
}
