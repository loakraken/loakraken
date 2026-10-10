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
      String category) {
    public Skill {
      tripods = tripods == null ? List.of() : List.copyOf(tripods);
      gemEffects = gemEffects == null ? List.of() : List.copyOf(gemEffects);
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

  /** 스킬 Tooltip의 "공격 타입". 백 어택은 치명타 적중률 +10%p(클로아 스킬별 치명 확률과 같은 규칙). */
  public enum AttackType {
    BACK_ATTACK,
    HEAD_ATTACK,
    NONE
  }

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
      List<String> extraLines) {
    public static final String PARSER_VERSION = "equipment-tooltip-v4";

    public EquipmentDetail {
      tradeLines = tradeLines == null ? List.of() : List.copyOf(tradeLines);
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
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
          List.of());
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
  public record Gem(
      Integer slot,
      String name,
      String icon,
      Integer level,
      String grade,
      List<String> extraLines) {
    public Gem {
      extraLines = extraLines == null ? List.of() : List.copyOf(extraLines);
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
  public record GridSlot(
      Integer index,
      String icon,
      String name,
      Integer point,
      String grade,
      List<GridGem> gems,
      CoreDetail core) {
    public GridSlot {
      gems = gems == null ? List.of() : List.copyOf(gems);
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

  /** detail: 젬 Tooltip 해석. 해석할 수 없으면 null. */
  public record GridGem(
      Integer index, String icon, Boolean active, String grade, GemDetail detail) {
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
