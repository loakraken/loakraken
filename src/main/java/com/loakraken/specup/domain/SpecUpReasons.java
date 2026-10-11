package com.loakraken.specup.domain;

/**
 * 시뮬레이터가 준 미지원 이유를 화면용 코드와 고정 문장으로 바꾼다(#315, PM-프론트 16:27 ④). 같은 코드는 늘 같은 문장이라 웹이 종류별로 한 줄로 접을 수
 * 있다. 원래 문장(슬롯 번호·툴팁 원문이 들어감)은 detail로 따로 준다.
 */
public final class SpecUpReasons {
  private SpecUpReasons() {}

  public record Reason(String code, String text) {}

  public static final Reason MODEL_UNCONFIRMED =
      new Reason("MODEL_UNCONFIRMED", "이 항목의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.");
  public static final Reason NO_OFFICIAL_COMBAT_POWER =
      new Reason("NO_OFFICIAL_COMBAT_POWER", "공식 전투력이 없어 계산하지 않았습니다.");
  public static final Reason GEM_TIER_UNCONFIRMED =
      new Reason("GEM_TIER_UNCONFIRMED", "이 티어 보석의 계수를 아직 확인하지 않아 계산하지 않았습니다.");
  public static final Reason GEM_LEVEL_UNCONFIRMED =
      new Reason("GEM_LEVEL_UNCONFIRMED", "이 레벨 보석의 기본 공격력 수치를 아직 확인하지 않아 계산하지 않았습니다.");
  public static final Reason GEM_BASE_ATTACK_MISMATCH =
      new Reason("GEM_BASE_ATTACK_MISMATCH", "보석의 기본 공격력 표기가 계수표와 달라 계산하지 않았습니다.");
  public static final Reason BASE_ATTACK_SOURCE_UNKNOWN =
      new Reason("BASE_ATTACK_SOURCE_UNKNOWN", "확인하지 않은 기본 공격력 % 출처가 있어 보석 변화는 계산하지 않았습니다.");
  public static final Reason STONE_BONUS_UNCONFIRMED =
      new Reason("STONE_BONUS_UNCONFIRMED", "스톤 레벨 보너스가 아직 확인하지 않은 구간으로 바뀌어 계산하지 않았습니다.");
  public static final Reason WEAPON_ADD_DAMAGE_UNREADABLE =
      new Reason("WEAPON_ADD_DAMAGE_UNREADABLE", "무기 추가 피해(공식 툴팁)를 읽지 못해 계산하지 않았습니다.");
  public static final Reason QUALITY_VALUE_UNCONFIRMED =
      new Reason("QUALITY_VALUE_UNCONFIRMED", "이 품질의 무기 추가 피해 수치를 아직 확인하지 않아 계산하지 않았습니다.");
  public static final Reason ENGRAVING_MISSING =
      new Reason("ENGRAVING_MISSING", "각인 정보가 없어 계산하지 않았습니다.");
  public static final Reason ENGRAVING_MISMATCH =
      new Reason("ENGRAVING_MISMATCH", "공식 각인 설명값이 계수표와 달라 계산하지 않았습니다.");
  public static final Reason ENGRAVING_LEVEL_UNCONFIRMED =
      new Reason("ENGRAVING_LEVEL_UNCONFIRMED", "이 레벨 조합의 각인 수치를 아직 확인하지 않아 계산하지 않았습니다.");

  /** 시뮬레이터 이유 문장 → 코드. 모르는 문장은 MODEL_UNCONFIRMED로 둔다(계산하지 않았다는 뜻은 같음). */
  public static Reason classify(String simulatorReason) {
    if (simulatorReason == null) return MODEL_UNCONFIRMED;
    if (simulatorReason.contains("공식 전투력이 없어")) return NO_OFFICIAL_COMBAT_POWER;
    if (simulatorReason.contains("티어 보석 계수")) return GEM_TIER_UNCONFIRMED;
    if (simulatorReason.contains("보석의 기본 공격력 수치")) return GEM_LEVEL_UNCONFIRMED;
    if (simulatorReason.contains("기본 공격력 표기")) return GEM_BASE_ATTACK_MISMATCH;
    if (simulatorReason.contains("기본 공격력 % 출처")) return BASE_ATTACK_SOURCE_UNKNOWN;
    if (simulatorReason.contains("레벨 보너스")) return STONE_BONUS_UNCONFIRMED;
    if (simulatorReason.contains("무기 추가 피해(공식 툴팁)를 읽지")) return WEAPON_ADD_DAMAGE_UNREADABLE;
    if (simulatorReason.contains("무기 추가 피해 수치")) return QUALITY_VALUE_UNCONFIRMED;
    if (simulatorReason.contains("각인 정보가 없어")) return ENGRAVING_MISSING;
    if (simulatorReason.contains("각인 설명값이 계수표와 달라")) return ENGRAVING_MISMATCH;
    if (simulatorReason.contains("레벨 조합의 각인 수치")) return ENGRAVING_LEVEL_UNCONFIRMED;
    return MODEL_UNCONFIRMED;
  }
}
