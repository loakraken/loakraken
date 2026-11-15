package com.loakraken.specup.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.specup.domain.SpecUpCost;
import com.loakraken.specup.domain.SpecUpCostRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 점수 입력에서 만드는 스펙업 후보: 장신구 연마 교체(POLISH)·어빌리티 스톤(ABILITY_STONE)·깨달음 카르마(KARMA_LEVEL). 시뮬레이터 입력 하나로
 * 나타낼 수 없어 전투력 Δ는 없고(change null), 바꿀 값(SpecUpKrakenScore.Edit)으로 Δ크라켄 점수만 낸다. 후보 모양은 비교 기준 B 스펙업
 * 추천과 같게 맞췄다(레퍼런스 결과 표, 기준 캐릭터 3명). 계산할 근거가 없으면(입력에 값이 없음·연마 수치표에 없는 장신구) null을 돌려주고 그 종류는 한 줄 알림으로
 * 남긴다.
 */
final class SpecUpScoreCandidates {
  private SpecUpScoreCandidates() {}

  /** 깨달음 카르마 최대 레벨. */
  static final int KARMA_MAX = 30;

  /** 부위별 점수에 쓰이는 연마 옵션 두 개(딜러, % 옵션). */
  static final Map<String, List<String>> MAIN_OPTIONS =
      Map.of(
          "목걸이", List.of("추가 피해", "적에게 주는 피해"),
          "귀걸이", List.of("무기 공격력", "공격력"),
          "반지", List.of("치명타 적중률", "치명타 피해"));

  /** 두 옵션이 모두 상일 때 셋째 줄에 넣어 보는 고정값 옵션. */
  static final List<String> FLAT_OPTIONS = List.of("무기 공격력", "공격력");

  private static final Pattern LINE = Pattern.compile("(.+?) \\+([\\d.,]+)(%?)");
  private static final String[] GRADE_LABELS = {"하", "중", "상"};
  private static final String GRADE_CODES = "LMH";

  /**
   * 어빌리티 스톤 목표(세공 성공 수 조합 → 각인 레벨): 9·7 = Lv.3·Lv.2, 10·6 = Lv.4·Lv.1, 10·7 = Lv.4·Lv.2. 두 각인 중 어느
   * 쪽에 높은 레벨이 붙을지는 점수가 더 오르는 쪽(비교 기준 B와 같음). 99·109·1010돌은 넣지 않는다(핫픽스 v0.3.9.2, PM 총괄 11-09): 기대
   * 비용(키트 50골드 ÷ 성공 확률)이 99돌 약 1,543만, 109돌 약 3.6억, 1010돌 약 314억 골드라 현실적인 목표가 아니고, 추천 순서 전투력 우선이 Δ만
   * 보고 1010돌을 첫 단계로 골라 누적 비용이 314억이 됐다. 목록·추천 순서(두 정렬)·목표 경로 모두 이 목록을 쓴다.
   */
  static final List<int[]> STONE_TARGETS =
      List.of(new int[] {9, 7, 3, 2}, new int[] {10, 6, 4, 1}, new int[] {10, 7, 4, 2});

  /** 깨달음 카르마 지금 레벨 + 1 … 30. 입력에 레벨이 없으면 null. */
  static List<SpecUpService.Planned> karma(JsonNode input) {
    var level = input.path("arkPassive").path("enlightenment").path("karmalevel");
    if (!level.isNumber()) return null;
    int current = level.asInt();
    var planned = new ArrayList<SpecUpService.Planned>();
    for (int next = current + 1; next <= KARMA_MAX; next++)
      planned.add(
          SpecUpService.Planned.scored(
              "KARMA_LEVEL:" + next,
              "KARMA_LEVEL",
              "깨달음 카르마",
              current + "레벨",
              next + "레벨",
              null,
              null,
              karmaCost(current, next),
              new SpecUpKrakenScore.Karma(next)));
    return planned;
  }

  /**
   * 어빌리티 스톤: 목표 조합마다 두 배치 중 점수가 더 오르는 쪽 하나, 점수가 오르는 조합만. 스톤 줄을 읽지 못하거나 지금 값이 서로 맞지 않으면(각인 행·레벨 보너스)
   * null.
   */
  static List<SpecUpService.Planned> stone(
      SpecUpKrakenScore scorer, SpecUpKrakenScore.Context context, CharacterLoadout loadout) {
    var current = SpecUpKrakenScore.stoneLevels(context.input());
    if (current == null) return null;
    var names = List.copyOf(current.keySet());
    // 지금 상태를 그대로 넣어 계산이 되는지 먼저 본다(각인 행·레벨 보너스가 맞지 않으면 계산하지 않음).
    if (scorer.delta(context, new SpecUpKrakenScore.Stone(current)) == null) return null;
    var item = equipment(loadout, "어빌리티 스톤");
    var planned = new ArrayList<SpecUpService.Planned>();
    for (var target : STONE_TARGETS) {
      var oriented = orientation(scorer, context, names, target);
      if (oriented == null) continue;
      var best = oriented.levels();
      double bestDelta = oriented.delta();
      if (SpecUpKrakenScore.round2(bestDelta).signum() <= 0) continue;
      var label = target[0] + "" + target[1] + "돌";
      planned.add(
          SpecUpService.Planned.scored(
              "ABILITY_STONE:" + best.get(names.get(0)) + ":" + best.get(names.get(1)),
              "ABILITY_STONE",
              item == null ? "어빌리티 스톤" : item.name(),
              stoneText(current),
              label + " " + stoneText(best),
              item == null ? null : item.icon(),
              item == null ? null : item.grade(),
              stoneCost(label),
              new SpecUpKrakenScore.Stone(best)));
    }
    return planned;
  }

  /** 스톤 목표 조합의 배치 하나: 각인 이름 → 레벨, 그 배치의 Δ점수(반올림 전). */
  record Oriented(Map<String, Integer> levels, double delta) {}

  /**
   * 목표 조합의 두 배치(어느 각인에 높은 레벨) 가운데 목록이 보이는 배치: 점수가 더 오르는 쪽, 같으면 앞 배치(점수 입력 스톤 줄 순서대로 높은 레벨 먼저). 둘 다
   * 계산할 수 없으면 null. 추천 순서·목표 경로도 이 배치만 쓴다(PM 총괄 11-15: 같은 점수에 다른 전투력인 뒤집은 배치는 비교 기준에 없음).
   */
  static Oriented orientation(
      SpecUpKrakenScore scorer,
      SpecUpKrakenScore.Context context,
      List<String> names,
      int[] target) {
    Map<String, Integer> best = null;
    Double bestDelta = null;
    for (var order : List.of(new int[] {target[2], target[3]}, new int[] {target[3], target[2]})) {
      var levels = new LinkedHashMap<String, Integer>();
      levels.put(names.get(0), order[0]);
      levels.put(names.get(1), order[1]);
      var delta = scorer.delta(context, new SpecUpKrakenScore.Stone(levels));
      if (delta != null && (bestDelta == null || delta > bestDelta)) {
        best = levels;
        bestDelta = delta;
      }
    }
    return best == null ? null : new Oriented(best, bestDelta);
  }

  /** 목록 스톤 줄과 같은 배치(목표 조합마다 하나). 점수를 낼 수 없거나 스톤 줄을 읽지 못하면 null. */
  static List<Map<String, Integer>> stoneOrientations(
      SpecUpKrakenScore scorer, SpecUpKrakenScore.Context context) {
    if (context == null || !context.available()) return null;
    var current = SpecUpKrakenScore.stoneLevels(context.input());
    if (current == null) return null;
    var names = List.copyOf(current.keySet());
    var result = new ArrayList<Map<String, Integer>>();
    for (var target : STONE_TARGETS) {
      var oriented = orientation(scorer, context, names, target);
      if (oriented != null) result.add(oriented.levels());
    }
    return List.copyOf(result);
  }

  private static SpecUpCost karmaCost(int current, int next) {
    var gold = SpecUpCostRules.karmaGold(current, next);
    return gold == null ? SpecUpCost.KARMA_COST_UNCONFIRMED : SpecUpCost.karma(gold);
  }

  private static SpecUpCost stoneCost(String label) {
    var gold = SpecUpCostRules.stoneGold(label);
    return gold == null ? SpecUpCost.STONE_COST_UNCONFIRMED : SpecUpCost.stone(gold);
  }

  private static String stoneText(Map<String, Integer> levels) {
    var parts = new ArrayList<String>();
    levels.forEach((name, level) -> parts.add(name + " Lv." + level));
    return String.join(" · ", parts);
  }

  /**
   * 장신구 연마 교체: 부위마다 점수에 쓰이는 두 옵션(MAIN_OPTIONS)의 등급을 지금 이상으로 바꾼 조합(지금과 같은 조합 제외). 목표 장신구는 그 두 옵션만 있는
   * 매물로 본다 — 셋째 줄은 모르는 옵션이라 지금 장신구의 고정값 공격력·무기 공격력 줄도 점수에서 빠진다(비교 기준 B와 같음, 레퍼런스 대조로 확인). 두 옵션이 모두
   * 상이면 셋째 줄에 고정값 공격력·무기 공격력을 넣은 매물도 후보다(지금 고정값 줄이 있으면 그보다 높은 등급 모두, 없으면 상만). 줄 수가 3개가 아니거나 두
   * 옵션·고정값 줄 가운데 값이 연마 수치표(1680 고대)에 없는 줄이 있는 부위(다른 아이템 레벨 장신구)는 건너뛴다. 하나도 계산할 수 없으면 null.
   */
  static List<SpecUpService.Planned> polish(
      JsonNode input, CharacterLoadout loadout, CombatPowerPolishValues values) {
    return polish(input, loadout, values, false);
  }

  /**
   * 추천 순서 전용 '상·상 뒤' 후보: 처음에 두 주옵션이 모두 상이 아닌 장신구가 경로에서 상·상(HH) 매물로 바뀐 뒤 비교 기준이 내는 셋째 줄 고정값 후보(공격력·
   * 무기 공격력 상, 그때 고정값 줄이 없으므로 상만). 처음 상태에서는 고를 수 없어(목록에 없음) 경로가 그 장신구의 HH 단계를 고른 뒤에만 쓴다(PM 총괄 11-15,
   * 스위치를 켜기 전 필수).
   */
  static List<SpecUpService.Planned> polishAfterTop(
      JsonNode input, CharacterLoadout loadout, CombatPowerPolishValues values) {
    return polish(input, loadout, values, true);
  }

  private static List<SpecUpService.Planned> polish(
      JsonNode input, CharacterLoadout loadout, CombatPowerPolishValues values, boolean afterTop) {
    var planned = new ArrayList<SpecUpService.Planned>();
    boolean any = false;
    int earing = 0;
    int ring = 0;
    for (int index = 0; index < loadout.equipment().size(); index++) {
      var item = loadout.equipment().get(index);
      if (item == null || !MAIN_OPTIONS.containsKey(item.type())) continue;
      var slot =
          switch (item.type()) {
            case "목걸이" -> "necklace";
            case "귀걸이" -> "earing" + ++earing;
            default -> "ring" + ++ring;
          };
      var rows = slotRows(input, item, index, slot, values, afterTop);
      if (rows == null) continue;
      any = true;
      planned.addAll(rows);
    }
    return any ? planned : null;
  }

  private static List<SpecUpService.Planned> slotRows(
      JsonNode input,
      CharacterLoadout.Equipment item,
      int index,
      String slot,
      CombatPowerPolishValues values,
      boolean afterTop) {
    var option = input.path("armory").path("accessory").path(slot).path("option");
    if (!option.isArray() || option.size() != 3) return null;
    // 시뮬레이터와 같은 규칙: 수치표(1680 고대)와 같은 장신구만(거래 제한 아이템 레벨 줄, 없으면 모든 연마 값이 표의 값). 점수에 안 쓰이는 줄만 있는
    // 다른 레벨 장신구가 1680 표로 후보를 만들지 않게(코드 리뷰 10-25).
    if (!com.loakraken.character.application.CombatPowerSimulationService.polishTableFits(
        values, item)) return null;
    var lines = new ArrayList<String>();
    option.forEach(line -> lines.add(line.asString("")));
    var part = item.type();
    var mains = MAIN_OPTIONS.get(part);
    int[] position = {-1, -1};
    int[] grade = {-1, -1};
    // 고정값 공격력·무기 공격력 줄은 모두 점수에 쓰인다(줄마다 보존). 등급은 셋째 줄 후보에서만 쓴다(그때 고정값 줄은 하나뿐).
    var flats = new ArrayList<Integer>();
    int flatGrade = -1;
    for (int i = 0; i < lines.size(); i++) {
      var m = LINE.matcher(lines.get(i));
      if (!m.matches()) continue;
      boolean percent = !m.group(3).isEmpty();
      if (!percent && FLAT_OPTIONS.contains(m.group(1))) {
        flatGrade = gradeOf(values, part, m.group(1), false, lines.get(i));
        if (flatGrade < 0) return null; // 수치표에 없는 값(다른 아이템 레벨): 주옵션과 같이 그 부위를 건너뜀
        flats.add(i);
        continue;
      }
      int k = percent ? mains.indexOf(m.group(1)) : -1;
      if (k < 0) continue;
      int g = gradeOf(values, part, m.group(1), true, lines.get(i));
      if (g < 0 || position[k] >= 0) return null; // 수치표에 없는 값(다른 아이템 레벨) 또는 같은 옵션 두 줄
      position[k] = i;
      grade[k] = g;
    }
    // 지금 장신구에서 점수에 쓰이는 줄(두 옵션 + 고정값 공격력·무기 공격력), 원래 줄 순서.
    var scoring = new ArrayList<String>();
    for (int i = 0; i < lines.size(); i++)
      if (i == position[0] || i == position[1] || flats.contains(i)) scoring.add(lines.get(i));
    var rows = new ArrayList<SpecUpService.Planned>();
    if (afterTop) {
      // 상·상이 된 뒤의 셋째 줄 고정값 상(처음부터 상·상인 장신구는 목록·경로의 셋째 줄 후보가 이미 있음).
      if (grade[0] == 2 && grade[1] == 2) return rows;
      var top = new int[] {2, 2};
      for (var name : FLAT_OPTIONS) {
        var next = target(values, part, mains, position, top);
        var text = text(values, part, name, false, 2);
        if (next == null || text == null) continue;
        next.add(text);
        rows.add(
            row(
                item,
                index,
                slot,
                scoring,
                next,
                lines,
                swapLines(values, part, mains, lines, position, flats, top, text),
                "HH" + ("무기 공격력".equals(name) ? "W" : "A") + "H",
                values));
      }
      return rows;
    }
    for (int a = grade[0]; a <= 2; a++)
      for (int b = grade[1]; b <= 2; b++) {
        if (a == grade[0] && b == grade[1]) continue;
        var next = target(values, part, mains, position, new int[] {a, b});
        if (next == null) continue;
        var swap = swapLines(values, part, mains, lines, position, flats, new int[] {a, b}, null);
        rows.add(
            row(item, index, slot, scoring, next, lines, swap, code(a) + "" + code(b), values));
      }
    if (grade[0] == 2 && grade[1] == 2)
      for (var name : FLAT_OPTIONS)
        // 고정값 줄이 있으면 지금보다 높은 등급 모두, 없으면 상만(비교 기준 B와 같음 — 셋째 줄이 모르는 옵션이면 상 매물만 본다).
        for (int g = flats.isEmpty() ? 2 : flatGrade + 1; g <= 2; g++) {
          var next = target(values, part, mains, position, grade);
          var text = text(values, part, name, false, g);
          if (next == null || text == null) continue;
          next.add(text);
          rows.add(
              row(
                  item,
                  index,
                  slot,
                  scoring,
                  next,
                  lines,
                  swapLines(values, part, mains, lines, position, flats, grade, text),
                  "HH" + ("무기 공격력".equals(name) ? "W" : "A") + code(g),
                  values));
        }
    return rows;
  }

  /** 목표 장신구의 점수 줄: 두 옵션을 grades로(−1은 없음), 지금 있던 옵션이 앞(원래 줄 순서), 새로 붙는 옵션이 뒤. */
  private static List<String> target(
      CombatPowerPolishValues values,
      String part,
      List<String> mains,
      int[] position,
      int[] grades) {
    var order = new ArrayList<Integer>(List.of(0, 1));
    order.sort(
        (x, y) ->
            Integer.compare(
                position[x] < 0 ? Integer.MAX_VALUE : position[x],
                position[y] < 0 ? Integer.MAX_VALUE : position[y]));
    var next = new ArrayList<String>();
    for (int k : order) {
      if (grades[k] < 0) continue;
      var text = text(values, part, mains.get(k), true, grades[k]);
      if (text == null) return null;
      next.add(text);
    }
    return next;
  }

  private static SpecUpService.Planned row(
      CharacterLoadout.Equipment item,
      int index,
      String slot,
      List<String> before,
      List<String> after,
      List<String> current,
      List<String> swap,
      String code,
      CombatPowerPolishValues values) {
    return SpecUpService.Planned.scored(
        "POLISH:" + index + ":" + code,
        "POLISH",
        item.name(),
        shown(values, item.type(), before),
        shown(values, item.type(), after),
        item.icon(),
        item.grade(),
        SpecUpCost.POLISH_COST_UNCONFIRMED,
        new SpecUpKrakenScore.Polish(slot, List.copyOf(after), index, List.copyOf(current), swap));
  }

  /**
   * 전투력 계산용 목표 줄(지금 줄 순서): Δ크라켄 점수와 같은 목표 장신구다(비교 기준 B 스펙업 후보 규칙, 계산 검증 11-15). 주옵션은 목표 등급, 지금 고정값
   * 공격력·무기 공격력 줄은 빠진다(null = 전투력 몫 0). 다만 두 주옵션이 모두 상인 장신구(셋째 줄 고정값 후보)는 고정값 줄을 그 후보 값으로 둔다. 주옵션도
   * 고정값도 아닌 줄(딜러 전투력 0인 옵션)은 그대로 둔다. 새로 붙는 주옵션은 빠지는 고정값 줄 자리부터, 없으면 그 밖 줄에 놓는다. 줄 자리는 전투력에 영향이
   * 없다(줄마다 독립 배수).
   */
  static List<String> swapLines(
      CombatPowerPolishValues values,
      String part,
      List<String> mains,
      List<String> current,
      int[] position,
      List<Integer> flats,
      int[] grades,
      String flatText) {
    var swap = new ArrayList<String>(current);
    for (int i : flats) swap.set(i, null);
    var free = new ArrayList<Integer>(flats);
    for (int i = 0; i < current.size(); i++)
      if (i != position[0] && i != position[1] && !flats.contains(i)) free.add(i);
    if (flatText != null) {
      int at = flats.isEmpty() ? free.getFirst() : flats.getFirst();
      free.remove(Integer.valueOf(at));
      swap.set(at, flatText);
    }
    for (int k = 0; k < 2; k++) {
      if (grades[k] < 0) continue;
      int at = position[k] >= 0 ? position[k] : free.removeFirst();
      swap.set(at, text(values, part, mains.get(k), true, grades[k]));
    }
    return java.util.Collections.unmodifiableList(swap);
  }

  private static String shown(CombatPowerPolishValues values, String part, List<String> lines) {
    if (lines.isEmpty()) return "점수 옵션 없음";
    var parts = new ArrayList<String>();
    for (var line : lines) parts.add(labeled(values, part, line));
    return String.join(" · ", parts);
  }

  private static char code(int grade) {
    return grade < 0 ? 'N' : GRADE_CODES.charAt(grade);
  }

  /** 줄 글자의 등급(0 하·1 중·2 상), 수치표에 없으면 −1. */
  static int gradeOf(
      CombatPowerPolishValues values, String part, String name, boolean percent, String line) {
    for (int g = 0; g <= 2; g++) if (line.equals(text(values, part, name, percent, g))) return g;
    return -1;
  }

  /** 수치표의 '이름 +값' 줄 글자(툴팁과 같은 꼴). 없으면 null. */
  static String text(
      CombatPowerPolishValues values, String part, String name, boolean percent, int grade) {
    for (var value : values.options())
      if (value.option().equals(name)
          && value.percent() == percent
          && value.parts().contains(part)) {
        var shown =
            switch (grade) {
              case 0 -> value.low();
              case 1 -> value.mid();
              case 2 -> value.high();
              default -> null;
            };
        return shown == null ? null : name + " " + shown;
      }
    return null;
  }

  /** 연마 수치표에 있는 줄이면 끝에 등급(상·중·하)을 붙인다. */
  static String labeled(CombatPowerPolishValues values, String part, String line) {
    var m = LINE.matcher(line);
    if (!m.matches()) return line;
    int grade = gradeOf(values, part, m.group(1), !m.group(3).isEmpty(), line);
    return grade < 0 ? line : line + "(" + GRADE_LABELS[grade] + ")";
  }

  private static CharacterLoadout.Equipment equipment(CharacterLoadout loadout, String type) {
    return loadout.equipment().stream()
        .filter(item -> item != null && type.equals(item.type()))
        .findFirst()
        .orElse(null);
  }
}
