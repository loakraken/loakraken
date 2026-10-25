package com.loakraken.specup.application;

import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CombatPowerPolishValues;
import com.loakraken.specup.domain.SpecUpCost;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 서포터 장신구 연마 교체 후보. 비교 기준 B의 장신구 후보 생성(역할별 옵션 표 · 등급 사다리)을 서포터 역할로 그대로 옮겼다(원본 모듈 확인, 내부 기록).
 *
 * <ul>
 *   <li>줄마다 상태: 서포터 옵션(고정값 공격력·무기 공격력 제외)은 지금 등급과 같은 계열의 더 높은 등급들, 고정값 공격력·무기 공격력 줄은 서포터 옵션 중 상이 두
 *       개 이상일 때만 남기고(서포터 옵션인 고정값 무기 공격력은 더 높은 등급으로도 바꿔 봄) 아니면 목표에서 뺌, 그 밖의 줄은 그대로.
 *   <li>모든 줄 상태 조합 가운데 바뀐 줄이 있는 것. 서포터 주옵션이 하나뿐이면 부위별 추가 옵션(목걸이 낙인력, 귀걸이 최대 생명력·최대 마나)을 붙인 조합도 —
 *       B는 옵션 이름을 표의 처음 이름(initial)으로 견주어, 처음 이름이 다른 세레나데·아군 강화 옵션은 붙이지 않는다.
 *   <li>Δ서포터 점수가 0보다 큰 후보만(B와 같음).
 * </ul>
 *
 * 표는 4티어 고대(1680) 값만 쓴다 — 같은 등급·아이템 레벨의 연마 수치표에 맞는 장신구만(시뮬레이터·딜러 연마와 같은 관문).
 */
final class SpecUpSupportPolish {
  private SpecUpSupportPolish() {}

  /** 표 한 줄: 옵션 글자, 등급(0 하·1 중·2 상), 계열(같은 계열 안에서만 등급을 올림), 서포터 옵션인가, 고정값 공격력·무기 공격력인가, 처음 이름. */
  record Option(String line, int grade, String family, boolean support, boolean flatAttack, String initialName) {}

  /** B 옵션 표의 4티어 고대 줄(표 순서). 최대 마나 +6은 유물·고대 공통 줄이라 계열이 따로다(올릴 등급이 없음). */
  static final List<Option> TABLE = table();

  private static final Map<String, Option> BY_LINE = byLine();

  /** 주옵션이 하나뿐일 때 붙여 보는 옵션 이름(부위별, B의 서포터 목록). */
  private static final Map<String, Set<String>> ADDED =
      Map.of(
          "necklace", Set.of("세레나데, 신앙, 조화 게이지 획득량", "낙인력"),
          "earing", Set.of("최대 생명력", "최대 마나"),
          "ring", Set.of("아군 공격력 강화 효과", "아군 피해량 강화 효과"));

  private static List<Option> table() {
    var rows = new ArrayList<Option>();
    // 딜러 옵션(서포터 아님)
    add(rows, "추가 피해", "%", false, false, null, "0.70", "1.60", "2.60");
    add(rows, "적에게 주는 피해", "%", false, false, null, "0.55", "1.20", "2.00");
    add(rows, "낙인력", "%", true, false, null, "2.15", "4.80", "8.00");
    add(rows, "세레나데, 신앙, 조화 게이지 획득량", "%", true, false, "아덴 게이지 획득량", "1.60", "3.60", "6.00");
    add(rows, "무기 공격력", "%", true, false, null, "0.80", "1.80", "3.00");
    add(rows, "공격력", "%", false, false, null, "0.40", "0.95", "1.55");
    add(rows, "파티원 회복 효과", "%", true, false, null, "0.95", "2.10", "3.50");
    add(rows, "파티원 보호막 효과", "%", true, false, null, "0.95", "2.10", "3.50");
    add(rows, "치명타 적중률", "%", false, false, null, "0.40", "0.95", "1.55");
    add(rows, "치명타 피해", "%", false, false, null, "1.10", "2.40", "4.00");
    add(rows, "아군 공격력 강화 효과", "%", true, false, "아군 공격력 강화", "1.35", "3.00", "5.00");
    add(rows, "아군 피해량 강화 효과", "%", true, false, "아군 피해량 강화", "2.00", "4.50", "7.50");
    add(rows, "무기 공격력", "", true, true, null, "195", "480", "960");
    add(rows, "공격력", "", false, true, null, "80", "195", "390");
    add(rows, "최대 생명력", "", true, false, null, "1300", "3250", "6500");
    rows.add(new Option("최대 마나 +6", 0, "최대 마나+#:유물·고대", true, false, "최대 마나"));
    rows.add(new Option("최대 마나 +15", 1, "최대 마나+#:고대", true, false, "최대 마나"));
    rows.add(new Option("최대 마나 +30", 2, "최대 마나+#:고대", true, false, "최대 마나"));
    add(rows, "상태이상 공격 지속시간", "%", false, false, "상태이상 지속시간", "0.20", "0.50", "1.00");
    add(rows, "전투 중 생명력 회복량", "", false, false, null, "10", "25", "50");
    return List.copyOf(rows);
  }

  private static void add(
      List<Option> rows,
      String name,
      String unit,
      boolean support,
      boolean flatAttack,
      String initialName,
      String... values) {
    for (int grade = 0; grade < 3; grade++)
      rows.add(
          new Option(
              name + " +" + values[grade] + unit,
              grade,
              name + "+#" + unit + ":고대",
              support,
              flatAttack,
              initialName == null ? name : initialName));
  }

  private static Map<String, Option> byLine() {
    var map = new LinkedHashMap<String, Option>();
    for (var option : TABLE) map.put(option.line(), option);
    return Map.copyOf(map);
  }

  /** 줄 하나의 상태(바꿔 볼 값들, 첫 값 = 지금). omitted면 목표에서 빠지는 줄. */
  private record State(List<String> values, boolean omitted, boolean main, String family) {}

  /**
   * 서포터 연마 후보(장신구 부위마다). 계산할 수 있는 부위가 하나도 없으면 null(종류 한 줄 알림). scorer·context는 Δ가 0보다 큰 후보만 남기려고 쓴다.
   */
  static List<SpecUpService.Planned> rows(
      SpecUpKrakenScore scorer,
      SpecUpKrakenScore.Context context,
      CharacterLoadout loadout,
      CombatPowerPolishValues values) {
    var planned = new ArrayList<SpecUpService.Planned>();
    boolean any = false;
    int earing = 0;
    int ring = 0;
    for (int index = 0; index < loadout.equipment().size(); index++) {
      var item = loadout.equipment().get(index);
      if (item == null || !SpecUpScoreCandidates.MAIN_OPTIONS.containsKey(item.type())) continue;
      var slot =
          switch (item.type()) {
            case "목걸이" -> "necklace";
            case "귀걸이" -> "earing" + ++earing;
            default -> "ring" + ++ring;
          };
      var option = context.input().path("armory").path("accessory").path(slot).path("option");
      if (!option.isArray() || option.isEmpty()) continue;
      if (!com.loakraken.character.application.CombatPowerSimulationService.polishTableFits(
          values, item)) continue;
      any = true;
      for (var next : candidates(slot, option)) {
        var delta = scorer.delta(context, new SpecUpKrakenScore.Polish(slot, next.lines()));
        if (delta == null || !(delta > 0)) continue;
        planned.add(row(item, index, slot, current(option), next, values));
      }
    }
    return any ? planned : null;
  }

  /** 목표 줄 목록과 후보 코드(줄마다 L·M·H, 빠진 줄 N, 붙인 옵션은 +이름 첫 글자와 등급). */
  record Next(List<String> lines, String code) {}

  static List<Next> candidates(String slot, JsonNode option) {
    var lines = new ArrayList<String>();
    option.forEach(line -> lines.add(line.asString("")));
    // 고정값 공격력·무기 공격력을 남기는가: 서포터 옵션(고정값 제외) 가운데 상이 두 개 이상
    long high =
        lines.stream()
            .filter(
                line -> {
                  var known = BY_LINE.get(line);
                  return (known == null || known.support())
                      && !(known != null && known.flatAttack())
                      && known != null
                      && known.grade() == 2;
                })
            .count();
    boolean keepFlat = high >= 2;
    var states = new ArrayList<State>();
    for (var line : lines) {
      var known = BY_LINE.get(line);
      boolean flat = known != null && known.flatAttack();
      if (!keepFlat && flat) states.add(new State(List.of(line), true, false, null));
      else if (known == null) states.add(new State(List.of(line), false, true, line));
      else if (!known.support()) states.add(new State(List.of(line), false, false, null));
      else if (flat) states.add(new State(withHigher(line, known, true), false, false, null));
      else states.add(new State(withHigher(line, known, false), false, true, known.family()));
    }
    var combos = new ArrayList<List<String>>();
    walk(states, 0, new String[states.size()], combos);
    var result = new ArrayList<Next>();
    for (var combo : combos)
      if (changed(states, combo)) result.add(new Next(next(states, combo), code(states, combo, null)));
    var mains = states.stream().filter(State::main).toList();
    if (mains.size() != 1) return result;
    var exclude = mains.get(0).family();
    var added = ADDED.get(slot.replaceAll("\\d", ""));
    var groups = new LinkedHashMap<String, List<Option>>();
    for (var candidate : TABLE)
      if (candidate.support()
          && !candidate.flatAttack()
          && added.contains(candidate.initialName())
          && !candidate.family().equals(exclude))
        groups.computeIfAbsent(candidate.family(), key -> new ArrayList<>()).add(candidate);
    var extras = new ArrayList<Option>();
    for (var group : groups.values()) {
      group.sort(
          (a, b) -> a.grade() != b.grade() ? a.grade() - b.grade() : a.line().compareTo(b.line()));
      extras.addAll(group);
    }
    for (var combo : combos)
      for (var extra : extras) {
        var next = new ArrayList<>(next(states, combo));
        next.add(extra.line());
        result.add(new Next(List.copyOf(next), code(states, combo, extra)));
      }
    return result;
  }

  /** 지금 값과, 같은 계열(고정값 무기 공격력은 서포터 고정값 옵션)의 더 높은 등급들(등급 순). */
  private static List<String> withHigher(String line, Option known, boolean flat) {
    var values = new ArrayList<String>();
    values.add(line);
    for (var candidate : TABLE)
      if (candidate.support()
          && candidate.flatAttack() == flat
          && candidate.grade() > known.grade()
          && (flat || candidate.family().equals(known.family()))) values.add(candidate.line());
    if (flat) values.subList(1, values.size()).sort(byGradeThenFamily(known));
    return List.copyOf(values);
  }

  private static java.util.Comparator<String> byGradeThenFamily(Option known) {
    return (a, b) -> {
      var x = BY_LINE.get(a);
      var y = BY_LINE.get(b);
      if (x.grade() != y.grade()) return x.grade() - y.grade();
      int fx = x.family().equals(known.family()) ? 0 : 1;
      int fy = y.family().equals(known.family()) ? 0 : 1;
      if (fx != fy) return fx - fy;
      return a.compareTo(b);
    };
  }

  private static void walk(List<State> states, int at, String[] chosen, List<List<String>> out) {
    if (at == states.size()) {
      out.add(java.util.Arrays.asList(chosen.clone()));
      return;
    }
    var state = states.get(at);
    if (state.omitted()) {
      chosen[at] = null;
      walk(states, at + 1, chosen, out);
      return;
    }
    for (var value : state.values()) {
      chosen[at] = value;
      walk(states, at + 1, chosen, out);
    }
  }

  private static boolean changed(List<State> states, List<String> combo) {
    for (int i = 0; i < states.size(); i++)
      if (!states.get(i).omitted() && !combo.get(i).equals(states.get(i).values().get(0)))
        return true;
    return false;
  }

  private static List<String> next(List<State> states, List<String> combo) {
    var next = new ArrayList<String>();
    for (int i = 0; i < states.size(); i++) if (!states.get(i).omitted()) next.add(combo.get(i));
    return List.copyOf(next);
  }

  private static String code(List<State> states, List<String> combo, Option extra) {
    var code = new StringBuilder("S");
    for (int i = 0; i < states.size(); i++) {
      if (states.get(i).omitted()) {
        code.append('N');
        continue;
      }
      var known = BY_LINE.get(combo.get(i));
      code.append(known == null ? 'X' : "LMH".charAt(known.grade()));
    }
    if (extra != null)
      code.append('+')
          .append(
              switch (extra.initialName()) {
                case "낙인력" -> 'B';
                case "최대 생명력" -> 'V';
                default -> 'P';
              })
          .append("LMH".charAt(extra.grade()));
    return code.toString();
  }

  private static List<String> current(JsonNode option) {
    var lines = new ArrayList<String>();
    option.forEach(line -> lines.add(line.asString("")));
    return lines;
  }

  private static SpecUpService.Planned row(
      CharacterLoadout.Equipment item,
      int index,
      String slot,
      List<String> before,
      Next next,
      CombatPowerPolishValues values) {
    return SpecUpService.Planned.scored(
        "POLISH:" + index + ":" + next.code(),
        "POLISH",
        item.name(),
        shown(values, item.type(), before),
        shown(values, item.type(), next.lines()),
        item.icon(),
        item.grade(),
        SpecUpCost.POLISH_COST_UNCONFIRMED,
        new SpecUpKrakenScore.Polish(slot, next.lines()));
  }

  /** 화면: 서포터 점수에 쓰이는 줄만(B와 같게 — 서포터 옵션, 고정값 무기 공격력은 남길 때만). */
  private static String shown(CombatPowerPolishValues values, String part, List<String> lines) {
    var parts = new ArrayList<String>();
    for (var line : lines) {
      var known = BY_LINE.get(line);
      if (known != null && !known.support()) continue;
      parts.add(SpecUpScoreCandidates.labeled(values, part, line));
    }
    return parts.isEmpty() ? "점수 옵션 없음" : String.join(" · ", parts);
  }
}
