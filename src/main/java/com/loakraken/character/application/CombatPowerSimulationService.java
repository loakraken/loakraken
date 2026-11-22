package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.CombatPowerArkGridModel;
import com.loakraken.character.domain.CombatPowerBraceletModel;
import com.loakraken.character.domain.CombatPowerEquipmentModel;
import com.loakraken.character.domain.CombatPowerGemModel;
import com.loakraken.character.domain.CombatPowerPolishModel;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.character.domain.CombatPowerReasons;
import com.loakraken.character.domain.CombatPowerRefineModel;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.api.ErrorCode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 전투력 시뮬레이터(계약 v0, A단계). 응답 모양은 OpenAPI가 기준이다.
 *
 * <p><b>공식 API를 부르지 않는다</b>: 캐릭터 캐시만 읽고, 없으면 CHARACTER_NOT_CACHED(404)다. 바꾼 항목(changes)을 검증하고 순서대로
 * 합친다. 처리정지 목록 캐릭터는 캐시가 없을 때와 같은 404다. 전투력 계산식은 계산 검증의 비교 기준 실측 대조(Q1·Q2)로 항목별로 확정되기 전까지 그 항목을
 * UNSUPPORTED로 값 없이 낸다(추정 금지).
 *
 * <p>B단계(#243): 보석(GEM_LEVEL·GEM_LEVEL_ALL)을 계산한다({@link CombatPowerGemModel}). after = before ×
 * 모델(바뀐 세팅) / 모델(현재 세팅)이고, contributions[].delta는 순서대로 적용한 누적 전투력을 소수 둘째 자리로 반올림한 값의 차이라 합이 delta와
 * 같다.
 */
@Service
public class CombatPowerSimulationService {
  public static final String RULE_VERSION =
      com.loakraken.common.api.EfficiencyRuleVersions.COMBAT_POWER_SIM;
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final String GEM_BASIS =
      "보석 레벨별 배수와 기본 공격력 %(보석·스톤 레벨 보너스·완갑 추가 효과, 공식 툴팁)로 바뀐 비율을 공식 전투력에 곱했습니다.";
  private static final String NO_OFFICIAL_BASIS = "공식 전투력이 없어 계산하지 않습니다.";
  private static final String WEAPON_QUALITY_BASIS =
      "무기 품질의 추가 피해(10 + 20 × (품질/100)²) 배수 비율을 공식 전투력에 곱했습니다.";
  private static final String ARMOR_QUALITY_BASIS = "방어구 품질은 체력에만 붙어 전투력이 바뀌지 않습니다.";
  private static final String ENGRAVING_BASIS = "각인 효과(공식 각인 설명값)의 배수 비율을 공식 전투력에 곱했습니다.";
  private static final String LEVEL_UNCONFIRMED_BASIS = "이 레벨 조합의 각인 수치를 아직 확인하지 않아 계산하지 않았습니다.";
  private static final Set<String> GRADES = Set.of("LOW", "MID", "HIGH");
  private static final List<String> CORE_GRADES = List.of("영웅", "전설", "유물", "고대");
  private static final Set<String> WEAPON_ARMOR = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final List<String> ARM_GUARD_GRADES = List.of("영웅", "전설", "유물", "고대");
  private static final String ARM_GUARD_BASIS =
      "팔 보호구 단계표(무기 공격력·주스탯·기본 공격력)와 등급 추가 효과(기본 공격력 %)로 기본 공격력을 다시 계산한 비율을 공식 전투력에 곱했습니다(펫 효과(힘민지 +1%)는 있다고 가정).";
  private static final String REFINE_BASIS =
      "재련 단계의 주스탯·무기 공격력 변화로 기본 공격력을 다시 계산한 비율을 공식 전투력에 곱했습니다(펫 효과(힘민지 +1%)는 있다고 가정).";
  private static final String POLISH_BASIS =
      "연마 줄마다 독립 배수(공격력·추가 피해·적에게 주는 피해·치명타)와 무기 공격력(재련과 같은 무기 공격력 항)을 바꾼 비율을 공식 전투력에 곱했습니다.";
  private static final String BRACELET_BASIS =
      "팔찌 옵션의 전투력 배수(특수 옵션 계수·전투 특성 0.03%/점·고정 무기 공격력)를 바꾼 비율을 공식 전투력에 곱했습니다.";

  /** 서포터 전투력은 케어 + 버프로 식이 다르다(계산 검증 #331). 서포터 모델 구현 전에는 딜러 식을 쓰지 않는다(PM-백엔드 P0 10-11 20:23). */
  public static final String SUPPORTER_BASIS = CombatPowerReasons.SUPPORTER_MODEL_PENDING.text();

  public static final String ROLE_UNKNOWN_BASIS = CombatPowerReasons.ROLE_UNKNOWN.text();

  /**
   * 방어구 재련·상급 재련의 전투력 Δ는 계산하지 않는다(P0 핫픽스 11-09): 같은 시점 공식 전투력과 비교한 실측에서 우리 값이 실제의 약 0.66배였다(주 스탯 배수
   * 경로). 스펙업 목록·추천 순서·시뮬레이터 모두 이 이유로 UNSUPPORTED(기존 코드 MODEL_UNCONFIRMED). 무기 재련·상급 재련은 그대로.
   */
  static final String ARMOR_REFINE_UNCONFIRMED = "방어구 재련·상급 재련의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";

  /** 팔 보호구(완갑)도 같은 주 스탯 경로라 확인 전까지 계산하지 않는다(P0 핫픽스 11-09). */
  static final String ARM_GUARD_UNCONFIRMED = "팔 보호구 재련의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";

  /** 팔찌 힘·민첩·지능 줄도 같은 주 스탯 → 전투력 경로라 확인 전까지 계산하지 않는다(PM 총괄 11-09, 시뮬레이터 공개 전). */
  static final String BRACELET_MAIN_STAT_UNCONFIRMED =
      "팔찌 힘·민첩·지능 줄의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";

  private static final String UNSUPPORTED_BASIS = "이 항목의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";
  private static final String ARK_GRID_BASIS =
      "아크 그리드 코어 배수(1 + 코어 계수/100, 코어 계열·포인트 구간·등급 상한, 고대 17P 이상 +1.0%p)의 비율을 공식 전투력에 곱했습니다.";

  private final CharacterCache cache;
  private final CharacterPublicSummaryPort stored;
  private final CombatPowerGemModel.Coefficients gemCoefficients;
  private final CombatPowerEquipmentModel.Coefficients equipmentCoefficients;
  private final CombatPowerPolishValues polishValues;
  private final CombatPowerRefineModel.Coefficients refineCoefficients;
  private final CombatPowerBraceletModel.Coefficients braceletCoefficients;
  private final CombatPowerArkGridModel.Coefficients arkGridCoefficients;
  private final com.loakraken.character.domain.CombatPowerArkPassiveModel.Coefficients
      arkPassiveCoefficients;

  /** 방어구 재련·상급 재련·완갑 전투력 문(spec-up.armor-cp-gate.enabled, 기본 꺼짐). */
  private final ArmorCpGate armorGate;

  /** 문 밖(|r| > 0.10%)일 때의 이유(코드 ARMOR_CP_RESIDUAL_HIGH, 웹 문구와 같은 해요체, PM-프론트 11-21). */
  static final String ARMOR_CP_RESIDUAL_HIGH =
      com.loakraken.character.domain.CombatPowerReasons.ARMOR_CP_RESIDUAL_HIGH.text();

  public CombatPowerSimulationService(
      CharacterCache cache,
      CharacterPublicSummaryPort stored,
      CombatPowerGemModel.Coefficients gemCoefficients,
      CombatPowerEquipmentModel.Coefficients equipmentCoefficients,
      CombatPowerPolishValues polishValues,
      CombatPowerRefineModel.Coefficients refineCoefficients,
      CombatPowerBraceletModel.Coefficients braceletCoefficients,
      CombatPowerArkGridModel.Coefficients arkGridCoefficients,
      com.loakraken.character.domain.CombatPowerArkPassiveModel.Coefficients arkPassiveCoefficients,
      ArmorCpGate armorGate) {
    this.armorGate = armorGate;
    this.cache = cache;
    this.stored = stored;
    this.gemCoefficients = gemCoefficients;
    this.equipmentCoefficients = equipmentCoefficients;
    this.polishValues = polishValues;
    this.refineCoefficients = refineCoefficients;
    this.braceletCoefficients = braceletCoefficients;
    this.arkGridCoefficients = arkGridCoefficients;
    this.arkPassiveCoefficients = arkPassiveCoefficients;
  }

  public enum Kind {
    GEM_LEVEL_ALL,
    GEM_LEVEL,
    EQUIPMENT_REFINE,
    EQUIPMENT_ADVANCED_REFINE,
    EQUIPMENT_QUALITY,
    POLISH,
    BRACELET_EFFECT,
    ENGRAVING_LEVEL,
    STONE_ENGRAVING,
    ARK_PASSIVE_POINT,
    ARK_GRID_CORE,
    ARM_GUARD,
    ARK_GRID_GEM
  }

  /** 아크 그리드 젬 교체의 새 젬(M49 G1): 젬 종류(안정·견고·불변·침식·왜곡·붕괴), 의지력 효율, 옵션 두 줄. 포인트는 Change.point. */
  public record GemSwap(String gemType, Integer willEfficiency, List<GemOption> effects) {}

  /** 젬 옵션 한 줄(이름·레벨 1~5). */
  public record GemOption(String name, Integer level) {}

  /** 바꾼 항목 하나. kind마다 쓰는 필드가 다르다(계약 문서 2절). */
  public record Change(
      Kind kind,
      Integer slot,
      Integer index,
      Integer line,
      Integer level,
      Integer refine,
      Integer quality,
      String option,
      String grade,
      String name,
      String tree,
      Integer points,
      Integer slotIndex,
      Integer point,
      Integer karmaLevel,
      GemSwap gem) {
    /** 젬 교체가 아닌 변경(깨달음 카르마 레벨 포함). */
    public Change(
        Kind kind,
        Integer slot,
        Integer index,
        Integer line,
        Integer level,
        Integer refine,
        Integer quality,
        String option,
        String grade,
        String name,
        String tree,
        Integer points,
        Integer slotIndex,
        Integer point,
        Integer karmaLevel) {
      this(
          kind,
          slot,
          index,
          line,
          level,
          refine,
          quality,
          option,
          grade,
          name,
          tree,
          points,
          slotIndex,
          point,
          karmaLevel,
          null);
    }

    /** karmaLevel 없는 변경(깨달음 카르마 레벨 바꾸기가 아닌 모든 종류). */
    public Change(
        Kind kind,
        Integer slot,
        Integer index,
        Integer line,
        Integer level,
        Integer refine,
        Integer quality,
        String option,
        String grade,
        String name,
        String tree,
        Integer points,
        Integer slotIndex,
        Integer point) {
      this(
          kind, slot, index, line, level, refine, quality, option, grade, name, tree, points,
          slotIndex, point, null);
    }
  }

  public enum Status {
    OK,
    UNSUPPORTED
  }

  public record Contribution(
      String kind,
      String target,
      String from,
      String to,
      BigDecimal delta,
      Status status,
      String basis,
      String reasonCode) {}

  public record Result(
      String characterName,
      Instant fetchedAt,
      BigDecimal before,
      BigDecimal modelBefore,
      BigDecimal after,
      BigDecimal delta,
      BigDecimal deltaPercent,
      boolean partial,
      List<Contribution> contributions,
      int unsupportedCount,
      List<String> assumptions) {}

  public Result simulate(String characterName, List<Change> changes) {
    return simulate(snapshot(characterName), changes);
  }

  /**
   * 시뮬레이션 기준 캐릭터(캐시)를 한 번 읽는다. 스펙업 가이드(#315)가 후보마다 {@link #simulate(CharacterResult, List)}를 부를 때
   * 같은 세팅을 쓰게 한다. 없으면 CHARACTER_NOT_CACHED다.
   */
  public CharacterResult snapshot(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    CharacterResult result;
    try {
      result = cache.find(name).or(() -> cache.findArmory(name)).orElse(null);
    } catch (CharacterException exception) {
      result = null;
    }
    // 처리정지 목록이어도 캐시(공식 실시간 응답)로 계산한다. 저장하지 않는 메모리 계산이고, 404로 처리정지 여부를 드러내지 않는다(#363).
    if (result == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED);
    return result;
  }

  /** 장착 항목의 전투력 기여(#327): 그 항목을 뺐을 때 공식 전투력이 몇 % 줄어드는지. 지금은 팔찌만. */
  public record ContributionItem(
      String kind,
      String target,
      Status status,
      BigDecimal contributionPct,
      BigDecimal withoutCombatPower,
      String basis) {
    /** UNSUPPORTED 이유 코드(시뮬레이터·스펙업과 같은 코드). OK면 null. */
    public String reasonCode() {
      return status == Status.OK ? null : CombatPowerReasons.classify(basis).code();
    }
  }

  public record Contributions(
      String characterName,
      Instant fetchedAt,
      BigDecimal combatPower,
      List<ContributionItem> items) {}

  /** 전투력 기여 % = 1 − (빼고 난 전투력 / 지금 전투력)(PM 총괄 10-11 16:36). 공식 호출 없음. */
  public Contributions contributions(String characterName) {
    var result = snapshot(characterName);
    var loadout = result.details().loadout();
    var official = result.details().profile().combatPower();
    var before = official == null ? null : official.setScale(2, RoundingMode.HALF_UP);
    var gemModel = CombatPowerGemModel.of(gemCoefficients, loadout);
    var refineModel =
        CombatPowerRefineModel.of(
            refineCoefficients,
            loadout,
            result.details().profile().stats(),
            gemModel.currentBaseAttackPct());
    var braceletModel =
        CombatPowerBraceletModel.of(
            braceletCoefficients, loadout, result.details().profile().stats());
    var items = new ArrayList<ContributionItem>();
    var roleReason = roleReason(result);
    if (braceletModel != null)
      items.add(
          roleReason != null
              ? new ContributionItem("BRACELET", "팔찌", Status.UNSUPPORTED, null, null, roleReason)
              : braceletContribution(before, braceletModel, refineModel));
    return new Contributions(
        result.details().profile().characterName(), result.fetchedAt(), before, List.copyOf(items));
  }

  private static ContributionItem braceletContribution(
      BigDecimal before,
      CombatPowerBraceletModel.Model bracelet,
      CombatPowerRefineModel.Model refine) {
    if (before == null)
      return new ContributionItem(
          "BRACELET", "팔찌", Status.UNSUPPORTED, null, null, NO_OFFICIAL_BASIS);
    var without = bracelet.without();
    if (without == null) {
      var reason = bracelet.firstUnknown();
      return new ContributionItem(
          "BRACELET",
          "팔찌",
          Status.UNSUPPORTED,
          null,
          null,
          reason == null ? "전투 특성 합이나 도약 포인트를 읽지 못해 계산하지 않았습니다." : reason);
    }
    var ratio = without.ratio();
    if (without.weaponFlatDelta().signum() != 0 || without.mainStatDelta().signum() != 0) {
      var weapon = refine.ratio(Map.of(), without.weaponFlatDelta(), without.mainStatDelta());
      if (weapon == null)
        return new ContributionItem(
            "BRACELET", "팔찌", Status.UNSUPPORTED, null, null, refine.unsupportedReason());
      ratio = ratio.multiply(weapon, MATH);
    }
    var after = before.multiply(ratio, MATH).setScale(2, RoundingMode.HALF_UP);
    var pct =
        BigDecimal.ONE
            .subtract(ratio, MATH)
            .multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP);
    return new ContributionItem(
        "BRACELET", "팔찌", Status.OK, pct, after, "팔찌 효과를 모두 빼고 팔찌 도약 포인트도 뺀 전투력과 지금 전투력의 차이입니다.");
  }

  /** 팔찌 효율(#345). percent는 지금 전투력이 팔찌를 뺀 전투력보다 몇 % 높은지, lines는 그 줄만 뺐을 때의 같은 값(팔찌 도약은 따로 한 줄). */
  public record BraceletEfficiency(
      BigDecimal percent,
      Status status,
      String reason,
      List<BraceletLineEfficiency> lines,
      String ruleVersion) {
    public String reasonCode() {
      return status == Status.OK ? null : CombatPowerReasons.classify(reason).code();
    }
  }

  public record BraceletLineEfficiency(
      String text, BigDecimal percent, Status status, String reason) {
    public String reasonCode() {
      return status == Status.OK ? null : CombatPowerReasons.classify(reason).code();
    }
  }

  /**
   * 캐릭터 응답의 팔찌 효율(#345, PM 총괄 10-11 21:21·21:46). 지금 전투력 ÷ 팔찌(효과 줄 전부 + 팔찌 도약)를 뺀 전투력 − 1이다. 전투력 기여
   * API는 1 − 뺀 ÷ 지금이라 값이 다르다(같은 모델, 표현만 다름). 줄별은 그 줄만 뺐을 때(전투 특성 줄은 T에서 그 줄만)이고 팔찌 도약은 따로 한 줄이다. 곱
   * 구조라 줄별 합은 전체 값과 같지 않다(줄별 값은 그 줄만 뺀 값). 시뮬레이터 플래그와 무관하고 공식 호출이 없다. 팔찌가 없으면 null.
   */
  public BraceletEfficiency braceletEfficiency(CharacterResult result) {
    var loadout = result.details().loadout();
    var stats = result.details().profile().stats();
    var bracelet = CombatPowerBraceletModel.of(braceletCoefficients, loadout, stats);
    if (bracelet == null) return null;
    var leapText = bracelet.braceletLeap() > 0 ? "팔찌 도약 +" + bracelet.braceletLeap() : null;
    String blocked = roleReason(result);
    if (blocked == null && result.details().profile().combatPower() == null)
      blocked = NO_OFFICIAL_BASIS;
    if (blocked != null) {
      var reason = blocked;
      var lines = new ArrayList<BraceletLineEfficiency>();
      for (var line : bracelet.lines())
        lines.add(new BraceletLineEfficiency(line.text(), null, Status.UNSUPPORTED, reason));
      if (leapText != null)
        lines.add(new BraceletLineEfficiency(leapText, null, Status.UNSUPPORTED, reason));
      return new BraceletEfficiency(
          null, Status.UNSUPPORTED, reason, List.copyOf(lines), RULE_VERSION);
    }
    var gemModel = CombatPowerGemModel.of(gemCoefficients, loadout);
    var refine =
        CombatPowerRefineModel.of(
            refineCoefficients, loadout, stats, gemModel.currentBaseAttackPct());
    var removed =
        CombatPowerBraceletModel.option(
            braceletCoefficients, CombatPowerBraceletModel.REMOVE, null);
    var lines = new ArrayList<BraceletLineEfficiency>();
    for (int i = 0; i < bracelet.lines().size(); i++) {
      var line = bracelet.lines().get(i);
      var reason = bracelet.cannotReplace(i, removed);
      var ratio = reason == null ? removalRatio(bracelet.factor(Map.of(i, removed)), refine) : null;
      if (reason == null && ratio == null) reason = refine.unsupportedReason();
      lines.add(
          ratio == null
              ? new BraceletLineEfficiency(line.text(), null, Status.UNSUPPORTED, reason)
              : new BraceletLineEfficiency(line.text(), efficiencyPct(ratio), Status.OK, null));
    }
    if (leapText != null) {
      var leap = bracelet.leapRatio();
      lines.add(
          leap == null
              ? new BraceletLineEfficiency(leapText, null, Status.UNSUPPORTED, LEAP_UNREAD)
              : new BraceletLineEfficiency(leapText, efficiencyPct(leap), Status.OK, null));
    }
    var total = removalRatio(bracelet.without(), refine);
    if (total == null) {
      var reason = bracelet.firstUnknown();
      if (reason == null) reason = refine.unsupportedReason();
      if (reason == null) reason = "전투 특성 합이나 도약 포인트를 읽지 못해 계산하지 않았습니다.";
      return new BraceletEfficiency(
          null, Status.UNSUPPORTED, reason, List.copyOf(lines), RULE_VERSION);
    }
    return new BraceletEfficiency(
        efficiencyPct(total),
        Status.OK,
        "팔찌 효과를 모두 빼고 팔찌 도약 포인트도 뺀 전투력보다 지금 전투력이 몇 % 높은지입니다.",
        List.copyOf(lines),
        RULE_VERSION);
  }

  private static final String LEAP_UNREAD = "도약 포인트 합을 읽지 못해 계산하지 않았습니다.";
  private static final String LEAP = "도약";

  /** 도약 포인트 최대(고대 장비 18 + 전투 레벨 총합 40 + 랭크 총합 12, 공식 가이드 아크 패시브 2026-06-24). */
  static final int LEAP_MAX = 70;

  private static final String LEAP_BASIS =
      "도약 포인트 합의 전투력 배수(1 + 0.002 × 포인트)를 바꾼 비율을 공식 전투력에 곱했습니다.";
  private static final String EVOLUTION = "진화";
  private static final String ENLIGHTENMENT = "깨달음";
  private static final String EVOLUTION_BASIS =
      "진화 포인트 합의 전투력 배수(1 + 0.0075 × (포인트 − 40), 40 이하는 1)를 바꾼 비율을 공식 전투력에 곱한 모델값입니다(40~140 사이 포인트당"
          + " 기울기는 한 곳에서만 확인).";
  private static final String ENLIGHTENMENT_BASIS =
      "깨달음 포인트 합의 전투력 배수(1 + 0.007 × 포인트, 100까지)를 바꾼 비율을 공식 전투력에 곱했습니다. 101번째 포인트는 전투력이 없습니다(캐릭터 사이 공식 전투력 대조).";
  private static final String ENLIGHTENMENT_OVER_REASON = "깨달음 포인트가 최대(101)를 넘는 값이라 계산하지 않았습니다.";
  private static final String PASSIVE_UNREAD = "아크 패시브 포인트 합을 읽지 못해 계산하지 않았습니다.";

  /** 트리 포인트 합의 전투력 배수. 계산할 수 없으면(깨달음 100 초과·모르는 트리) null. */
  private BigDecimal passiveFactor(String tree, int points) {
    if (LEAP.equals(tree))
      return BigDecimal.ONE.add(
          braceletCoefficients.leapPerPoint().multiply(BigDecimal.valueOf(points), MATH), MATH);
    if (EVOLUTION.equals(tree))
      return com.loakraken.character.domain.CombatPowerArkPassiveModel.evolution(
          arkPassiveCoefficients, points);
    if (ENLIGHTENMENT.equals(tree))
      return com.loakraken.character.domain.CombatPowerArkPassiveModel.enlightenment(
          arkPassiveCoefficients, points);
    return null;
  }

  /** 팔찌 변화의 전투력 비(W·S 항은 재련 모델로). 계산할 수 없으면 null. */
  private static BigDecimal removalRatio(
      CombatPowerBraceletModel.Factor factor, CombatPowerRefineModel.Model refine) {
    if (factor == null) return null;
    var ratio = factor.ratio();
    if (factor.weaponFlatDelta().signum() != 0 || factor.mainStatDelta().signum() != 0) {
      var weapon = refine.ratio(Map.of(), factor.weaponFlatDelta(), factor.mainStatDelta());
      if (weapon == null) return null;
      ratio = ratio.multiply(weapon, MATH);
    }
    return ratio;
  }

  /** 뺀 전투력 ÷ 지금 = ratio일 때 지금이 뺀 쪽보다 몇 % 높은지(지금 ÷ 뺀 − 1, PM 총괄 10-11 21:46). */
  private static BigDecimal efficiencyPct(BigDecimal ratio) {
    return BigDecimal.ONE
        .divide(ratio, MATH)
        .subtract(BigDecimal.ONE, MATH)
        .multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
  }

  /** 딜러가 아니면(서포터·판정 불가) 계산하지 않는 이유. 딜러면 null. 판정은 깨달음 노드(CharacterPosition). */
  public static String roleReason(CharacterResult result) {
    var profile = result.details().profile();
    var role =
        CharacterPosition.judge(profile.className(), result.details().loadout().arkPassive())
            .role();
    if (role == CharacterPosition.Role.DEALER) return null;
    return role == CharacterPosition.Role.SUPPORT ? SUPPORTER_BASIS : ROLE_UNKNOWN_BASIS;
  }

  /** 이미 읽은 캐릭터로 시뮬레이션한다(공식 호출 없음). */
  public Result simulate(CharacterResult result, List<Change> changes) {
    return simulate(result, models(result), changes);
  }

  /**
   * 한 캐릭터 캐시의 계산 모델(보석·재련·팔찌). 캐릭터 캐시에서만 만들고 바꾸지 않으므로, 같은 캐시로 후보 여럿을 잴 때(스펙업 목록) 요청마다 한 번 만들어 나눠
   * 쓴다.
   */
  public record Models(
      CombatPowerGemModel.Model gem,
      CombatPowerRefineModel.Model refine,
      CombatPowerBraceletModel.Model bracelet) {}

  public Models models(CharacterResult result) {
    var loadout = result.details().loadout();
    var gemModel = CombatPowerGemModel.of(gemCoefficients, loadout);
    var refineModel =
        CombatPowerRefineModel.of(
            refineCoefficients,
            loadout,
            result.details().profile().stats(),
            gemModel.currentBaseAttackPct());
    var braceletModel =
        CombatPowerBraceletModel.of(
            braceletCoefficients, loadout, result.details().profile().stats());
    return new Models(gemModel, refineModel, braceletModel);
  }

  /** models는 같은 result로 만든 것이어야 한다(models(result)). */
  public Result simulate(CharacterResult result, Models models, List<Change> changes) {
    var loadout = result.details().loadout();
    // 같은 대상은 뒤의 변경이 이긴다. 순서는 처음 나온 자리를 지킨다.
    var merged = new LinkedHashMap<String, Planned>();
    for (var change : changes)
      merged.put(
          key(change), describe(loadout, models.gem(), models.refine(), models.bracelet(), change));
    return compute(result, models.gem(), models.refine(), models.bracelet(), merged.values());
  }

  /**
   * 스펙업 연마 후보의 장신구 바꾸기(공개 시뮬레이터 요청에는 없는 내부 계산). 장비 순번 index의 연마 줄을 lines(줄 순서, 툴팁 꼴 '이름 +값')로 바꾼다.
   * null 줄은 빠진 줄로 보고 전투력 몫 0(Δ크라켄 점수와 같은 목표 장신구: 지금 고정값 공격력·무기 공격력 줄이 빠진 매물, 규칙은
   * SpecUpScoreCandidates.swapLines). 지금 줄과 같은 줄은 바꾸지 않는다. 줄마다 POLISH 한 줄 교체와 같은 규칙(수치표 1680 고대만,
   * 확인하지 않은 계수는 계산하지 않음, 무기 공격력 줄은 재련 모델 W 항)이다. 줄 수가 지금과 다르거나, 목표를 만든 지금 줄(expected, 점수 입력)이 툴팁 줄과
   * 줄마다 같지 않으면 계산하지 않는다(null). models는 같은 result로 만든 것(models(result)).
   */
  public Result simulatePolishSwap(
      CharacterResult result, Models models, int index, List<String> expected, List<String> lines) {
    var changes = polishSwapChanges(result, index, expected, lines);
    return changes == null ? null : simulate(result, models, changes);
  }

  /** POLISH 변경의 option: 점수 옵션이 없는 줄(전투력 몫 0). */
  public static final String POLISH_NONE = "NONE";

  static final String POLISH_NONE_TEXT = "점수 옵션 없는 줄";

  /**
   * 장신구 바꾸기(simulatePolishSwap과 같은 목표)를 공개 시뮬레이터 변경으로: 바뀌는 줄마다 POLISH(index, line, option, grade)
   * 하나, 빠진 줄은 option NONE. 이 목록을 시뮬레이터에 넣은 값이 simulatePolishSwap과 같다(스펙업 추천 순서 단계를 다시 잴 때 같은 값).
   * 바뀌는 줄이 먼저(이름 있는 옵션 → NONE). 점수 입력 줄이 툴팁 줄과 다르거나, 목표 줄이 연마 수치표에 없으면 null.
   */
  public List<Change> polishSwapChanges(
      CharacterResult result, int index, List<String> expected, List<String> lines) {
    return polishSwapChanges(result, index, expected, lines, false);
  }

  /**
   * allLines면 지금과 같은 줄도 변경으로 낸다(줄마다 하나, 장신구 전체를 다시 정함). 스펙업 추천 순서는 단계 changes를 차례로 이어 다시 재므로(같은 줄은
   * 뒤의 변경이 이김), 같은 장신구의 앞 단계가 바꾼 줄을 이 단계가 원래 값으로 되돌릴 때도 그 줄을 내야 한다(계산 검증 11-15 #951 HOLD).
   */
  public List<Change> polishSwapChanges(
      CharacterResult result,
      int index,
      List<String> expected,
      List<String> lines,
      boolean allLines) {
    if (polishSwapReject(result, index, expected, lines) != null) return null;
    var item = result.details().loadout().equipment().get(index);
    var current = item.detail().polishEffects();
    var named = new ArrayList<Change>();
    var none = new ArrayList<Change>();
    for (int line = 0; line < lines.size(); line++) {
      var now = current.get(line);
      var next = lines.get(line);
      if (!allLines && next != null && sameLine(next, now.name() + " " + now.value())) continue;
      if (next == null) {
        none.add(polishChange(index, line, POLISH_NONE, null));
        continue;
      }
      var target = polishTarget(item.type(), next);
      if (target == null) return null;
      named.add(polishChange(index, line, target[0], target[1]));
    }
    named.addAll(none);
    return List.copyOf(named);
  }

  /** 장신구 바꾸기를 계산하지 않는 까닭(polishSwapChanges가 null인 까닭 가운데 줄 짝 단계). 계산할 수 있으면 null. */
  public static final String POLISH_REJECT_LINE_MISMATCH = "line_mismatch";

  public static final String POLISH_REJECT_UNMAPPABLE = "unmappable";

  /**
   * polishSwapChanges가 줄 짝을 맞추기 전에 거르는 까닭: 장비·줄이 없으면 unmappable, 줄 수가 다르거나 점수 입력 줄이 툴팁 줄과 줄마다 같지
   * 않으면 line_mismatch(두 원문 파서가 갈라짐, 코드 리뷰 #945 P3·#949 P3). 거르지 않으면 null(목표 줄이 수치표에 없어 null일 수는
   * 있다).
   */
  public String polishSwapReject(
      CharacterResult result, int index, List<String> expected, List<String> lines) {
    var loadout = result.details().loadout();
    if (index < 0 || index >= loadout.equipment().size()) return POLISH_REJECT_UNMAPPABLE;
    var item = loadout.equipment().get(index);
    if (item == null || item.detail() == null || lines == null || expected == null)
      return POLISH_REJECT_UNMAPPABLE;
    var current = item.detail().polishEffects();
    if (current.size() != lines.size() || current.size() != expected.size())
      return POLISH_REJECT_LINE_MISMATCH;
    // 줄 짝은 순서로 맞추므로, 목표 줄을 만든 지금 줄(점수 입력)이 툴팁 줄과 줄마다 같아야 한다. 하나라도 다르면(두 원문 파서가 갈라짐) 틀린 Δ 대신 계산하지
    // 않는다(코드 리뷰 #945 P3).
    for (int line = 0; line < current.size(); line++) {
      var now = current.get(line);
      if (now == null || expected.get(line) == null) return POLISH_REJECT_LINE_MISMATCH;
      if (!sameLine(expected.get(line), now.name() + " " + now.value()))
        return POLISH_REJECT_LINE_MISMATCH;
    }
    return null;
  }

  private static Change polishChange(int index, int line, String option, String grade) {
    return new Change(
        Kind.POLISH,
        null,
        index,
        line,
        null,
        null,
        null,
        option,
        grade,
        null,
        null,
        null,
        null,
        null);
  }

  /** 툴팁 꼴 줄('이름 +값')의 POLISH option(% 옵션은 끝에 " %")과 등급(LOW·MID·HIGH). 수치표에 없으면 null. */
  private String[] polishTarget(String part, String text) {
    int split = text.lastIndexOf(" +");
    if (split <= 0) return null;
    var name = text.substring(0, split);
    var value = text.substring(split + 1);
    boolean percent = value.endsWith("%");
    for (var row : polishValues.options())
      if (row.option().equals(name) && row.percent() == percent && row.parts().contains(part)) {
        String grade =
            value.equals(row.low())
                ? "LOW"
                : value.equals(row.mid()) ? "MID" : value.equals(row.high()) ? "HIGH" : null;
        return grade == null ? null : new String[] {percent ? name + " %" : name, grade};
      }
    return null;
  }

  /** 같은 줄인지(빈칸·천 단위 쉼표 차이는 무시: 점수 입력 줄과 툴팁 줄의 표기 차이). */
  private static boolean sameLine(String a, String b) {
    return a.replaceAll("[\\s,]", "").equals(b.replaceAll("[\\s,]", ""));
  }

  private Result compute(
      CharacterResult result,
      CombatPowerGemModel.Model gemModel,
      CombatPowerRefineModel.Model refineModel,
      CombatPowerBraceletModel.Model braceletModel,
      java.util.Collection<Planned> merged) {
    var loadout = result.details().loadout();
    var official = result.details().profile().combatPower();
    // 반올림은 서버에서 끝낸다(PM-프론트 18:31 (g)): 전투력 소수 둘째 자리, deltaPercent 소수 셋째 자리.
    var before = official == null ? null : official.setScale(2, RoundingMode.HALF_UP);
    var engravings = currentEngravings(loadout);
    var weaponAddDamage = CombatPowerEquipmentModel.currentWeaponAddDamage(weaponDetail(loadout));
    var state =
        new State(
            Map.of(), null, Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(), Map.of(), null,
            Map.of());
    var running = before;
    var contributions = new ArrayList<Contribution>();
    int unsupported = 0;
    var roleReason = roleReason(result);
    var gemBlocked = arkGemBlocked(merged);
    for (var planned : merged) {
      String reason = roleReason != null ? roleReason : planned.reason();
      if (reason == null && planned.effect() instanceof ArkGridGemEffect gem)
        reason = gemBlocked.get(gem.core().index());
      if (roleReason == null) {
        var hold = heldReason(loadout, braceletModel, planned);
        // 방어구 재련·상급 재련·완갑: 문이 열린 스냅샷이면 계산하고, r이 크면 그 이유로 닫는다(스위치가 꺼져 있으면 지금처럼).
        if (hold != null && armorClosed(loadout, planned.change()))
          hold =
              switch (armorGate.state(result)) {
                case OPEN -> null;
                case RESIDUAL_HIGH -> ARMOR_CP_RESIDUAL_HIGH;
                case DISABLED, UNAVAILABLE -> hold;
              };
        if (hold != null) reason = hold;
      }
      if (planned.effect() != null && reason == null) {
        if (before == null) reason = NO_OFFICIAL_BASIS;
        else {
          var next = state.apply(planned.effect(), engravings);
          reason = stoneBonusReason(loadout, engravings, next);
          var ratio =
              reason == null
                  ? ratio(gemModel, refineModel, braceletModel, engravings, weaponAddDamage, next)
                  : null;
          if (reason == null && ratio == null) reason = LEVEL_UNCONFIRMED_BASIS;
          if (reason == null) {
            state = next;
            var value = before.multiply(ratio, MATH).setScale(2, RoundingMode.HALF_UP);
            contributions.add(planned.ok(value.subtract(running), planned.basis()));
            running = value;
            continue;
          }
        }
      }
      unsupported++;
      contributions.add(planned.unsupported(reason == null ? UNSUPPORTED_BASIS : reason));
    }
    var delta = before == null ? null : running.subtract(before);
    return new Result(
        result.details().profile().characterName(),
        result.fetchedAt(),
        before,
        null,
        running,
        delta,
        before == null || before.signum() == 0
            ? null
            : delta.multiply(BigDecimal.valueOf(100)).divide(before, 3, RoundingMode.HALF_UP),
        unsupported > 0,
        List.copyOf(contributions),
        unsupported,
        List.of(
            "현재 세팅은 캐릭터 캐시(수집 시각 fetchedAt)를 기준으로 합니다.",
            "before는 공식 전투력입니다. 계산식이 확정된 항목만 after에 반영하고, 나머지는 UNSUPPORTED로 값 없이 알립니다.",
            "계산한 항목은 순서대로 적용합니다. contributions[].delta의 합은 delta와 같습니다."));
  }

  /** 계산할 수 있는 변경의 효과. 보석(칸 → 레벨), 무기 품질(새 추가 피해 %), 방어구 품질(배수 1), 각인(각인서·스톤 레벨, null은 그대로). */
  private sealed interface Effect {}

  private record GemEffect(Map<Integer, Integer> levels) implements Effect {}

  private record WeaponQualityEffect(BigDecimal addDamage) implements Effect {}

  private record NeutralEffect() implements Effect {}

  private record EngravingEffect(String name, Integer book, Integer stone) implements Effect {}

  /** 재련 단계(#319·#333): 장비 순번 → 새 일반·상급 단계(null이면 그대로). current는 지금 단계. */
  private record RefineEffect(
      int index, Integer level, Integer advanced, CombatPowerRefineModel.Target current)
      implements Effect {}

  /** 팔 보호구 단계·등급(#341). */
  private record ArmGuardEffect(CombatPowerRefineModel.ArmGuard target) implements Effect {}

  /** 장신구 연마 줄 교체(#325 모델): 장비 순번:줄 → 지금 줄과 바꾼 줄의 몫. */
  private record PolishEffect(
      String key, CombatPowerPolishModel.Line from, CombatPowerPolishModel.Line to)
      implements Effect {}

  /** 팔찌 효과 줄(#327): 줄 번호 → 새 줄(빼기 포함). */
  private record BraceletEffect(int line, CombatPowerBraceletModel.Line next) implements Effect {}

  /** 아크 패시브 트리(진화·깨달음·도약) 포인트 합: 지금 → 새 합. */
  private record PassiveEffect(String tree, int current, int next) implements Effect {}

  /** 깨달음 카르마 레벨: 지금 → 새 레벨. W 항의 무기 공격력 %가 레벨마다 karmaWeaponPctPerLevel만큼 바뀐다. */
  private record KarmaEffect(int current, int next) implements Effect {}

  /** 아크 그리드 코어(slotIndex) 등급·포인트: 지금·바꾼 뒤 코어 계수 C(%). */
  private record ArkGridCoreEffect(int slot, BigDecimal nowPct, BigDecimal nextPct)
      implements Effect {}

  /**
   * 아크 그리드 젬 교체(M49 G1): 코어(공식 Slots 한 칸)의 젬 하나를 새 포인트·옵션 두 줄로 바꾼다. 같은 요청의 교체는 계산할 때 함께 다시 더한다(옵션
   * 레벨은 이름별 합, 코어 포인트는 코어별 합).
   */
  private record ArkGridGemEffect(
      CharacterLoadout.GridSlot core,
      CharacterLoadout.GridGem old,
      int point,
      Map<String, Integer> options,
      List<CharacterLoadout.GridEffect> officialEffects,
      int required)
      implements Effect {
    String key() {
      return core.index() + ":" + old.index();
    }
  }

  /** 각인 하나의 각인서·스톤 레벨. */
  private record EngravingLevels(int book, int stone) {}

  /** 지금까지 적용한 변경. 보석 칸 레벨, 무기 추가 피해 %(null이면 그대로), 각인별 레벨, 도약 포인트 합(null이면 그대로). */
  private record State(
      Map<Integer, Integer> gems,
      BigDecimal weaponAddDamage,
      Map<String, EngravingLevels> engravings,
      Map<Integer, CombatPowerRefineModel.Target> refines,
      Map<Integer, CombatPowerBraceletModel.Line> bracelet,
      CombatPowerRefineModel.ArmGuard armGuard,
      Map<String, PolishEffect> polish,
      Map<Integer, ArkGridCoreEffect> cores,
      Map<String, PassiveEffect> passives,
      KarmaEffect karma,
      Map<String, ArkGridGemEffect> arkGems) {
    State apply(Effect effect, Map<String, CharacterLoadout.PassiveEngraving> currents) {
      return switch (effect) {
        case ArkGridGemEffect gem -> {
          var next = new HashMap<>(arkGems);
          next.put(gem.key(), gem);
          yield new State(
              gems,
              weaponAddDamage,
              engravings,
              refines,
              bracelet,
              armGuard,
              polish,
              cores,
              passives,
              karma,
              Map.copyOf(next));
        }
        case GemEffect gem -> {
          var next = new HashMap<>(gems);
          next.putAll(gem.levels());
          yield new State(
              Map.copyOf(next),
              weaponAddDamage,
              engravings,
              refines,
              bracelet,
              armGuard,
              polish,
              cores,
              passives,
              karma,
              arkGems);
        }
        case WeaponQualityEffect quality ->
            new State(
                gems,
                quality.addDamage(),
                engravings,
                refines,
                bracelet,
                armGuard,
                polish,
                cores,
                passives,
                karma,
                arkGems);
        case RefineEffect refine -> {
          var next = new HashMap<>(refines);
          var base = refines.getOrDefault(refine.index(), refine.current());
          next.put(
              refine.index(),
              new CombatPowerRefineModel.Target(
                  refine.level() != null ? refine.level() : base.level(),
                  refine.advanced() != null ? refine.advanced() : base.advanced()));
          yield new State(
              gems,
              weaponAddDamage,
              engravings,
              Map.copyOf(next),
              bracelet,
              armGuard,
              polish,
              cores,
              passives,
              karma,
              arkGems);
        }
        case BraceletEffect line -> {
          var next = new HashMap<>(bracelet);
          next.put(line.line(), line.next());
          yield new State(
              gems,
              weaponAddDamage,
              engravings,
              refines,
              Map.copyOf(next),
              armGuard,
              polish,
              cores,
              passives,
              karma,
              arkGems);
        }
        case ArmGuardEffect guard ->
            new State(
                gems,
                weaponAddDamage,
                engravings,
                refines,
                bracelet,
                guard.target(),
                polish,
                cores,
                passives,
                karma,
                arkGems);
        case NeutralEffect neutral -> this;
        case KarmaEffect next ->
            new State(
                gems,
                weaponAddDamage,
                engravings,
                refines,
                bracelet,
                armGuard,
                polish,
                cores,
                passives,
                next,
                arkGems);
        case ArkGridCoreEffect core -> {
          var next = new HashMap<>(cores);
          next.put(core.slot(), core);
          yield new State(
              gems,
              weaponAddDamage,
              engravings,
              refines,
              bracelet,
              armGuard,
              polish,
              Map.copyOf(next),
              passives,
              karma,
              arkGems);
        }
        case PolishEffect line -> {
          var next = new HashMap<>(polish);
          next.put(line.key(), line);
          yield new State(
              gems,
              weaponAddDamage,
              engravings,
              refines,
              bracelet,
              armGuard,
              Map.copyOf(next),
              cores,
              passives,
              karma,
              arkGems);
        }
        case PassiveEffect next -> {
          var map = new HashMap<>(passives);
          map.put(next.tree(), next);
          yield new State(
              gems,
              weaponAddDamage,
              engravings,
              refines,
              bracelet,
              armGuard,
              polish,
              cores,
              Map.copyOf(map),
              karma,
              arkGems);
        }
        case EngravingEffect engraving -> {
          var next = new HashMap<>(engravings);
          var base = engravings.get(engraving.name());
          if (base == null) {
            var current = currents.get(engraving.name());
            base =
                new EngravingLevels(
                    current.level(),
                    current.abilityStoneLevel() == null ? 0 : current.abilityStoneLevel());
          }
          next.put(
              engraving.name(),
              new EngravingLevels(
                  engraving.book() != null ? engraving.book() : base.book(),
                  engraving.stone() != null ? engraving.stone() : base.stone()));
          yield new State(
              gems,
              weaponAddDamage,
              Map.copyOf(next),
              refines,
              bracelet,
              armGuard,
              polish,
              cores,
              passives,
              karma,
              arkGems);
        }
      };
    }
  }

  /** 검증을 마친 변경 하나. effect가 있으면 계산할 수 있는 종류이고(basis는 OK일 때의 근거), reason이 있으면 계산하지 않는 이유다. */
  private record Planned(
      Change change,
      String target,
      String from,
      String to,
      Effect effect,
      String basis,
      String reason) {
    Contribution ok(BigDecimal delta, String okBasis) {
      return new Contribution(
          change.kind().name(), target, from, to, delta, Status.OK, okBasis, null);
    }

    Contribution unsupported(String why) {
      return new Contribution(
          change.kind().name(),
          target,
          from,
          to,
          null,
          Status.UNSUPPORTED,
          why,
          CombatPowerReasons.classify(why).code());
    }
  }

  /**
   * 현재 세팅 대비 전투력 비. 모든 항목이 독립 배수라 항목 배수를 곱한다. 계산할 수 없는 조합이면 null. 각인 상태는 처음 레벨을 currents에서 가져온다.
   */
  private BigDecimal ratio(
      CombatPowerGemModel.Model gemModel,
      CombatPowerRefineModel.Model refineModel,
      CombatPowerBraceletModel.Model braceletModel,
      Map<String, CharacterLoadout.PassiveEngraving> currents,
      BigDecimal weaponCurrent,
      State state) {
    var ratio = BigDecimal.ONE;
    if (!state.gems().isEmpty()) {
      var base = gemModel.ratio(Map.of());
      var next = gemModel.ratio(state.gems());
      if (base == null || next == null) return null;
      ratio = ratio.multiply(next.divide(base, MATH), MATH);
    }
    var weaponFlatDelta = BigDecimal.ZERO;
    var mainStatDelta = BigDecimal.ZERO;
    if (!state.bracelet().isEmpty()) {
      if (braceletModel == null) return null;
      var factor = braceletModel.factor(state.bracelet());
      if (factor == null) return null;
      ratio = ratio.multiply(factor.ratio(), MATH);
      weaponFlatDelta = factor.weaponFlatDelta();
      mainStatDelta = factor.mainStatDelta();
    }
    // 연마 줄 교체(#325): 줄마다 독립 배수를 바꾸고, 무기 공격력 줄은 W 항(고정 N·무공%)으로 넘긴다.
    var weaponPctDelta = BigDecimal.ZERO;
    for (var line : state.polish().values()) {
      ratio = ratio.multiply(line.to().factor().divide(line.from().factor(), MATH), MATH);
      weaponFlatDelta =
          weaponFlatDelta.add(line.to().weaponFlat().subtract(line.from().weaponFlat()), MATH);
      weaponPctDelta =
          weaponPctDelta.add(line.to().weaponPct().subtract(line.from().weaponPct()), MATH);
    }
    // 깨달음 카르마(PM 총괄 11-15 (f), 딜러만): 레벨마다 무기 공격력 % +0.1%(W 항 안, 3곳 일치).
    if (state.karma() != null)
      weaponPctDelta =
          weaponPctDelta.add(
              refineModel
                  .coefficients()
                  .karmaWeaponPctPerLevel()
                  .multiply(BigDecimal.valueOf(state.karma().next() - state.karma().current())),
              MATH);
    // 재련 단계와 팔찌·연마·카르마의 무기 공격력은 같은 W 항을 쓴다.
    if (!state.refines().isEmpty()
        || weaponFlatDelta.signum() != 0
        || weaponPctDelta.signum() != 0
        || mainStatDelta.signum() != 0
        || state.armGuard() != null) {
      var refine =
          refineModel.ratioFull(
              state.refines(), weaponFlatDelta, weaponPctDelta, mainStatDelta, state.armGuard());
      if (refine == null) return null;
      ratio = ratio.multiply(refine, MATH);
    }
    // 팔 보호구 등급과 스톤 [레벨 보너스]의 기본 공격력 %는 보석 변화까지 반영한 q에 더한다: (1 + q + Δ)/(1 + q).
    var delta =
        state.armGuard() == null ? BigDecimal.ZERO : refineModel.armGuardPctDelta(state.armGuard());
    delta = delta.add(stoneBonusDelta(currents, state), MATH);
    if (delta.signum() != 0) {
      var pct = gemModel.baseAttackPct(state.gems());
      if (pct == null) return null;
      var q = BigDecimal.ONE.add(pct.divide(BigDecimal.valueOf(100), MATH), MATH);
      ratio = ratio.multiply(q.add(delta, MATH).divide(q, MATH), MATH);
    }
    if (state.weaponAddDamage() != null) {
      if (weaponCurrent == null) return null;
      ratio =
          ratio.multiply(
              CombatPowerEquipmentModel.weaponQualityRatio(weaponCurrent, state.weaponAddDamage()),
              MATH);
    }
    if (!state.arkGems().isEmpty()) {
      var gemRatio = arkGemRatio(state);
      if (gemRatio == null) return null;
      ratio = ratio.multiply(gemRatio, MATH);
    }
    // 아크 그리드 코어(계산 검증 11-02): 코어 배수는 다른 항목과 독립이라 (1 + C(new)/100) / (1 + C(now)/100)를 곱한다.
    for (var core : state.cores().values())
      ratio =
          ratio.multiply(
              CombatPowerArkGridModel.factor(core.nextPct())
                  .divide(CombatPowerArkGridModel.factor(core.nowPct()), MATH),
              MATH);
    // 아크 패시브 트리 포인트 합 P: 트리마다 독립 배수 f(P), 바꾼 비율 f(새)/f(지금)을 곱한다. 도약 (1 + 0.002·P, 팔찌 도약과 같은
    // 계수), 진화 (1 + 0.0075·max(P − 40, 0), 모델값), 깨달음 (1 + 0.007·P, 100 이하만).
    for (var passive : state.passives().values()) {
      var now = passiveFactor(passive.tree(), passive.current());
      var next = passiveFactor(passive.tree(), passive.next());
      // 계획 단계에서 계산할 수 없는 트리·값(깨달음 100 초과 등)은 이미 걸러 효과가 없으므로 여기 오지 않는다(지키는 줄).
      if (now == null || next == null) return null;
      ratio = ratio.multiply(next.divide(now, MATH), MATH);
    }
    for (var entry : state.engravings().entrySet()) {
      var current = currents.get(entry.getKey());
      int book = current.level();
      int stone = current.abilityStoneLevel() == null ? 0 : current.abilityStoneLevel();
      var before =
          CombatPowerEquipmentModel.engravingFactor(
              equipmentCoefficients, entry.getKey(), book, stone);
      var after =
          CombatPowerEquipmentModel.engravingFactor(
              equipmentCoefficients,
              entry.getKey(),
              entry.getValue().book(),
              entry.getValue().stone());
      if (before == null || after == null) return null;
      ratio = ratio.multiply(after.divide(before, MATH), MATH);
    }
    return ratio;
  }

  /**
   * 아크 그리드 젬 교체 배수(M49 G1, 계산 검증 11-22). 공식 ArkGrid.Effects 레벨에서 바꾼 젬들의 옵션 레벨을 이름별로 빼고(활성 젬만) 새 옵션을
   * 더해 젬 옵션 배수를 다시 구하고, 코어마다 공식 포인트에서 바꾼 젬의 포인트를 빼고(활성 젬만) 새 포인트를 더해 코어 배수를 다시 구한다. 두 배수는 독립이라 곱한다.
   * 같은 요청에서 그 코어의 등급·포인트도 바꾸면(ARK_GRID_CORE) 함께 계산하지 않는다(null).
   */
  private BigDecimal arkGemRatio(State state) {
    var official = state.arkGems().values().iterator().next().officialEffects();
    var levels = new LinkedHashMap<String, Integer>();
    for (var effect : official) {
      if (effect == null || effect.name() == null || effect.level() == null) return null;
      levels.merge(effect.name().strip(), effect.level(), Integer::sum);
    }
    var points = new LinkedHashMap<Integer, Integer>();
    var slots = new LinkedHashMap<Integer, CharacterLoadout.GridSlot>();
    for (var swap : state.arkGems().values()) {
      int index = swap.core().index();
      if (state.cores().containsKey(index)) return null;
      slots.putIfAbsent(index, swap.core());
      points.putIfAbsent(index, swap.core().point());
      if (Boolean.TRUE.equals(swap.old().active())) {
        // 코어 포인트는 젬의 질서·혼돈 포인트(공식 '혼돈 포인트 : 4')다. 젬 포인트(총합 '젬 포인트 : 15')가 아니다(#1079 계산 검증 P0).
        points.merge(index, -swap.old().detail().corePoint(), Integer::sum);
        for (var option : swap.old().detail().options())
          levels.merge(option.name().strip(), -option.level(), Integer::sum);
      }
      points.merge(index, swap.point(), Integer::sum);
      swap.options().forEach((name, level) -> levels.merge(name, level, Integer::sum));
    }
    var after = new ArrayList<CharacterLoadout.GridEffect>();
    for (var entry : levels.entrySet()) {
      if (entry.getValue() < 0) return null;
      if (entry.getValue() > 0)
        after.add(new CharacterLoadout.GridEffect(entry.getKey(), entry.getValue(), null, null));
    }
    var now = CombatPowerArkGridModel.gems(arkGridCoefficients, official);
    var next = CombatPowerArkGridModel.gems(arkGridCoefficients, after);
    if (now.ratio() == null || next.ratio() == null) return null;
    var ratio = next.ratio().divide(now.ratio(), MATH);
    for (var entry : slots.entrySet()) {
      var slot = entry.getValue();
      var moved =
          new CharacterLoadout.GridSlot(
              slot.index(),
              slot.icon(),
              slot.name(),
              points.get(entry.getKey()),
              slot.grade(),
              slot.gems(),
              slot.core(),
              slot.lines());
      var before = CombatPowerArkGridModel.core(arkGridCoefficients, slot);
      var target = CombatPowerArkGridModel.core(arkGridCoefficients, moved);
      if (before.reason() != null || target.reason() != null) return null;
      ratio =
          ratio.multiply(
              CombatPowerArkGridModel.factor(target.pct())
                  .divide(CombatPowerArkGridModel.factor(before.pct()), MATH),
              MATH);
    }
    return ratio;
  }

  /** 지금·바꾼 뒤 스톤 세공 레벨 합. */
  private static int[] stoneSums(
      Map<String, CharacterLoadout.PassiveEngraving> currents, State state) {
    int before = 0;
    int after = 0;
    for (var engraving : currents.values()) {
      int stone = engraving.abilityStoneLevel() == null ? 0 : engraving.abilityStoneLevel();
      before += stone;
      var next = state.engravings().get(engraving.name());
      after += next == null ? stone : next.stone();
    }
    return new int[] {before, after};
  }

  /** 스톤 [레벨 보너스]의 기본 공격력 변화(비율, 1.5% = 0.015). 합이 5를 넘나들 때만 0이 아니다. */
  private BigDecimal stoneBonusDelta(
      Map<String, CharacterLoadout.PassiveEngraving> currents, State state) {
    var sums = stoneSums(currents, state);
    return CombatPowerEquipmentModel.stoneBonusPct(equipmentCoefficients, sums[1])
        .subtract(CombatPowerEquipmentModel.stoneBonusPct(equipmentCoefficients, sums[0]))
        .divide(BigDecimal.valueOf(100), MATH);
  }

  /**
   * 스톤 합이 5를 넘나드는데 지금 공식 [레벨 보너스]가 규칙(합 5 이상 1.5%)과 다르면 이유 문장. 공식 가이드 규칙이라 어느 합이든 계산하지만, 지금 값이 규칙과
   * 맞는지 공식 스톤 문구로 확인한다(추정 금지).
   */
  private String stoneBonusReason(
      CharacterLoadout loadout,
      Map<String, CharacterLoadout.PassiveEngraving> currents,
      State state) {
    if (stoneBonusDelta(currents, state).signum() == 0) return null;
    var official = CombatPowerGemModel.stoneBonusPct(loadout);
    var expected =
        CombatPowerEquipmentModel.stoneBonusPct(
            equipmentCoefficients, stoneSums(currents, state)[0]);
    return official.compareTo(expected) == 0
        ? null
        : "지금 어빌리티 스톤 [레벨 보너스]가 공식 규칙(세공 레벨 합 5 이상이면 기본 공격력 1.5%)과 달라 계산하지 않았습니다.";
  }

  private static Map<String, CharacterLoadout.PassiveEngraving> currentEngravings(
      CharacterLoadout loadout) {
    var result = new LinkedHashMap<String, CharacterLoadout.PassiveEngraving>();
    if (loadout.engravings() != null)
      for (var engraving : loadout.engravings().arkPassiveEffects())
        if (engraving != null && engraving.name() != null)
          result.putIfAbsent(engraving.name(), engraving);
    return result;
  }

  private static CharacterLoadout.EquipmentDetail weaponDetail(CharacterLoadout loadout) {
    for (var item : loadout.equipment())
      if (item != null && "무기".equals(item.type())) return item.detail();
    return null;
  }

  private static String key(Change change) {
    return switch (change.kind()) {
      case GEM_LEVEL_ALL -> "GEM_ALL";
      case GEM_LEVEL -> "GEM:" + change.slot();
      case EQUIPMENT_REFINE, EQUIPMENT_ADVANCED_REFINE, EQUIPMENT_QUALITY ->
          change.kind() + ":" + change.index();
      case POLISH -> "POLISH:" + change.index() + ":" + change.line();
      case BRACELET_EFFECT -> "BRACELET:" + change.line();
      case ENGRAVING_LEVEL -> "ENGRAVING:" + change.name();
      case STONE_ENGRAVING -> "STONE:" + change.name();
      case ARK_PASSIVE_POINT -> karmaChange(change) ? "KARMA" : "ARK_PASSIVE:" + change.tree();
      case ARK_GRID_CORE -> "ARK_GRID:" + change.slotIndex();
      case ARM_GUARD -> "ARM_GUARD";
      case ARK_GRID_GEM -> "ARK_GRID_GEM:" + change.slotIndex() + ":" + change.index();
    };
  }

  private Planned describe(
      CharacterLoadout loadout,
      CombatPowerGemModel.Model gemModel,
      CombatPowerRefineModel.Model refineModel,
      CombatPowerBraceletModel.Model braceletModel,
      Change change) {
    if (change.kind() == null) throw invalid("kind가 필요합니다.");
    if (change.karmaLevel() != null && change.kind() != Kind.ARK_PASSIVE_POINT)
      throw invalid("karmaLevel은 ARK_PASSIVE_POINT(tree 깨달음)에만 쓸 수 있습니다.");
    return switch (change.kind()) {
      case GEM_LEVEL_ALL -> {
        range("level", change.level(), 0, 10);
        var targets = new LinkedHashMap<Integer, Integer>();
        String reason = gemModel.unsupportedReason();
        // 전체 바꾸기는 장착한 칸만 대상이다. 보석이 하나도 없으면 바꿀 칸이 없다(빈 칸 끼우기는 GEM_LEVEL로).
        if (reason == null && gemModel.slots().isEmpty()) reason = "장착한 보석이 없습니다.";
        for (var slot : gemModel.slots().keySet()) {
          targets.put(slot, change.level());
          if (reason == null) reason = gemModel.cannotSet(slot, change.level());
        }
        yield new Planned(
            change,
            "보석 전체",
            null,
            String.valueOf(change.level()),
            new GemEffect(targets),
            GEM_BASIS,
            reason);
      }
      case GEM_LEVEL -> {
        range("level", change.level(), 0, 10);
        var gem =
            loadout.gems() == null
                ? null
                : loadout.gems().items().stream()
                    .filter(
                        item ->
                            item != null
                                && change.slot() != null
                                && change.slot().equals(item.slot()))
                    .findFirst()
                    .orElse(null);
        if (gem == null) {
          // 빈 칸(0~10 안, 보석 없음)은 0레벨에서 목표 레벨 4티어 보석을 끼우는 변화다(스펙업 빈 칸 후보).
          if (change.slot() == null || !gemModel.empty(change.slot()))
            throw notFound("보석 slot " + change.slot());
          yield new Planned(
              change,
              "보석 " + change.slot() + "번(빈 칸)",
              "0",
              str(change.level()),
              new GemEffect(Map.of(change.slot(), change.level())),
              GEM_BASIS,
              gemModel.cannotSet(change.slot(), change.level()));
        }
        yield new Planned(
            change,
            "보석 " + gem.slot() + "번(" + gem.name() + ")",
            str(gem.level()),
            str(change.level()),
            new GemEffect(Map.of(gem.slot(), change.level())),
            GEM_BASIS,
            gemModel.cannotSet(gem.slot(), change.level()));
      }
      case EQUIPMENT_REFINE, EQUIPMENT_ADVANCED_REFINE, EQUIPMENT_QUALITY -> {
        var item = equipment(loadout, change.index());
        var detail = item.detail();
        if (change.kind() == Kind.EQUIPMENT_QUALITY) {
          range("quality", change.quality(), 0, 100);
          if (detail == null || detail.quality() == null)
            throw notFound("품질이 있는 장비 index " + change.index());
          yield quality(item, detail, change);
        }
        if (!WEAPON_ARMOR.contains(item.type()) || detail == null || detail.refineLevel() == null)
          throw notFound("재련 장비 index " + change.index());
        if (change.kind() == Kind.EQUIPMENT_REFINE) {
          range("refine", change.refine(), 10, 25);
          yield new Planned(
              change,
              item.name(),
              str(detail.refineLevel()),
              str(change.refine()),
              new RefineEffect(
                  change.index(),
                  change.refine(),
                  null,
                  currentTarget(refineModel, change.index())),
              REFINE_BASIS,
              refineModel.cannotSet(change.index(), change.refine()));
        }
        range("level", change.level(), 0, 40);
        yield new Planned(
            change,
            item.name(),
            str(detail.advancedRefineLevel()),
            str(change.level()),
            new RefineEffect(
                change.index(), null, change.level(), currentTarget(refineModel, change.index())),
            REFINE_BASIS,
            refineModel.cannotSetAdvanced(change.index(), change.level()));
      }
      case POLISH -> {
        var item = equipment(loadout, change.index());
        if (item.detail() == null
            || change.line() == null
            || change.line() < 0
            || change.line() >= item.detail().polishEffects().size())
          throw notFound("연마 효과 index " + change.index() + " line " + change.line());
        // NONE: 점수 옵션이 없는 줄(전투력 몫 0). 스펙업 연마 단계가 사는 장신구의 빠진 줄(지금 고정값 줄)을 나타낸다(PM 총괄 11-15 (c) A안).
        boolean none = POLISH_NONE.equals(change.option());
        if (!none) {
          text("option", change.option());
          grade(change.grade());
        }
        var current = item.detail().polishEffects().get(change.line());
        // 수치표는 한 아이템 레벨·등급(지금 1680 고대) 값이다. 다른 장신구(예: 1540)는 같은 등급도 값이 달라 계산하지 않는다(코드 리뷰 10-25).
        boolean tableFits = polishTableFits(polishValues, item);
        var value =
            tableFits && !none
                ? polishValue(polishValues, item.type(), change.option(), change.grade(), current)
                : null;
        var option = none ? null : optionName(change.option());
        var from = CombatPowerPolishModel.line(current.name(), current.value());
        var to =
            none
                ? CombatPowerPolishModel.Line.NEUTRAL
                : value == null ? null : CombatPowerPolishModel.line(option, value);
        String reason =
            !tableFits
                ? POLISH_TABLE_MISMATCH
                : !none && value == null
                    ? "이 부위·연마 옵션·등급의 수치를 확인하지 않아 계산하지 않았습니다."
                    : from == null
                        ? "지금 연마 줄("
                            + current.name()
                            + " "
                            + current.value()
                            + ")의 전투력 계수를 확인하지 않아 계산하지 않았습니다."
                        : to == null ? "이 연마 옵션의 전투력 계수를 확인하지 않아 계산하지 않았습니다." : null;
        yield new Planned(
            change,
            item.name() + " 연마 " + (change.line() + 1),
            current.name() + " " + current.value(),
            none
                ? POLISH_NONE_TEXT
                : value == null ? change.option() + " " + change.grade() : option + " " + value,
            reason == null ? new PolishEffect(key(change), from, to) : null,
            POLISH_BASIS,
            // 무기 공격력 줄은 재련 모델의 W 항을 쓰므로 그 모델을 만들 수 있어야 한다. 무공 줄 비율은 비교 기준 B 시뮬레이터 교체 값과
            // ±0.00002 안이다(계산 검증 10-13 04:49 권고).
            reason != null
                ? reason
                : touchesWeapon(from) || touchesWeapon(to)
                    ? refineModel.unsupportedReason()
                    : null);
      }
      case BRACELET_EFFECT -> {
        var bracelet =
            loadout.equipment().stream()
                .filter(item -> item != null && "팔찌".equals(item.type()) && item.detail() != null)
                .findFirst()
                .orElse(null);
        if (bracelet == null
            || change.line() == null
            || change.line() < 0
            || change.line() >= bracelet.detail().braceletEffects().size())
          throw notFound("팔찌 효과 line " + change.line());
        text("name", change.name());
        // "없음"은 그 줄을 빼는 변경이다(전투력 기여 계산과 같은 규칙).
        if (!CombatPowerBraceletModel.REMOVE.equals(change.name())) grade(change.grade());
        var next =
            CombatPowerBraceletModel.option(braceletCoefficients, change.name(), change.grade());
        yield new Planned(
            change,
            "팔찌 효과 " + (change.line() + 1),
            bracelet.detail().braceletEffects().get(change.line()).text(),
            CombatPowerBraceletModel.REMOVE.equals(change.name())
                ? CombatPowerBraceletModel.REMOVE
                : change.name() + " " + change.grade(),
            new BraceletEffect(change.line(), next),
            BRACELET_BASIS,
            braceletModel.cannotReplace(change.line(), next));
      }
      case ENGRAVING_LEVEL -> {
        range("level", change.level(), 0, 4);
        var engraving =
            loadout.engravings() == null
                ? null
                : loadout.engravings().arkPassiveEffects().stream()
                    .filter(
                        item ->
                            item != null
                                && change.name() != null
                                && change.name().equals(item.name()))
                    .findFirst()
                    .orElse(null);
        if (engraving == null) throw notFound("각인 " + change.name());
        yield engraving(
            change,
            engraving.name(),
            str(engraving.level()),
            new EngravingEffect(engraving.name(), change.level(), null),
            engraving);
      }
      case STONE_ENGRAVING -> {
        range("level", change.level(), 0, 4);
        var stone =
            loadout.equipment().stream()
                .filter(item -> item != null && item.detail() != null)
                .flatMap(item -> item.detail().stoneEngravings().stream())
                .filter(
                    item ->
                        item != null && change.name() != null && change.name().equals(item.name()))
                .findFirst()
                .orElse(null);
        if (stone == null) throw notFound("스톤 각인 " + change.name());
        var engraving =
            loadout.engravings() == null
                ? null
                : loadout.engravings().arkPassiveEffects().stream()
                    .filter(item -> item != null && stone.name().equals(item.name()))
                    .findFirst()
                    .orElse(null);
        yield engraving(
            change,
            "스톤 " + stone.name(),
            str(stone.level()),
            new EngravingEffect(stone.name(), null, change.level()),
            engraving);
      }
      case ARK_PASSIVE_POINT -> {
        if (change.karmaLevel() != null) {
          // 깨달음 카르마 레벨은 자기 필드로만(포인트·level과 섞지 않음, PM 총괄 11-19).
          if (!ENLIGHTENMENT.equals(change.tree()))
            throw invalid("karmaLevel은 tree 깨달음에만 쓸 수 있습니다.");
          if (change.points() != null || change.level() != null)
            throw invalid("karmaLevel은 points·level과 함께 쓸 수 없습니다.");
          yield karma(loadout, refineModel, change, change.karmaLevel(), karmaCpEnabled);
        }
        range("points", change.points(), 0, 999);
        var point =
            loadout.arkPassive() == null
                ? null
                : loadout.arkPassive().points().stream()
                    .filter(
                        item ->
                            item != null
                                && change.tree() != null
                                && change.tree().equals(item.name()))
                    .findFirst()
                    .orElse(null);
        if (point == null) throw notFound("아크 패시브 " + change.tree());
        // 도약은 고대 장비 +18·전투 레벨 총합 +40·랭크 총합 +12 = 최대 70(공식 가이드 아크 패시브, 최근수정일 2026-06-24). 웹을 거치지 않은
        // 요청도
        // 막는다(코드 리뷰 web #1706 P2).
        if (LEAP.equals(point.name())) range("points", change.points(), 0, LEAP_MAX);
        // 진화 포인트 최대 140(공식 공지 13307, 계수 리소스 evolutionMaxPoints): 넘으면 웹을 거치지 않은 요청도 400(코드 리뷰 #965
        // P2).
        if (EVOLUTION.equals(point.name()))
          range("points", change.points(), 0, arkPassiveCoefficients.evolutionMaxPoints());
        // 깨달음 포인트 최대 101(계수 리소스 enlightenmentLimitPoints): 넘으면 400, 100 초과는 계산하지 않음(아래).
        if (ENLIGHTENMENT.equals(point.name()))
          range("points", change.points(), 0, arkPassiveCoefficients.enlightenmentLimitPoints());
        var target = "아크 패시브 " + point.name();
        var from = str(point.value());
        var to = str(change.points());
        // 트리마다 독립 배수(PM 총괄 11-15 19:12): 도약은 비교 기준 둘이 같고, 진화는 모델값, 깨달음은 100 이하만(지금 값이나 바꿀 값이 100을
        // 넘으면 계산하지 않음). 그 밖 트리는 계산하지 않는다.
        var basis =
            LEAP.equals(point.name())
                ? LEAP_BASIS
                : EVOLUTION.equals(point.name())
                    ? EVOLUTION_BASIS
                    : ENLIGHTENMENT.equals(point.name()) ? ENLIGHTENMENT_BASIS : null;
        if (basis == null)
          yield new Planned(
              change, target, from, to, null, null, CombatPowerReasons.MODEL_UNCONFIRMED.text());
        if (point.value() == null)
          yield new Planned(
              change,
              target,
              from,
              to,
              null,
              null,
              LEAP.equals(point.name()) ? LEAP_UNREAD : PASSIVE_UNREAD);
        if (passiveFactor(point.name(), point.value()) == null
            || passiveFactor(point.name(), change.points()) == null)
          yield new Planned(change, target, from, to, null, null, ENLIGHTENMENT_OVER_REASON);
        yield new Planned(
            change,
            target,
            from,
            to,
            new PassiveEffect(point.name(), point.value(), change.points()),
            basis,
            null);
      }
      case ARM_GUARD -> armGuard(loadout, refineModel, change);
      case ARK_GRID_CORE -> {
        range("point", change.point(), 0, 20);
        if (change.grade() == null || !CORE_GRADES.contains(change.grade()))
          throw invalid("grade는 " + CORE_GRADES + " 중 하나여야 합니다.");
        var slot =
            loadout.arkGrid() == null
                ? null
                : loadout.arkGrid().slots().stream()
                    .filter(
                        item ->
                            item != null
                                && change.slotIndex() != null
                                && change.slotIndex().equals(item.index()))
                    .findFirst()
                    .orElse(null);
        if (slot == null) throw notFound("아크 그리드 slotIndex " + change.slotIndex());
        var target =
            new CharacterLoadout.GridSlot(
                slot.index(),
                slot.icon(),
                slot.name(),
                change.point(),
                change.grade(),
                slot.gems(),
                slot.core(),
                slot.lines());
        // 같은 코어 표(아크 그리드 실 효율 #368)로 지금·바꾼 뒤 C를 구한다. 혼돈의 별 : 무기·확인하지 않은 코어는 그 이유로 계산하지 않는다.
        var now = CombatPowerArkGridModel.core(arkGridCoefficients, slot);
        var next = CombatPowerArkGridModel.core(arkGridCoefficients, target);
        var reason = now.reason() != null ? now.reason() : next.reason();
        yield new Planned(
            change,
            slot.name(),
            slot.grade() + " " + slot.point() + "P",
            change.grade() + " " + change.point() + "P",
            reason == null ? new ArkGridCoreEffect(slot.index(), now.pct(), next.pct()) : null,
            ARK_GRID_BASIS,
            reason);
      }
      case ARK_GRID_GEM -> arkGridGem(loadout, change);
    };
  }

  /**
   * 젬 종류 → 그 종류가 가질 수 있는 옵션 4종(공식 확률 정보 '젬 가공, 젬 융합' 2025-08-20, 공지 13204). 질서 코어는 안정·견고·불변, 혼돈 코어는
   * 침식·왜곡·붕괴.
   */
  static final Map<String, List<String>> GEM_TYPE_OPTIONS =
      Map.of(
          "안정", List.of("공격력", "추가 피해", "아군 피해 강화", "낙인력"),
          "침식", List.of("공격력", "추가 피해", "아군 피해 강화", "낙인력"),
          "견고", List.of("공격력", "보스 피해", "아군 피해 강화", "아군 공격 강화"),
          "왜곡", List.of("공격력", "보스 피해", "아군 피해 강화", "아군 공격 강화"),
          "불변", List.of("추가 피해", "보스 피해", "낙인력", "아군 공격 강화"),
          "붕괴", List.of("추가 피해", "보스 피해", "낙인력", "아군 공격 강화"));

  /** 젬 종류 → 기본 필요 의지력(공지 13204: 안정·침식 8, 견고·왜곡 9, 불변·붕괴 10). 필요 의지력 = 기본 − 의지력 효율. */
  static final Map<String, Integer> GEM_TYPE_BASE_WILL =
      Map.of("안정", 8, "침식", 8, "견고", 9, "왜곡", 9, "불변", 10, "붕괴", 10);

  private static final List<String> ORDER_GEM_TYPES = List.of("안정", "견고", "불변");
  private static final List<String> CHAOS_GEM_TYPES = List.of("침식", "왜곡", "붕괴");

  /**
   * 아크 그리드 젬 교체(M49 G1, 계산 검증 11-22 명세). 코어(slotIndex = 공식 Slots Index)의 젬(index = 공식 Gems Index)을
   * 새 젬으로 바꾼다. 바꾼 뒤 그 코어의 활성 젬 필요 의지력 합이 공급 의지력을 넘으면 어느 젬이 꺼질지 공식 문구가 없어 계산하지
   * 않는다(ARK_GRID_WILL_SHORT).
   */
  private Planned arkGridGem(CharacterLoadout loadout, Change change) {
    var swap = change.gem();
    if (swap == null) throw invalid("ARK_GRID_GEM에는 gemType·willEfficiency·effects가 있어야 합니다.");
    // 빠지거나 음수인 칸 번호는 요청 오류(400)다. 없는 칸은 409(#1079 코드 리뷰 P3).
    range("slotIndex", change.slotIndex(), 0, 99);
    range("index", change.index(), 0, 99);
    range("point", change.point(), 1, 5);
    range("willEfficiency", swap.willEfficiency(), 1, 5);
    var pool = swap.gemType() == null ? null : GEM_TYPE_OPTIONS.get(swap.gemType());
    if (pool == null) throw invalid("gemType은 " + GEM_TYPE_BASE_WILL.keySet() + " 중 하나여야 합니다.");
    if (swap.effects() == null || swap.effects().size() != 2)
      throw invalid("effects는 옵션 두 줄이어야 합니다.");
    var options = new LinkedHashMap<String, Integer>();
    for (var effect : swap.effects()) {
      if (effect == null || effect.name() == null || !pool.contains(effect.name().strip()))
        throw invalid(swap.gemType() + " 젬의 옵션은 " + pool + " 중 하나여야 합니다.");
      range("effects.level", effect.level(), 1, 5);
      if (options.put(effect.name().strip(), effect.level()) != null)
        throw invalid("effects의 두 옵션은 서로 달라야 합니다.");
    }
    var grid = loadout.arkGrid();
    var slot =
        grid == null
            ? null
            : grid.slots().stream()
                .filter(item -> item != null && Objects.equals(change.slotIndex(), item.index()))
                .findFirst()
                .orElse(null);
    if (slot == null) throw notFound("아크 그리드 slotIndex " + change.slotIndex());
    var series = slot.name() == null ? "" : slot.name().strip();
    var allowed =
        series.startsWith("질서")
            ? ORDER_GEM_TYPES
            : series.startsWith("혼돈") ? CHAOS_GEM_TYPES : List.<String>of();
    if (!allowed.contains(swap.gemType()))
      throw invalid("이 코어(" + slot.name() + ")에는 " + allowed + " 젬만 넣을 수 있습니다.");
    var old =
        slot.gems() == null
            ? null
            : slot.gems().stream()
                .filter(item -> item != null && Objects.equals(change.index(), item.index()))
                .findFirst()
                .orElse(null);
    if (old == null)
      throw notFound("아크 그리드 slotIndex " + change.slotIndex() + " 젬 index " + change.index());
    var target = slot.name() + " 젬 " + change.index();
    var from = gemText(old);
    var to =
        swap.gemType()
            + " "
            + change.point()
            + "P "
            + String.join(
                " · ",
                options.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList());
    var unreadable = gemUnreadable(slot, old, grid);
    if (unreadable != null)
      return new Planned(change, target, from, to, null, ARK_GRID_BASIS, unreadable);
    // 의지력은 같은 요청의 그 코어 교체를 모두 반영한 뒤에 본다(compute의 arkGemBlocked, #1079 코드 리뷰).
    return new Planned(
        change,
        target,
        from,
        to,
        new ArkGridGemEffect(
            slot,
            old,
            change.point(),
            Map.copyOf(options),
            grid.effects(),
            GEM_TYPE_BASE_WILL.get(swap.gemType()) - swap.willEfficiency()),
        ARK_GRID_BASIS,
        null);
  }

  private static final String ARK_GRID_GEM_CORE_CONFLICT =
      "같은 코어의 등급·포인트 변경과 젬 교체를 함께 계산하지 않았습니다. 하나씩 확인해 주세요.";

  /**
   * 젬 교체를 계산하지 않을 이유(코어 index → 이유). 같은 요청의 그 코어 교체를 모두 반영한 필요 의지력 합(바꾸지 않은 활성 젬 + 새 젬)이 공급 의지력을
   * 넘으면 ARK_GRID_WILL_SHORT(어느 젬이 꺼질지 공식 문구가 없다), 같은 코어의 ARK_GRID_CORE와 함께 오면 그 조합은 계산하지 않는다.
   */
  private static Map<Integer, String> arkGemBlocked(java.util.Collection<Planned> merged) {
    var swaps = new LinkedHashMap<Integer, List<ArkGridGemEffect>>();
    var cores = new java.util.HashSet<Integer>();
    for (var planned : merged) {
      if (planned.effect() instanceof ArkGridGemEffect gem)
        swaps.computeIfAbsent(gem.core().index(), key -> new ArrayList<>()).add(gem);
      if (planned.effect() instanceof ArkGridCoreEffect core) cores.add(core.slot());
    }
    var blocked = new LinkedHashMap<Integer, String>();
    for (var entry : swaps.entrySet()) {
      if (cores.contains(entry.getKey())) {
        blocked.put(entry.getKey(), ARK_GRID_GEM_CORE_CONFLICT);
        continue;
      }
      var core = entry.getValue().getFirst().core();
      var replaced = new java.util.HashSet<Integer>();
      int required = 0;
      for (var swap : entry.getValue()) {
        replaced.add(swap.old().index());
        required += swap.required();
      }
      for (var gem : core.gems())
        if (gem != null && !replaced.contains(gem.index()) && Boolean.TRUE.equals(gem.active()))
          required += gem.detail().requiredWillpower();
      if (required > core.core().supplyWillpower())
        blocked.put(entry.getKey(), CombatPowerReasons.ARK_GRID_WILL_SHORT.text());
    }
    return blocked;
  }

  /** 젬 교체에 필요한 공식 값(활성 여부, 포인트, 필요 의지력, 옵션, 코어 공급 의지력, 옵션 합)을 읽지 못했으면 이유. */
  private static String gemUnreadable(
      CharacterLoadout.GridSlot slot, CharacterLoadout.GridGem old, CharacterLoadout.ArkGrid grid) {
    if (slot.point() == null || slot.core() == null || slot.core().supplyWillpower() == null)
      return GEM_UNREADABLE;
    if (grid.effects() == null || grid.effects().isEmpty()) return GEM_UNREADABLE;
    for (var gem : slot.gems()) {
      if (gem == null || gem.active() == null) return GEM_UNREADABLE;
      if (!Boolean.TRUE.equals(gem.active())) continue;
      var detail = gem.detail();
      if (detail == null
          || detail.corePoint() == null
          || detail.requiredWillpower() == null
          || detail.options() == null) return GEM_UNREADABLE;
      for (var option : detail.options())
        if (option == null || option.name() == null || option.level() == null)
          return GEM_UNREADABLE;
    }
    return null;
  }

  private static String gemText(CharacterLoadout.GridGem gem) {
    var detail = gem.detail();
    if (detail == null) return "젬 " + gem.index();
    var options =
        detail.options() == null
            ? ""
            : String.join(
                " · ",
                detail.options().stream()
                    .filter(Objects::nonNull)
                    .map(option -> option.name() + " " + option.level())
                    .toList());
    return (detail.name() == null ? "" : detail.name().replaceFirst("^.*: ", "") + " ")
        + (detail.corePoint() == null ? "" : detail.corePoint() + "P ")
        + options;
  }

  private static final String GEM_UNREADABLE = "젬 포인트·필요 의지력·옵션을 읽지 못해 계산하지 않았습니다.";

  /**
   * 방어구 재련·상급 재련·팔 보호구처럼 전투력을 아직 계산하지 않는(UNSUPPORTED, 주 스탯 경로 확인 전) 변경인지. heldReason과 같은 규칙(팔찌 줄은
   * 빼고). 스펙업 KRAKEN 추천 순서는 이 단계를 점수로 고르고 전투력은 비운다(PM 총괄 11-21 (e)).
   */
  public static boolean armorClosed(CharacterLoadout loadout, Change change) {
    if (change == null) return false;
    if (change.kind() == Kind.ARM_GUARD) return true;
    if (change.kind() != Kind.EQUIPMENT_REFINE && change.kind() != Kind.EQUIPMENT_ADVANCED_REFINE)
      return false;
    var index = change.index();
    if (loadout == null || index == null || index < 0 || index >= loadout.equipment().size())
      return false;
    var item = loadout.equipment().get(index);
    return item != null && !"무기".equals(item.type());
  }

  /**
   * 이 스냅샷에서 이 변경의 전투력을 아직 내지 않는지(방어구 재련·상급 재련·완갑이고 문이 열리지 않음). 스펙업 추천 순서 KRAKEN이 전투력을 비울 단계를 고를 때
   * 쓴다(문이 열리면 전투력을 그대로 잰다).
   */
  public boolean armorClosed(CharacterResult snapshot, Change change) {
    if (!armorClosed(snapshot.details().loadout(), change)) return false;
    return armorGate.state(snapshot) != ArmorCpGate.State.OPEN;
  }

  /** 방어구 전투력 문 스위치가 켜졌는지(캐시 키·규칙 버전에 쓴다). */
  public boolean armorGateEnabled() {
    return armorGate.enabled();
  }

  /**
   * 응답에 쓰는 규칙 버전: 방어구 전투력 문 스위치가 켜지면 '-acg'를 붙인다(같은 버전에 다른 값이 나오지 않게, 코드 리뷰 #1036 P2-5). 꺼져 있으면 지금과
   * 같다. 웹은 이 값을 비교하지 않는다(PM-프론트 11-21).
   */
  public String ruleVersion() {
    return armorGate.enabled() ? RULE_VERSION + "-acg" : RULE_VERSION;
  }

  /** 스펙업 추천 순서가 방어구 단계에서 전투력을 비울 때의 이유 코드: 문 밖이면 ARMOR_CP_RESIDUAL_HIGH, 그 밖은 계산식 확인 전. */
  public String armorStopReason(CharacterResult snapshot) {
    return armorGate.state(snapshot) == ArmorCpGate.State.RESIDUAL_HIGH
        ? com.loakraken.character.domain.CombatPowerReasons.ARMOR_CP_RESIDUAL_HIGH.code()
        : "ARMOR_REFINE_CP_UNCONFIRMED";
  }

  /** 확인 전이라 계산하지 않는 변경의 이유(방어구 재련·상급 재련, 팔 보호구, 팔찌 힘·민첩·지능 줄). 그 밖이면 null. */
  private static String heldReason(
      CharacterLoadout loadout, CombatPowerBraceletModel.Model braceletModel, Planned planned) {
    var change = planned.change();
    if (change.kind() == Kind.ARM_GUARD) return ARM_GUARD_UNCONFIRMED;
    // 새 팔찌 옵션은 특수 옵션뿐이라, 주 스탯 경로는 지금 줄이 힘·민첩·지능일 때(바꾸거나 뺄 때)만 탄다.
    if (planned.effect() instanceof BraceletEffect line
        && braceletModel != null
        && line.line() < braceletModel.lines().size()
        && braceletModel.lines().get(line.line()).kind() == CombatPowerBraceletModel.Kind.MAIN_STAT)
      return BRACELET_MAIN_STAT_UNCONFIRMED;
    if (change.kind() != Kind.EQUIPMENT_REFINE && change.kind() != Kind.EQUIPMENT_ADVANCED_REFINE)
      return null;
    return "무기".equals(equipment(loadout, change.index()).type()) ? null : ARMOR_REFINE_UNCONFIRMED;
  }

  /** 재련 모델이 아는 부위의 지금 단계. 모르는 부위면 (0, 0)(그 변경은 cannotSet 이유로 계산하지 않는다). */
  private static CombatPowerRefineModel.Target currentTarget(
      CombatPowerRefineModel.Model refineModel, Integer index) {
    var part = index == null ? null : refineModel.part(index);
    return part == null
        ? new CombatPowerRefineModel.Target(0, 0)
        : new CombatPowerRefineModel.Target(part.level(), part.advanced());
  }

  private Planned armGuard(
      CharacterLoadout loadout, CombatPowerRefineModel.Model refineModel, Change change) {
    range("level", change.level(), 0, 25);
    if (change.grade() == null || !ARM_GUARD_GRADES.contains(change.grade()))
      throw invalid("grade는 " + ARM_GUARD_GRADES + " 중 하나여야 합니다.");
    var item =
        loadout.equipment().stream()
            .filter(equipment -> equipment != null && "완갑".equals(equipment.type()))
            .findFirst()
            .orElse(null);
    if (item == null) throw notFound("팔 보호구");
    var current =
        (item.detail() == null || item.detail().refineLevel() == null
                ? ""
                : "+" + item.detail().refineLevel() + " ")
            + item.grade();
    return new Planned(
        change,
        item.name(),
        current,
        "+" + change.level() + " " + change.grade(),
        new ArmGuardEffect(new CombatPowerRefineModel.ArmGuard(change.level(), change.grade())),
        ARM_GUARD_BASIS,
        refineModel.cannotSetArmGuard(change.level(), change.grade()));
  }

  private static CharacterLoadout.Equipment equipment(CharacterLoadout loadout, Integer index) {
    if (index == null
        || index < 0
        || index >= loadout.equipment().size()
        || loadout.equipment().get(index) == null) throw notFound("장비 index " + index);
    return loadout.equipment().get(index);
  }

  /**
   * 연마 수치 표(polish-values)에서 부위·옵션·등급의 공식 수치. 같은 부위에 같은 이름의 %·고정값 옵션이 둘 다 있으면(귀걸이 공격력·무기 공격력) % 쪽은
   * 옵션 끝에 " %"를 붙여 고른다(예: "공격력 %"). 없으면 null.
   */
  static final String POLISH_TABLE_MISMATCH = "이 장신구의 아이템 레벨·등급에 맞는 연마 수치표를 아직 확인하지 않아 계산하지 않았습니다.";

  private static final java.util.regex.Pattern TRADE_ITEM_LEVEL =
      java.util.regex.Pattern.compile("거래 제한 아이템 레벨\\s*([0-9,]+)");

  /**
   * 장신구가 연마 수치표(등급·아이템 레벨)와 같은 장신구인지. 공식 장신구 툴팁에는 '아이템 레벨 N' 줄이 없고 거래 가능한 장신구에만 '거래 제한 아이템 레벨 N' 줄이
   * 있다(계산 검증 원문 확인 10-25). 그래서 등급이 같고, (1) 거래 제한 아이템 레벨 줄이 있으면 그 레벨이 같을 때, (2) 없으면(거래 불가) 지금 연마 줄
   * 값이 모두 이 수치표의 그 부위 값일 때만 같은 장신구로 본다. 그 밖(모르면)은 아님.
   */
  public static boolean polishTableFits(
      CombatPowerPolishValues polishValues, CharacterLoadout.Equipment item) {
    if (polishValues.accessoryGrade() == null
        || !polishValues.accessoryGrade().equals(item.grade())
        || polishValues.itemLevel() == null
        || item.detail() == null) return false;
    var detail = item.detail();
    if (detail.itemLevel() != null) return polishValues.itemLevel().equals(detail.itemLevel());
    for (var line : detail.tradeLines() == null ? List.<String>of() : detail.tradeLines()) {
      var matcher = TRADE_ITEM_LEVEL.matcher(line == null ? "" : line);
      if (matcher.find())
        return polishValues.itemLevel().equals(Integer.valueOf(matcher.group(1).replace(",", "")));
    }
    if (detail.polishEffects().isEmpty()) return false;
    for (var polish : detail.polishEffects())
      if (polish == null || !tableHasValue(polishValues, item.type(), polish)) return false;
    return true;
  }

  /** 지금 연마 줄(이름·값)이 수치표의 그 부위 하·중·상 값 가운데 하나인지. */
  private static boolean tableHasValue(
      CombatPowerPolishValues polishValues, String part, CharacterLoadout.PolishEffect polish) {
    if (polish.name() == null || polish.value() == null) return false;
    var value = polish.value().strip();
    return polishValues.options().stream()
        .filter(row -> row.option().equals(polish.name()) && row.parts().contains(part))
        .anyMatch(
            row -> value.equals(row.low()) || value.equals(row.mid()) || value.equals(row.high()));
  }

  static String polishValue(
      CombatPowerPolishValues polishValues,
      String part,
      String option,
      String grade,
      CharacterLoadout.PolishEffect current) {
    var name = optionName(option);
    // 같은 줄의 등급만 바꾸면(웹 시뮬레이터) 옵션 이름이 지금 줄과 같다. 그때는 지금 줄 값이 %인지로 고르고, 옵션을 바꿀 때만 끝의 " %" 표시를 본다.
    boolean percent =
        current != null && name.equals(current.name()) && !option.strip().endsWith("%")
            ? current.value() != null && current.value().strip().endsWith("%")
            : option.strip().endsWith("%");
    var matches =
        polishValues.options().stream()
            .filter(value -> value.option().equals(name) && value.parts().contains(part))
            .toList();
    var value =
        matches.size() == 1
            ? matches.getFirst()
            : matches.stream().filter(item -> item.percent() == percent).findFirst().orElse(null);
    if (value == null) return null;
    return switch (grade) {
      case "LOW" -> value.low();
      case "MID" -> value.mid();
      case "HIGH" -> value.high();
      default -> null;
    };
  }

  private static boolean touchesWeapon(CombatPowerPolishModel.Line line) {
    return line.weaponFlat().signum() != 0 || line.weaponPct().signum() != 0;
  }

  /** 옵션 이름에서 % 구분 표시(" %")를 뗀다. */
  private static String optionName(String option) {
    var name = option.strip();
    return name.endsWith("%") ? name.substring(0, name.length() - 1).strip() : name;
  }

  /** 깨달음 카르마 레벨 최대(공식 가이드 아크 패시브: 랭크마다 레벨 1~30). */
  static final int KARMA_MAX_LEVEL = 30;

  private static final String KARMA_BASIS =
      "깨달음 카르마 레벨마다 무기 공격력 +0.1%(전투력 W 항 안, 세 곳 일치)를 바꾼 비율을 공식 전투력에 곱했습니다.";
  private static final String KARMA_UNREAD = "깨달음 카르마 레벨을 읽지 못해 계산하지 않았습니다.";

  private static final String KARMA_OFF = "깨달음 카르마 레벨 전투력 계산은 아직 켜지 않아 계산하지 않았습니다.";

  /** 깨달음 카르마 레벨 계산 스위치(spec-up.karma-cp.enabled, 기본 꺼짐). 스펙업 줄·단계와 /simulate가 같은 스위치를 본다. */
  private volatile boolean karmaCpEnabled;

  @org.springframework.beans.factory.annotation.Value("${spec-up.karma-cp.enabled:false}")
  void setKarmaCpEnabled(boolean enabled) {
    this.karmaCpEnabled = enabled;
  }

  /**
   * 깨달음 카르마 레벨 바꾸기: ARK_PASSIVE_POINT(tree 깨달음)에 karmaLevel을 넣는다(points·level 없음). 새 kind를 만들지 않아
   * 응답의 kind 값이 늘지 않고, 깨달음 포인트(points)와 헷갈리지 않게 자기 필드를 쓴다(PM 총괄 11-19).
   */
  public static Change karmaLevelChange(int level) {
    return new Change(
        Kind.ARK_PASSIVE_POINT,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        ENLIGHTENMENT,
        null,
        null,
        null,
        level);
  }

  /** 깨달음 카르마 레벨 변경인가(ARK_PASSIVE_POINT + karmaLevel). */
  public static boolean karmaChange(Change change) {
    return change.kind() == Kind.ARK_PASSIVE_POINT && change.karmaLevel() != null;
  }

  /**
   * 깨달음 카르마 레벨을 level로(0~30). 딜러가 아니면 compute가 역할 이유로 거른다(서포터 식은 비교 기준과 달라 계산하지 않음, PM 총괄 11-15).
   */
  private static Planned karma(
      CharacterLoadout loadout,
      CombatPowerRefineModel.Model refineModel,
      Change change,
      Integer level,
      boolean enabled) {
    range("karmaLevel", level, 0, KARMA_MAX_LEVEL);
    var target = "깨달음 카르마";
    var to = String.valueOf(level);
    // 스위치(spec-up.karma-cp.enabled)가 꺼져 있으면 시뮬레이터에 바로 넣어도 계산하지 않는다(검증 전 값이 나가지 않게, 코드 리뷰 #1004 P2).
    if (!enabled) return new Planned(change, target, null, to, null, null, KARMA_OFF);
    // 아크 패시브나 깨달음 포인트 줄이 없으면 카르마 레벨을 읽은 것이 아니다(0으로 보고 +3%를 내지 않게, 코드 리뷰 #1004 P3·재실행 P2).
    var current = CombatPowerRefineModel.observedKarmaLevel(loadout);
    if (current == null) return new Planned(change, target, null, to, null, null, KARMA_UNREAD);
    var from = String.valueOf(current);
    if (refineModel.unsupportedReason() != null)
      return new Planned(change, target, from, to, null, null, refineModel.unsupportedReason());
    return new Planned(
        change, target, from, to, new KarmaEffect(current, level), KARMA_BASIS, null);
  }

  private static Planned unsupported(Change change, String target, String from, String to) {
    return new Planned(change, target, from, to, null, null, UNSUPPORTED_BASIS);
  }

  /** 품질(#275): 무기는 추가 피해 배수, 방어구는 배수 1, 그 밖(장신구 등)은 아직 계산하지 않는다. */
  private Planned quality(
      CharacterLoadout.Equipment item, CharacterLoadout.EquipmentDetail detail, Change change) {
    var from = str(detail.quality());
    var to = str(change.quality());
    if (CombatPowerEquipmentModel.ARMOR.contains(item.type()))
      return new Planned(
          change, item.name(), from, to, new NeutralEffect(), ARMOR_QUALITY_BASIS, null);
    if (!"무기".equals(item.type())) return unsupported(change, item.name(), from, to);
    // 추가 피해 = 10 + 20 × (품질/100)²(0~100 전부). 품질이 있는 무기만 여기까지 온다.
    var next = CombatPowerEquipmentModel.weaponAddDamage(change.quality());
    String reason =
        CombatPowerEquipmentModel.currentWeaponAddDamage(detail) == null || next == null
            ? "무기 품질을 읽지 못해 계산하지 않았습니다."
            : null;
    return new Planned(
        change, item.name(), from, to, new WeaponQualityEffect(next), WEAPON_QUALITY_BASIS, reason);
  }

  /** 각인·스톤(#275): 계수표가 있는 유물 각인만, 현재 공식 설명값이 계수표와 같을 때만 계산한다. */
  private Planned engraving(
      Change change,
      String target,
      String from,
      EngravingEffect effect,
      CharacterLoadout.PassiveEngraving current) {
    var to = str(change.level());
    String reason = null;
    if (!CombatPowerEquipmentModel.supportsEngraving(equipmentCoefficients, effect.name()))
      reason = UNSUPPORTED_BASIS;
    else if (current == null) reason = "각인 정보가 없어 계산하지 않았습니다.";
    else if (current.level() != null
        && CombatPowerEquipmentModel.engravingFactor(
                equipmentCoefficients,
                effect.name(),
                current.level(),
                current.abilityStoneLevel() == null ? 0 : current.abilityStoneLevel())
            == null) reason = LEVEL_UNCONFIRMED_BASIS;
    else if (!CombatPowerEquipmentModel.matchesOfficial(equipmentCoefficients, current))
      reason = "공식 각인 설명값이 계수표와 달라 계산하지 않았습니다.";
    return new Planned(change, target, from, to, effect, ENGRAVING_BASIS, reason);
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static void range(String field, Integer value, int min, int max) {
    if (value == null || value < min || value > max)
      throw invalid(field + "는 " + min + "~" + max + " 범위여야 합니다.");
  }

  private static void grade(String grade) {
    if (grade == null || !GRADES.contains(grade)) throw invalid("grade는 LOW·MID·HIGH 중 하나여야 합니다.");
  }

  private static void text(String field, String value) {
    if (value == null || value.isBlank()) throw invalid(field + "가 필요합니다.");
  }

  /** 범위 밖·필수 값 없음: 400 INVALID_REQUEST(메시지에 필드와 허용 범위). */
  static BusinessException invalid(String message) {
    return new BusinessException(new Detailed("INVALID_REQUEST", HttpStatus.BAD_REQUEST, message));
  }

  /** 캐시가 바뀌어 바꿀 대상이 캐릭터에 없음: 409 CHANGE_TARGET_NOT_FOUND(웹은 다시 불러오기를 띄움). */
  static BusinessException notFound(String target) {
    return new BusinessException(
        new Detailed(
            CharacterErrorCode.CHANGE_TARGET_NOT_FOUND.code(),
            CharacterErrorCode.CHANGE_TARGET_NOT_FOUND.httpStatus(),
            CharacterErrorCode.CHANGE_TARGET_NOT_FOUND.message() + " (" + target + ")"));
  }

  record Detailed(String code, HttpStatus httpStatus, String message) implements ErrorCode {}

  /** 입력 범위(계약 1절). 숫자 범위는 공식 근거 확인 전 예시라 verified=false다(B단계에서 공식 표로 채움). */
  public record Options(
      String ruleVersion,
      boolean verified,
      List<Integer> gemLevels,
      int refineMin,
      int refineMax,
      int advancedRefineMax,
      int qualityMax,
      List<Integer> engravingLevels,
      List<Integer> stoneLevels,
      List<String> arkPassiveTrees,
      List<String> coreGrades,
      int corePointMax,
      List<String> polishGrades,
      CombatPowerPolishValues polishValues,
      List<BraceletOption> braceletOptions,
      List<String> notes) {}

  /** 팔찌 옵션 하나(#327): BRACELET_EFFECT의 name으로 쓰는 계열 이름과 등급별 공식 문구. 빼기는 name "없음". */
  public record BraceletOption(String name, Map<String, String> grades) {}

  public Options options() {
    return new Options(
        ruleVersion(),
        false,
        List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
        10,
        25,
        40,
        100,
        List.of(0, 1, 2, 3, 4),
        List.of(0, 1, 2, 3, 4),
        List.of("진화", "깨달음", "도약"),
        CORE_GRADES,
        20,
        List.of("LOW", "MID", "HIGH"),
        polishValues,
        braceletCoefficients.families().stream()
            .map(
                family ->
                    new BraceletOption(
                        family.name(),
                        Map.of(
                            "LOW", family.text("LOW"),
                            "MID", family.text("MID"),
                            "HIGH", family.text("HIGH"))))
            .toList(),
        List.of(
            "숫자 범위는 B단계에서 공식 근거로 확정합니다(verified=false).",
            "연마 수치(polishValues)는 공식 장비 툴팁 표본으로 확인한 고대 장신구 값이고, 확인하지 못한 칸은 null입니다.",
            "팔찌 효과(BRACELET_EFFECT)의 name은 braceletOptions의 계열 이름이고, 그 줄을 빼려면 \"없음\"입니다. 전투 특성(치명·특화·신속) 줄 교체는 아직 계산하지 않습니다."));
  }
}
