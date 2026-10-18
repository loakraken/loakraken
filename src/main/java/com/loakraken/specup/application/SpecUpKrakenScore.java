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
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 스펙업 후보 하나의 Δ크라켄 점수. 캐릭터 결과에 캐시된 점수 엔진 입력의 <b>사본</b>에 그 후보만 적용해 딜러 점수를 다시 계산한다(원본 입력은 그대로, 공식 호출
 * 없음). 바꾸는 곳은 엔진이 읽는 필드뿐이다: 보석 레벨·기본 공격력 %(순서·합 다시), 각인 레벨, 무기 품질, 재련 부위 스탯(전투력 재련 모델 단계표의 Δ). 근거가
 * 없으면(빈 칸 보석·서포터·상급 재련 등) null이고 근사하지 않는다.
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
    if (!input.path("armory").path("equipment").path("weapon").path("stat").isNumber()
        || !input.path("arkPassive").path("evolution").isObject())
      return new Context(null, Double.NaN, "SCORE_UNAVAILABLE");
    double before = score(input);
    if (!usable(before)) return new Context(null, Double.NaN, "SCORE_UNAVAILABLE");
    return new Context(input, before, null);
  }

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
          default -> false;
        };
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
}
