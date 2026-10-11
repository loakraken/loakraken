package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerEquipmentModel;
import com.loakraken.character.domain.CombatPowerGemModel;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 전투력 시뮬레이터(계약 v0, A단계). 볼트 「전투력 시뮬레이터 API 계약 초안」·PM-백엔드 18:27·PM-프론트 18:31 교차 확인.
 *
 * <p><b>공식 API를 부르지 않는다</b>: 캐릭터 캐시만 읽고, 없으면 CHARACTER_NOT_CACHED(404)다. 바꾼 항목(changes)을 검증하고 순서대로
 * 합친다. 공개 제외 캐릭터는 캐시가 없을 때와 같은 404다. 전투력 계산식은 계산 검증의 목표 사이트 실측 대조(Q1·Q2)로 항목별로 확정되기 전까지 그 항목을
 * UNSUPPORTED로 값 없이 낸다(추정 금지).
 *
 * <p>B단계(#243): 보석(GEM_LEVEL·GEM_LEVEL_ALL)을 계산한다({@link CombatPowerGemModel}). after = before ×
 * 모델(바뀐 세팅) / 모델(현재 세팅)이고, contributions[].delta는 순서대로 적용한 누적 전투력을 소수 둘째 자리로 반올림한 값의 차이라 합이 delta와
 * 같다.
 */
@Service
public class CombatPowerSimulationService {
  public static final String RULE_VERSION = "combat-power-sim-v2";
  private static final MathContext MATH = MathContext.DECIMAL64;
  private static final String GEM_BASIS =
      "보석 레벨별 배수와 기본 공격력 %(보석·스톤 레벨 보너스·완갑 추가 효과, 공식 툴팁)로 바뀐 비율을 공식 전투력에 곱했습니다.";
  private static final String NO_OFFICIAL_BASIS = "공식 전투력이 없어 계산하지 않습니다.";
  private static final String WEAPON_QUALITY_BASIS = "무기 품질의 추가 피해(공식 툴팁) 배수 비율을 공식 전투력에 곱했습니다.";
  private static final String ARMOR_QUALITY_BASIS = "방어구 품질은 체력에만 붙어 전투력이 바뀌지 않습니다.";
  private static final String ENGRAVING_BASIS = "각인 효과(공식 각인 설명값)의 배수 비율을 공식 전투력에 곱했습니다.";
  private static final String LEVEL_UNCONFIRMED_BASIS = "이 레벨 조합의 각인 수치를 아직 확인하지 않아 계산하지 않았습니다.";
  private static final Set<String> GRADES = Set.of("LOW", "MID", "HIGH");
  private static final List<String> CORE_GRADES = List.of("영웅", "전설", "유물", "고대");
  private static final Set<String> WEAPON_ARMOR = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final String UNSUPPORTED_BASIS = "이 항목의 전투력 계산식을 아직 확인하고 있어 계산하지 않았습니다.";

  private final CharacterCache cache;
  private final CharacterPublicSummaryPort stored;
  private final CombatPowerGemModel.Coefficients gemCoefficients;
  private final CombatPowerEquipmentModel.Coefficients equipmentCoefficients;

  public CombatPowerSimulationService(
      CharacterCache cache,
      CharacterPublicSummaryPort stored,
      CombatPowerGemModel.Coefficients gemCoefficients,
      CombatPowerEquipmentModel.Coefficients equipmentCoefficients) {
    this.cache = cache;
    this.stored = stored;
    this.gemCoefficients = gemCoefficients;
    this.equipmentCoefficients = equipmentCoefficients;
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
    ARK_GRID_CORE
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
      String basis) {}

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
    var name = new SearchCharacterCommand(characterName).characterName();
    CharacterResult result;
    try {
      result = cache.find(name).or(() -> cache.findArmory(name)).orElse(null);
    } catch (CharacterException exception) {
      result = null;
    }
    // 공개 제외 캐릭터는 캐시가 남아 있어도 세팅을 내주지 않는다(없는 캐릭터와 같은 404).
    try {
      if (result != null
          && stored.excluded(
              result.details().profile().characterName(), result.details().profile().serverName()))
        result = null;
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
    if (result == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED);
    var loadout = result.details().loadout();
    var gemModel = CombatPowerGemModel.of(gemCoefficients, loadout);
    // 같은 대상은 뒤의 변경이 이긴다. 순서는 처음 나온 자리를 지킨다.
    var merged = new LinkedHashMap<String, Planned>();
    for (var change : changes) merged.put(key(change), describe(loadout, gemModel, change));
    var official = result.details().profile().combatPower();
    // 반올림은 서버에서 끝낸다(PM-프론트 18:31 (g)): 전투력 소수 둘째 자리, deltaPercent 소수 셋째 자리.
    var before = official == null ? null : official.setScale(2, RoundingMode.HALF_UP);
    var engravings = currentEngravings(loadout);
    var weaponAddDamage = CombatPowerEquipmentModel.currentWeaponAddDamage(weaponDetail(loadout));
    var state = new State(Map.of(), null, Map.of());
    var running = before;
    var contributions = new ArrayList<Contribution>();
    int unsupported = 0;
    for (var planned : merged.values()) {
      String reason = planned.reason();
      if (planned.effect() != null && reason == null) {
        if (before == null) reason = NO_OFFICIAL_BASIS;
        else {
          var next = state.apply(planned.effect(), engravings);
          reason = stoneBonusReason(engravings, next);
          var ratio = reason == null ? ratio(gemModel, engravings, weaponAddDamage, next) : null;
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

  /** 각인 하나의 각인서·스톤 레벨. */
  private record EngravingLevels(int book, int stone) {}

  /** 지금까지 적용한 변경. 보석 칸 레벨, 무기 추가 피해 %(null이면 그대로), 각인별 레벨. */
  private record State(
      Map<Integer, Integer> gems,
      BigDecimal weaponAddDamage,
      Map<String, EngravingLevels> engravings) {
    State apply(Effect effect, Map<String, CharacterLoadout.PassiveEngraving> currents) {
      return switch (effect) {
        case GemEffect gem -> {
          var next = new HashMap<>(gems);
          next.putAll(gem.levels());
          yield new State(Map.copyOf(next), weaponAddDamage, engravings);
        }
        case WeaponQualityEffect quality -> new State(gems, quality.addDamage(), engravings);
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
          yield new State(gems, weaponAddDamage, Map.copyOf(next));
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
      return new Contribution(change.kind().name(), target, from, to, delta, Status.OK, okBasis);
    }

    Contribution unsupported(String why) {
      return new Contribution(
          change.kind().name(), target, from, to, null, Status.UNSUPPORTED, why);
    }
  }

  /**
   * 현재 세팅 대비 전투력 비. 모든 항목이 독립 배수라 항목 배수를 곱한다. 계산할 수 없는 조합이면 null. 각인 상태는 처음 레벨을 currents에서 가져온다.
   */
  private BigDecimal ratio(
      CombatPowerGemModel.Model gemModel,
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

  /** 스톤 레벨 합이 [레벨 보너스]가 그대로인 확인된 구간(5~7)을 벗어나면 이유 문장. 스톤을 바꾸지 않았으면 null. */
  private String stoneBonusReason(
      Map<String, CharacterLoadout.PassiveEngraving> currents, State state) {
    int before = 0;
    int after = 0;
    boolean changed = false;
    for (var engraving : currents.values()) {
      int stone = engraving.abilityStoneLevel() == null ? 0 : engraving.abilityStoneLevel();
      before += stone;
      var next = state.engravings().get(engraving.name());
      after += next == null ? stone : next.stone();
      if (next != null && next.stone() != stone) changed = true;
    }
    if (!changed
        || CombatPowerEquipmentModel.stoneBonusStable(equipmentCoefficients, before, after))
      return null;
    return "스톤 레벨 합이 "
        + before
        + "에서 "
        + after
        + "(으)로 바뀌어 [레벨 보너스] 변화를 아직 확인하지 않은 구간이라 계산하지 않았습니다.";
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
    };
  }

  private Planned describe(
      CharacterLoadout loadout, CombatPowerGemModel.Model gemModel, Change change) {
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
          yield unsupported(change, item.name(), str(detail.refineLevel()), str(change.refine()));
        }
        range("level", change.level(), 0, 40);
        yield unsupported(
            change, item.name(), str(detail.advancedRefineLevel()), str(change.level()));
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
        grade(change.grade());
        yield unsupported(
            change,
            "팔찌 효과 " + (change.line() + 1),
            bracelet.detail().braceletEffects().get(change.line()).text(),
            change.name() + " " + change.grade());
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
    String reason = null;
    if (CombatPowerEquipmentModel.currentWeaponAddDamage(detail) == null)
      reason = "무기 추가 피해(공식 툴팁)를 읽지 못해 계산하지 않았습니다.";
    var next = CombatPowerEquipmentModel.weaponAddDamage(equipmentCoefficients, change.quality());
    if (reason == null && next == null)
      reason = "품질 " + change.quality() + "의 무기 추가 피해 수치를 아직 확인하지 않아 계산하지 않았습니다.";
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
      List<String> notes) {}

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
        List.of(
            "숫자 범위는 B단계에서 공식 근거로 확정합니다(verified=false).",
            "연마 옵션별 상중하 수치와 팔찌 부여 효과 목록은 공식 확률표로 B단계에서 추가합니다."));
  }
}
