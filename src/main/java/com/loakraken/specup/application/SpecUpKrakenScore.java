package com.loakraken.specup.application;

import com.loakraken.character.application.CharacterResult;
import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.CombatPowerRefineModel;
import com.loakraken.character.domain.GemBaseAttack;
import com.loakraken.character.domain.KrakenScoreEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 스펙업 후보 하나의 Δ크라켄 점수. 캐릭터 결과에 캐시된 점수 엔진 입력의 <b>사본</b>에 그 후보만 적용해 딜러 점수를 다시 계산한다(원본 입력은 그대로, 공식 호출
 * 없음). 바꾸는 곳은 엔진이 읽는 필드뿐이다: 보석 레벨·기본 공격력 %(순서·합 다시), 각인 레벨, 무기 품질, 재련 부위 스탯(전투력 재련 모델 단계표의 Δ), 깨달음
 * 카르마 레벨, 스톤 세공 레벨·레벨 보너스, 장신구 연마 줄(#575). 근거가 없으면(빈 칸 보석·서포터·상급 재련 등) null이고 근사하지 않는다.
 */
@Component
public class SpecUpKrakenScore {
  public static final String RULE_VERSION = "spec-up-kraken-v1";
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  /** 입력 매퍼와 같은 부위 키. */
  static final Map<String, String> PARTS =
      Map.of(
          "무기", "weapon",
          "투구", "helmet",
          "상의", "armor",
          "하의", "pants",
          "장갑", "gloves",
          "어깨", "shoulder");

  private final KrakenScoreEngine.Tables tables;
  private final CombatPowerRefineModel.Coefficients refine;
  private final boolean enabled;

  public SpecUpKrakenScore(
      KrakenScoreEngine.Tables tables,
      CombatPowerRefineModel.Coefficients refine,
      @Value("${kraken-score.enabled:false}") boolean enabled) {
    this.tables = tables;
    this.refine = refine;
    this.enabled = enabled;
  }

  /**
   * 캐릭터 하나의 계산 기준. unavailable이 있으면 모든 후보의 Δ점수는 null이고 KRAKEN 정렬은 효율 순으로 되돌린다. unavailable 값:
   * DISABLED(점수 기능 꺼짐), INPUT_PENDING(점수 입력이 없는 이전 조회), ROLE_UNKNOWN, SUPPORTER_PENDING(서포터는 준비 중),
   * SCORE_UNAVAILABLE(지금 점수를 낼 수 없음).
   */
  public record Context(JsonNode input, double before, String unavailable) {
    boolean available() {
      return unavailable == null;
    }

    /** 화면 점수(소수 둘째 자리). */
    BigDecimal shown() {
      return available() ? round2(before) : null;
    }
  }

  Context context(CharacterResult snapshot) {
    if (!enabled) return new Context(null, Double.NaN, "DISABLED");
    var input = snapshot.krakenScoreInput();
    if (input == null || !input.isObject()) return new Context(null, Double.NaN, "INPUT_PENDING");
    var details = snapshot.details();
    var position =
        CharacterPosition.judge(details.profile().className(), details.loadout().arkPassive());
    if (position.role() == null) return new Context(null, Double.NaN, "ROLE_UNKNOWN");
    if (position.role() == CharacterPosition.Role.SUPPORT)
      return new Context(null, Double.NaN, "SUPPORTER_PENDING");
    // 점수 서비스와 같은 조건: 무기·아크 패시브가 비면 엔진 기본값으로 0점이 나온다.
    if (!com.loakraken.character.application.KrakenScoreService.scorable(input))
      return new Context(null, Double.NaN, "SCORE_UNAVAILABLE");
    double before = score(input);
    if (!usable(before)) return new Context(null, Double.NaN, "SCORE_UNAVAILABLE");
    return new Context(input, before, null);
  }

  /**
   * 시뮬레이터 입력으로 나타낼 수 없는 후보(점수 입력에서 만든 후보)의 바꿀 값. Karma = 깨달음 카르마 레벨, Stone = 스톤 증가 각인 두 개의 세공 레벨(스톤
   * 줄 순서), Polish = 장신구 한 부위(입력 키 necklace·earing1·…)의 연마 줄 전체.
   */
  sealed interface Edit permits Karma, Stone, Polish {}

  record Karma(int level) implements Edit {}

  record Stone(Map<String, Integer> levels) implements Edit {}

  record Polish(String slot, List<String> option) implements Edit {}

  /** 후보 하나를 적용한 Δ점수(반올림 전). 계산할 수 없으면 null. */
  Double delta(Context context, CharacterLoadout loadout, Change change) {
    if (context == null || !context.available() || change == null || change.kind() == null)
      return null;
    var copy = (ObjectNode) context.input().deepCopy();
    boolean applied =
        switch (change.kind()) {
          case GEM_LEVEL -> gem(copy, loadout, change.slot(), change.level());
          case ENGRAVING_LEVEL -> engraving(copy, change.name(), change.level());
          case EQUIPMENT_QUALITY -> quality(copy, change.quality());
          case EQUIPMENT_REFINE -> refine(copy, loadout, change.index(), change.refine());
          case ARM_GUARD -> armGuard(copy, loadout, change.level(), change.grade());
          default -> false;
        };
    return rescore(context, copy, applied);
  }

  /** 점수 입력에서 만든 후보 하나를 적용한 Δ점수(반올림 전). 계산할 수 없으면 null. */
  Double delta(Context context, Edit edit) {
    if (context == null || !context.available() || edit == null) return null;
    var copy = (ObjectNode) context.input().deepCopy();
    boolean applied =
        switch (edit) {
          case Karma karma -> karma(copy, karma.level());
          case Stone stone -> stone(copy, stone.levels());
          case Polish polish -> polish(copy, polish.slot(), polish.option());
        };
    return rescore(context, copy, applied);
  }

  private Double rescore(Context context, ObjectNode copy, boolean applied) {
    if (!applied) return null;
    double after = score(copy);
    return usable(after) ? after - context.before() : null;
  }

  private double score(JsonNode input) {
    try {
      return KrakenScoreEngine.dealerScore(input, tables);
    } catch (RuntimeException exception) {
      return Double.NaN;
    }
  }

  private static boolean usable(double value) {
    return Double.isFinite(value) && value > 0;
  }

  static BigDecimal round2(double value) {
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * 보석 칸 slot을 level로. 입력 행은 {level, type, skill, attackBonus, valid}이고 슬롯이 없어 스킬·종류(딜·쿨)·지금 레벨로
   * 찾는다. 새 레벨의 기본 공격력 %는 출처가 확인된 표(GemBaseAttack, 4티어 6~10레벨), 순서는 입력 매퍼와 같게(딜 → 쿨, 레벨 높은 순, 같은 레벨은
   * 원래 행 순서), gem.attackBonus는 그 순서대로 다시 더한다(끝자리). 빈 칸은 붙는 스킬이 없어 계산하지 않는다.
   */
  static boolean gem(ObjectNode input, CharacterLoadout loadout, Integer slot, Integer level) {
    if (slot == null || level == null || loadout.gems() == null) return false;
    var current =
        loadout.gems().items().stream()
            .filter(item -> item != null && slot.equals(item.slot()))
            .findFirst()
            .orElse(null);
    if (current == null || current.level() == null) return false;
    String skill = null;
    String type = null;
    if (loadout.gems().effects() != null)
      for (var effect : loadout.gems().effects().skills())
        if (effect != null && slot.equals(effect.gemSlot())) {
          skill = effect.name();
          type =
              String.join(" ", effect.description()).contains("재사용 대기시간") ? "cooldown" : "damage";
          break;
        }
    if (skill == null) return false;
    var bonus =
        GemBaseAttack.of(
            new CharacterLoadout.Gem(
                current.slot(),
                current.name(),
                current.icon(),
                level,
                current.grade(),
                current.extraLines(),
                current.titleLine(),
                current.itemLevel(),
                current.tier(),
                current.tradeLines()),
            List.of());
    if (bonus == null) return false;
    var rows = input.path("gem").path("gems");
    if (!rows.isArray()) return false;
    ObjectNode target = null;
    for (var row : rows)
      if (skill.equals(row.path("skill").asString(null))
          && type.equals(row.path("type").asString(null))
          && row.path("level").asInt(-1) == current.level()) {
        if (target != null) return false; // 같은 행이 둘이면 어느 칸인지 모른다
        target = (ObjectNode) row;
      }
    if (target == null) return false;
    target.put("level", level);
    target.put("attackBonus", bonus.pct().doubleValue());
    var ordered = new ArrayList<JsonNode>();
    rows.forEach(ordered::add);
    // List.sort는 안정 정렬이라 같은 종류·레벨은 원래 행 순서가 남는다.
    ordered.sort(
        (a, b) -> {
          int byType =
              Integer.compare(
                  "cooldown".equals(a.path("type").asString(null)) ? 1 : 0,
                  "cooldown".equals(b.path("type").asString(null)) ? 1 : 0);
          if (byType != 0) return byType;
          return Integer.compare(b.path("level").asInt(), a.path("level").asInt());
        });
    var sorted = F.arrayNode();
    double total = 0;
    for (var row : ordered) {
      sorted.add(row);
      total += row.path("attackBonus").asDouble(0);
    }
    var gem = (ObjectNode) input.path("gem");
    gem.set("gems", sorted);
    gem.put("attackBonus", total);
    return true;
  }

  /** 각인 name의 레벨을 level로(등급·스톤 그대로). */
  static boolean engraving(ObjectNode input, String name, Integer level) {
    if (name == null || level == null) return false;
    var rows = input.path("engraving");
    if (!rows.isArray()) return false;
    for (var row : (ArrayNode) rows)
      if (name.equals(row.path("name").asString(null))) {
        ((ObjectNode) row).put("level", level);
        return true;
      }
    return false;
  }

  /** 무기 품질. */
  static boolean quality(ObjectNode input, Integer quality) {
    var weapon = input.path("armory").path("equipment").path("weapon");
    if (quality == null || !weapon.isObject()) return false;
    ((ObjectNode) weapon).put("quality", quality);
    return true;
  }

  /**
   * 일반 재련 +1: 그 부위 stat(방어구 주스탯·무기 무기 공격력)에 단계표 Δ를 더한다. 엔진의 주스탯은 부위 stat 합이라 그 부위만 바꾸면 반영된다. 단계표가
   * 없는 칸(상급 재련이 있는 전율 등)은 계산하지 않는다.
   */
  boolean refine(ObjectNode input, CharacterLoadout loadout, Integer index, Integer target) {
    if (index == null || target == null || index < 0 || index >= loadout.equipment().size())
      return false;
    var item = loadout.equipment().get(index);
    if (item == null || item.detail() == null || item.detail().refineLevel() == null) return false;
    var key = PARTS.get(item.type());
    var grade = SpecUpService.refineGrade(item.name());
    if (key == null || grade == null) return false;
    var family =
        grade == com.loakraken.refining.domain.RefiningRules.Grade.JEONYUL
            ? CombatPowerRefineModel.Family.JEONYUL
            : CombatPowerRefineModel.Family.UPGRADE;
    int advanced =
        item.detail().advancedRefineLevel() == null ? 0 : item.detail().advancedRefineLevel();
    var from =
        CombatPowerRefineModel.stat(
            refine, item.type(), family, item.detail().refineLevel(), advanced);
    var to = CombatPowerRefineModel.stat(refine, item.type(), family, target, advanced);
    var part = input.path("armory").path("equipment").path(key);
    if (from == null || to == null || !part.path("stat").isNumber()) return false;
    ((ObjectNode) part).put("stat", part.path("stat").asDouble() + to.subtract(from).doubleValue());
    return true;
  }

  /**
   * 팔 보호구(완갑) 재련: 단계표(armGuardTable)의 주스탯·무기 공격력·기본 공격력 +N을 목표 단계 값으로, 기본 공격력 %를 grade의 등급 효과로. 지금
   * 단계와 목표 단계가 모두 확인된 칸이고 입력 값이 지금 단계·지금 등급과 같을 때만 바꾼다.
   */
  boolean armGuard(ObjectNode input, CharacterLoadout loadout, Integer level, String grade) {
    if (level == null || grade == null) return false;
    var item = armGuardItem(loadout);
    if (item == null || item.detail() == null || item.detail().refineLevel() == null) return false;
    var from = refine.armGuardTable().get(item.detail().refineLevel());
    var to = refine.armGuardTable().get(level);
    var fromPct = refine.armGuardGradePct().get(item.grade());
    var toPct = refine.armGuardGradePct().get(grade);
    var part = input.path("armory").path("equipment").path("vambrace");
    if (from == null
        || to == null
        || !from.confirmed()
        || !to.confirmed()
        || fromPct == null
        || toPct == null
        || !part.isObject()) return false;
    if (part.path("stat").asLong(-1) != from.mainStat()
        || part.path("weaponAtkFlat").asLong(-1) != from.weaponAttack()
        || part.path("baseAtkFlat").asLong(-1) != from.baseAttack()
        || part.path("baseAtkPercent").asDouble(-1) != fromPct.doubleValue() * 100) return false;
    var vambrace = (ObjectNode) part;
    vambrace.put("stat", to.mainStat());
    vambrace.put("weaponAtkFlat", to.weaponAttack());
    vambrace.put("baseAtkFlat", to.baseAttack());
    vambrace.put("baseAtkPercent", toPct.doubleValue() * 100);
    return true;
  }

  /** 팔 보호구 등급 순서. */
  static final List<String> ARM_GUARD_GRADES = List.of("영웅", "전설", "유물", "고대");

  /**
   * 목표 단계의 팔 보호구 등급: 지금 등급의 최대 단계(armGuardGradeRange 끝)를 넘으면 다음 등급(승급), 아니면 지금 등급. 모르는 등급이면 null.
   */
  String armGuardGrade(String current, int target) {
    int at = ARM_GUARD_GRADES.indexOf(current);
    var range = refine.armGuardGradeRange().get(current);
    if (at < 0 || range == null || range.size() < 2) return null;
    if (target <= range.get(1)) return current;
    return at + 1 < ARM_GUARD_GRADES.size() ? ARM_GUARD_GRADES.get(at + 1) : null;
  }

  static CharacterLoadout.Equipment armGuardItem(CharacterLoadout loadout) {
    return loadout.equipment().stream()
        .filter(equipment -> equipment != null && "완갑".equals(equipment.type()))
        .findFirst()
        .orElse(null);
  }

  /** 깨달음 카르마 레벨(엔진은 레벨 × 0.1%를 무기 공격력 %에 더한다). */
  static boolean karma(ObjectNode input, int level) {
    var enlightenment = input.path("arkPassive").path("enlightenment");
    if (!enlightenment.isObject() || !enlightenment.path("karmalevel").isNumber()) return false;
    ((ObjectNode) enlightenment).put("karmalevel", level);
    return true;
  }

  /** 스톤 [레벨 보너스]: 증가 각인 두 개의 세공 레벨 합이 5 이상이면 기본 공격력 1.5%(공식 가이드, 전투력 장비 모델과 같은 규칙). */
  static final double STONE_BONUS = 1.5;

  static final int STONE_BONUS_SUM = 5;

  private static final Pattern STONE_LINE = Pattern.compile("(.+) Lv\\.(\\d+)");

  /**
   * 스톤 증가 각인 두 개의 세공 레벨을 levels로: 각인 행의 stone, 스톤 줄('이름 Lv.N'), 레벨 보너스(합 5 이상 1.5%). 지금 값이 서로 맞을 때만
   * 바꾼다(각인 행의 stone = 스톤 줄 레벨, 지금 보너스 = 규칙 값).
   */
  static boolean stone(ObjectNode input, Map<String, Integer> levels) {
    var current = stoneLevels(input);
    if (current == null || levels == null || !current.keySet().equals(levels.keySet()))
      return false;
    var stone = (ObjectNode) input.path("armory").path("abilityStone");
    int sum = current.values().stream().mapToInt(Integer::intValue).sum();
    if (stone.path("attackbonus").asDouble(0) != (sum >= STONE_BONUS_SUM ? STONE_BONUS : 0))
      return false;
    var targets = new LinkedHashMap<String, ObjectNode>();
    for (var row : input.path("engraving")) {
      var name = row.path("name").asString(null);
      if (!levels.containsKey(name)) continue;
      if (row.path("stone").asInt(0) != current.get(name)) return false;
      targets.put(name, (ObjectNode) row);
    }
    if (targets.size() != levels.size()) return false;
    targets.forEach((name, row) -> row.put("stone", levels.get(name)));
    var option = (ArrayNode) stone.path("option");
    for (int i = 0; i < option.size(); i++) {
      var m = STONE_LINE.matcher(option.get(i).asString(""));
      if (m.matches() && levels.containsKey(m.group(1)))
        option.set(i, F.stringNode(m.group(1) + " Lv." + levels.get(m.group(1))));
    }
    int next = levels.values().stream().mapToInt(Integer::intValue).sum();
    stone.put("attackbonus", next >= STONE_BONUS_SUM ? STONE_BONUS : 0);
    return true;
  }

  /** 스톤 줄의 앞 두 줄(증가 각인) 이름 → 세공 레벨, 스톤 줄 순서. 두 줄로 읽히지 않으면 null. */
  static Map<String, Integer> stoneLevels(JsonNode input) {
    var option = input.path("armory").path("abilityStone").path("option");
    if (!option.isArray() || option.size() < 2) return null;
    var levels = new LinkedHashMap<String, Integer>();
    for (int i = 0; i < 2; i++) {
      var m = STONE_LINE.matcher(option.get(i).asString(""));
      if (!m.matches()) return null;
      levels.put(m.group(1), Integer.parseInt(m.group(2)));
    }
    return levels.size() == 2 ? levels : null;
  }

  /** 장신구 한 부위의 연마 줄을 option으로(줄 글자는 툴팁과 같은 '이름 +값' 꼴). */
  static boolean polish(ObjectNode input, String slot, List<String> option) {
    if (slot == null || option == null) return false;
    var item = input.path("armory").path("accessory").path(slot);
    if (!item.isObject() || !item.path("option").isArray()) return false;
    var lines = F.arrayNode();
    option.forEach(lines::add);
    ((ObjectNode) item).set("option", lines);
    return true;
  }
}
