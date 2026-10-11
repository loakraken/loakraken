package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.CombatPowerBraceletModel;
import com.loakraken.character.domain.CombatPowerEquipmentModel;
import com.loakraken.character.domain.CombatPowerGemModel;
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
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 전투력 시뮬레이터(계약 v0, A단계). 볼트 「전투력 시뮬레이터 API 계약 초안」·PM-백엔드 18:27·PM-프론트 18:31 교차 확인.
 *
 * <p><b>공식 API를 부르지 않는다</b>: 캐릭터 캐시만 읽고, 없으면 CHARACTER_NOT_CACHED(404)다. 바꾼 항목(changes)을 검증하고 순서대로
 * 합친다. 처리정지 목록 캐릭터는 캐시가 없을 때와 같은 404다. 전투력 계산식은 계산 검증의 목표 사이트 실측 대조(Q1·Q2)로 항목별로 확정되기 전까지 그 항목을
 * UNSUPPORTED로 값 없이 낸다(추정 금지).
 *
 * <p>B단계(#243): 보석(GEM_LEVEL·GEM_LEVEL_ALL)을 계산한다({@link CombatPowerGemModel}). after = before ×
 * 모델(바뀐 세팅) / 모델(현재 세팅)이고, contributions[].delta는 순서대로 적용한 누적 전투력을 소수 둘째 자리로 반올림한 값의 차이라 합이 delta와
 * 같다.
 */
@Service
public class CombatPowerSimulationService {
  public static final String RULE_VERSION = "combat-power-sim-v8";
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
  private static final String BRACELET_BASIS =
      "팔찌 옵션의 전투력 배수(특수 옵션 계수·전투 특성 0.03%/점·고정 무기 공격력)를 바꾼 비율을 공식 전투력에 곱했습니다.";

  /** 서포터 전투력은 케어 + 버프로 식이 다르다(계산 검증 #331). 서포터 모델 구현 전에는 딜러 식을 쓰지 않는다(PM-백엔드 P0 10-11 20:23). */
  public static final String SUPPORTER_BASIS = CombatPowerReasons.SUPPORTER_MODEL_PENDING.text();

  public static final String ROLE_UNKNOWN_BASIS = CombatPowerReasons.ROLE_UNKNOWN.text();
  private static final String UNSUPPORTED_BASIS = "이 항목의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";

  private final CharacterCache cache;
  private final CharacterPublicSummaryPort stored;
  private final CombatPowerGemModel.Coefficients gemCoefficients;
  private final CombatPowerEquipmentModel.Coefficients equipmentCoefficients;
  private final CombatPowerPolishValues polishValues;
  private final CombatPowerRefineModel.Coefficients refineCoefficients;
  private final CombatPowerBraceletModel.Coefficients braceletCoefficients;

  public CombatPowerSimulationService(
      CharacterCache cache,
      CharacterPublicSummaryPort stored,
      CombatPowerGemModel.Coefficients gemCoefficients,
      CombatPowerEquipmentModel.Coefficients equipmentCoefficients,
      CombatPowerPolishValues polishValues,
      CombatPowerRefineModel.Coefficients refineCoefficients,
      CombatPowerBraceletModel.Coefficients braceletCoefficients) {
    this.cache = cache;
    this.stored = stored;
    this.gemCoefficients = gemCoefficients;
    this.equipmentCoefficients = equipmentCoefficients;
    this.polishValues = polishValues;
    this.refineCoefficients = refineCoefficients;
    this.braceletCoefficients = braceletCoefficients;
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
    ARM_GUARD
  }

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
      Integer point) {}

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
    // 같은 대상은 뒤의 변경이 이긴다. 순서는 처음 나온 자리를 지킨다.
    var merged = new LinkedHashMap<String, Planned>();
    for (var change : changes)
      merged.put(key(change), describe(loadout, gemModel, refineModel, braceletModel, change));
    var official = result.details().profile().combatPower();
    // 반올림은 서버에서 끝낸다(PM-프론트 18:31 (g)): 전투력 소수 둘째 자리, deltaPercent 소수 셋째 자리.
    var before = official == null ? null : official.setScale(2, RoundingMode.HALF_UP);
    var engravings = currentEngravings(loadout);
    var weaponAddDamage = CombatPowerEquipmentModel.currentWeaponAddDamage(weaponDetail(loadout));
    var state = new State(Map.of(), null, Map.of(), Map.of(), Map.of(), null);
    var running = before;
    var contributions = new ArrayList<Contribution>();
    int unsupported = 0;
    var roleReason = roleReason(result);
    for (var planned : merged.values()) {
      String reason = roleReason != null ? roleReason : planned.reason();
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

  /** 팔찌 효과 줄(#327): 줄 번호 → 새 줄(빼기 포함). */
  private record BraceletEffect(int line, CombatPowerBraceletModel.Line next) implements Effect {}

  /** 각인 하나의 각인서·스톤 레벨. */
  private record EngravingLevels(int book, int stone) {}

  /** 지금까지 적용한 변경. 보석 칸 레벨, 무기 추가 피해 %(null이면 그대로), 각인별 레벨. */
  private record State(
      Map<Integer, Integer> gems,
      BigDecimal weaponAddDamage,
      Map<String, EngravingLevels> engravings,
      Map<Integer, CombatPowerRefineModel.Target> refines,
      Map<Integer, CombatPowerBraceletModel.Line> bracelet,
      CombatPowerRefineModel.ArmGuard armGuard) {
    State apply(Effect effect, Map<String, CharacterLoadout.PassiveEngraving> currents) {
      return switch (effect) {
        case GemEffect gem -> {
          var next = new HashMap<>(gems);
          next.putAll(gem.levels());
          yield new State(
              Map.copyOf(next), weaponAddDamage, engravings, refines, bracelet, armGuard);
        }
        case WeaponQualityEffect quality ->
            new State(gems, quality.addDamage(), engravings, refines, bracelet, armGuard);
        case RefineEffect refine -> {
          var next = new HashMap<>(refines);
          var base = refines.getOrDefault(refine.index(), refine.current());
          next.put(
              refine.index(),
              new CombatPowerRefineModel.Target(
                  refine.level() != null ? refine.level() : base.level(),
                  refine.advanced() != null ? refine.advanced() : base.advanced()));
          yield new State(gems, weaponAddDamage, engravings, Map.copyOf(next), bracelet, armGuard);
        }
        case BraceletEffect line -> {
          var next = new HashMap<>(bracelet);
          next.put(line.line(), line.next());
          yield new State(gems, weaponAddDamage, engravings, refines, Map.copyOf(next), armGuard);
        }
        case ArmGuardEffect guard ->
            new State(gems, weaponAddDamage, engravings, refines, bracelet, guard.target());
        case NeutralEffect neutral -> this;
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
          yield new State(gems, weaponAddDamage, Map.copyOf(next), refines, bracelet, armGuard);
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
    // 재련 단계와 팔찌의 고정 무기 공격력은 같은 W 항을 쓴다.
    if (!state.refines().isEmpty()
        || weaponFlatDelta.signum() != 0
        || mainStatDelta.signum() != 0
        || state.armGuard() != null) {
      var refine =
          refineModel.ratioFull(state.refines(), weaponFlatDelta, mainStatDelta, state.armGuard());
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
      case ARK_PASSIVE_POINT -> "ARK_PASSIVE:" + change.tree();
      case ARK_GRID_CORE -> "ARK_GRID:" + change.slotIndex();
      case ARM_GUARD -> "ARM_GUARD";
    };
  }

  private Planned describe(
      CharacterLoadout loadout,
      CombatPowerGemModel.Model gemModel,
      CombatPowerRefineModel.Model refineModel,
      CombatPowerBraceletModel.Model braceletModel,
      Change change) {
    if (change.kind() == null) throw invalid("kind가 필요합니다.");
    return switch (change.kind()) {
      case GEM_LEVEL_ALL -> {
        range("level", change.level(), 0, 10);
        var targets = new LinkedHashMap<Integer, Integer>();
        String reason = gemModel.unsupportedReason();
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
        if (gem == null) throw notFound("보석 slot " + change.slot());
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
        text("option", change.option());
        grade(change.grade());
        var current = item.detail().polishEffects().get(change.line());
        yield unsupported(
            change,
            item.name() + " 연마 " + (change.line() + 1),
            current.name() + " " + current.value(),
            change.option() + " " + change.grade());
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
        yield unsupported(
            change, "아크 패시브 " + point.name(), str(point.value()), str(change.points()));
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
        yield unsupported(
            change,
            slot.name(),
            slot.grade() + " " + slot.point() + "P",
            change.grade() + " " + change.point() + "P");
      }
    };
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
        RULE_VERSION,
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
