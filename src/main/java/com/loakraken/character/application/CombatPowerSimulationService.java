package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.api.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 전투력 시뮬레이터(계약 v0, A단계). 볼트 「전투력 시뮬레이터 API 계약 초안」·PM-백엔드 18:27·PM-프론트 18:31 교차 확인.
 *
 * <p><b>공식 API를 부르지 않는다</b>: 캐릭터 캐시만 읽고, 없으면 CHARACTER_NOT_CACHED(404)다. 바꾼 항목(changes)을 검증하고 순서대로
 * 합친다. 공개 제외 캐릭터는 캐시가 없을 때와 같은 404다. 전투력 계산식은 계산 검증의 목표 사이트 실측 대조(Q1·Q2)로 항목별로 확정되기 전까지 모든 항목을
 * UNSUPPORTED로 값 없이 낸다(추정 금지). 그래서 이 단계의 after는 공식 전투력 그대로이고 partial=true다.
 */
@Service
public class CombatPowerSimulationService {
  public static final String RULE_VERSION = "combat-power-sim-v0";
  private static final Set<String> GRADES = Set.of("LOW", "MID", "HIGH");
  private static final List<String> CORE_GRADES = List.of("영웅", "전설", "유물", "고대");
  private static final Set<String> WEAPON_ARMOR = Set.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final String UNSUPPORTED_BASIS = "전투력 계산식이 아직 확정되지 않았습니다(목표 사이트 실측 대조 대기).";

  private final CharacterCache cache;
  private final CharacterPublicSummaryPort stored;

  public CombatPowerSimulationService(CharacterCache cache, CharacterPublicSummaryPort stored) {
    this.cache = cache;
    this.stored = stored;
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
    // 같은 대상은 뒤의 변경이 이긴다. 순서는 처음 나온 자리를 지킨다.
    var merged = new LinkedHashMap<String, Contribution>();
    for (var change : changes) {
      var contribution = describe(loadout, change);
      merged.put(key(change), contribution);
    }
    var contributions = List.copyOf(merged.values());
    var official = result.details().profile().combatPower();
    // 반올림은 서버에서 끝낸다(PM-프론트 18:31 (g)): 전투력 소수 둘째 자리, deltaPercent 소수 셋째 자리.
    var before = official == null ? null : official.setScale(2, RoundingMode.HALF_UP);
    return new Result(
        result.details().profile().characterName(),
        result.fetchedAt(),
        before,
        null,
        before,
        before == null ? null : BigDecimal.ZERO.setScale(2),
        before == null ? null : BigDecimal.ZERO.setScale(3),
        !contributions.isEmpty(),
        contributions,
        contributions.size(),
        List.of(
            "현재 세팅은 캐릭터 캐시(수집 시각 fetchedAt)를 기준으로 합니다.",
            "before는 공식 전투력입니다. 계산식이 확정된 항목만 after에 반영하고, 나머지는 UNSUPPORTED로 값 없이 알립니다."));
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

  private static Contribution describe(CharacterLoadout loadout, Change change) {
    if (change.kind() == null) throw invalid("kind가 필요합니다.");
    return switch (change.kind()) {
      case GEM_LEVEL_ALL -> {
        range("level", change.level(), 1, 10);
        yield unsupported(change, "보석 전체", null, String.valueOf(change.level()));
      }
      case GEM_LEVEL -> {
        range("level", change.level(), 1, 10);
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
        yield unsupported(
            change,
            "보석 " + gem.slot() + "번(" + gem.name() + ")",
            str(gem.level()),
            str(change.level()));
      }
      case EQUIPMENT_REFINE, EQUIPMENT_ADVANCED_REFINE, EQUIPMENT_QUALITY -> {
        var item = equipment(loadout, change.index());
        var detail = item.detail();
        if (change.kind() == Kind.EQUIPMENT_QUALITY) {
          range("quality", change.quality(), 0, 100);
          if (detail == null || detail.quality() == null)
            throw notFound("품질이 있는 장비 index " + change.index());
          yield unsupported(change, item.name(), str(detail.quality()), str(change.quality()));
        }
        if (!WEAPON_ARMOR.contains(item.type()) || detail == null || detail.refineLevel() == null)
          throw notFound("재련 장비 index " + change.index());
        if (change.kind() == Kind.EQUIPMENT_REFINE) {
          range("refine", change.refine(), 10, 25);
          yield unsupported(change, item.name(), str(detail.refineLevel()), str(change.refine()));
        }
        range("level", change.level(), 0, 40);
        // 현재 상급 재련 단계(from)는 #234(advancedRefineLevel) 병합 뒤 채운다.
        yield unsupported(change, item.name(), null, str(change.level()));
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
        yield unsupported(change, engraving.name(), str(engraving.level()), str(change.level()));
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
        yield unsupported(change, "스톤 " + stone.name(), str(stone.level()), str(change.level()));
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

  private static Contribution unsupported(Change change, String target, String from, String to) {
    return new Contribution(
        change.kind().name(), target, from, to, null, Status.UNSUPPORTED, UNSUPPORTED_BASIS);
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
        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
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
