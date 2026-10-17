package com.loakraken.character.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 아크 그리드 젬 실 효율(#539, 원본 모듈 59000의 o·P). 점수 함수(딜러는 점수 A, 서폿은 GT)를 받아, 젬 옵션·포인트를 뺀 입력의 점수와 비교한다. 효율
 * 값은 원본처럼 ⌊(전/후 − 1) × 10⁴⌋ ÷ 100(퍼센트, 둘째 자리 내림)이다.
 */
public final class KrakenScoreGems {
  private KrakenScoreGems() {}

  private static final List<String> SERIES = List.of("질서", "혼돈");

  /** 옵션 한 줄의 효율. */
  public record Line(String name, double level, Double efficiency) {}

  /** 전체 또는 한 계열(질서·혼돈)의 효율. 계산할 수 없으면 값 null. */
  public record Efficiency(Double efficiency, Double optionEfficiency, Double pointEfficiency, List<Line> lines) {}

  /** 계산할 수 없을 때(아크 그리드 없음). */
  public static final Efficiency UNAVAILABLE = new Efficiency(null, null, null, List.of());

  private static boolean hasArkGrid(JsonNode e) {
    return e != null && e.isObject() && e.path("arkGrid").isObject();
  }

  /** 원본 o(e, t): 젬 전체(옵션 + 포인트). 아크 그리드가 없으면 {@link #UNAVAILABLE}. */
  public static Efficiency total(JsonNode e, KrakenScoreEngine.Tables tables, ToDoubleFunction<JsonNode> score) {
    // 아크 그리드가 없거나 객체가 아니면 젬 효율을 계산하지 않는다(값 null, 500 아님).
    if (!hasArkGrid(e)) return UNAVAILABLE;
    double c = score.applyAsDouble(e);
    double m = score.applyAsDouble(withGemEffects(e, null));
    var noGem = withGemEffects(e, null);
    var cores = e.path("arkGrid").path("core");
    if (cores.isArray()) {
      var mapped = JsonNodeFactory.instance.arrayNode();
      for (var core : cores) mapped.add(reducedCore(core));
      ((ObjectNode) noGem.path("arkGrid")).set("core", mapped);
    }
    double g = score.applyAsDouble(noGem);
    var effects = e.path("arkGrid").path("gemEffects");
    var lines = new ArrayList<Line>();
    if (effects.isArray())
      for (int i = 0; i < effects.size(); i++) {
        var others = new ArrayList<JsonNode>();
        for (int t = 0; t < effects.size(); t++) if (t != i) others.add(effects.get(t));
        double l = score.applyAsDouble(withGemEffects(e, others.isEmpty() ? null : others));
        var a = effects.get(i);
        lines.add(new Line(a.path("name").asString(""), a.path("level").asDouble(), efficiency(c, l)));
      }
    return new Efficiency(efficiency(c, g), efficiency(c, m), efficiency(m, g), lines);
  }

  /** 원본 P(e, t): 질서·혼돈 계열별. 젬 구성을 확인할 수 없으면 두 계열 모두 null. */
  public static Map<String, Efficiency> bySeries(
      JsonNode e, KrakenScoreEngine.Tables tables, ToDoubleFunction<JsonNode> score) {
    var result = new LinkedHashMap<String, Efficiency>();
    result.put("질서", null);
    result.put("혼돈", null);
    if (!hasArkGrid(e)) return result;
    var cores = e.path("arkGrid").path("core");
    var effects = e.path("arkGrid").path("gemEffects");
    if (cores.size() == 0 || effects.size() == 0) return result;
    var names = effectNames(tables);
    var bySeries = levels(cores, effects, names);
    if (bySeries == null) return result;
    double s = score.applyAsDouble(e);
    var u = tables.get("gemEffectLevels");
    for (var series : SERIES) {
      var r = bySeries.get(series);
      if (r.isEmpty()) continue;
      var v = new ArrayList<JsonNode>();
      boolean ok = true;
      for (var n : effects) {
        var key = names.get(normalize(n.path("name").asString("")));
        double c = r.getOrDefault(key, 0.0);
        double g = n.path("level").asDouble() - c;
        if (c == 0) v.add(n);
        else if (g == 0) v.add(null);
        else {
          var t = u.path(key).path(KrakenScoreEngine.jsNumber(g));
          if (!t.isNumber()) {
            ok = false;
            break;
          }
          var copy = (ObjectNode) n.deepCopy();
          copy.put("level", g);
          copy.put("effect", t.asDouble());
          v.add(copy);
        }
      }
      if (!ok) continue;
      var p = withGemEffects(e, v);
      double k = score.applyAsDouble(p);
      var pointless = p.deepCopy();
      var mapped = JsonNodeFactory.instance.arrayNode();
      for (var core : cores) mapped.add(series.equals(series(core)) ? reducedCore(core) : core);
      ((ObjectNode) pointless.path("arkGrid")).set("core", mapped);
      double sScore = score.applyAsDouble(pointless);
      var lines = new ArrayList<Line>();
      for (int i = 0; i < effects.size(); i++) {
        var a = effects.get(i);
        double level = r.getOrDefault(names.get(normalize(a.path("name").asString(""))), 0.0);
        if (level == 0) continue;
        var replaced = new ArrayList<JsonNode>();
        for (int t = 0; t < effects.size(); t++) replaced.add(t == i ? v.get(i) : effects.get(t));
        double o = score.applyAsDouble(withGemEffects(e, replaced));
        lines.add(new Line(a.path("name").asString(""), level, efficiency(s, o)));
      }
      result.put(series, new Efficiency(efficiency(s, sScore), efficiency(s, k), efficiency(k, sScore), lines));
    }
    return result;
  }

  /** 계열별 옵션 이름 → 레벨 합(원본 함수의 검증 그대로). 맞지 않으면 null. */
  private static Map<String, Map<String, Double>> levels(
      JsonNode cores, JsonNode effects, Map<String, String> names) {
    var n = new HashMap<String, Map<String, Double>>();
    n.put("질서", new LinkedHashMap<>());
    n.put("혼돈", new LinkedHashMap<>());
    for (var core : cores) {
      if (core.path("gem").size() == 0) continue;
      var series = series(core);
      if (series == null || !core.path("point").isNumber()) return null;
      for (var gem : core.path("gem")) {
        var point = gem.path("orderChaosPoint");
        if (gem.path("effects").size() == 0 || !point.isNumber() || point.asDouble() <= 0) return null;
        for (var effect : gem.path("effects")) {
          var key = names.get(normalize(effect.path("name").asString("")));
          var level = effect.path("level");
          if (key == null || !level.canConvertToExactIntegral() || level.asDouble() <= 0) return null;
          n.get(series).merge(key, level.asDouble(), Double::sum);
        }
      }
    }
    var seen = new HashSet<String>();
    for (var effect : effects) {
      var key = names.get(normalize(effect.path("name").asString("")));
      if (key == null || seen.contains(key) || !effect.path("effect").isNumber()) return null;
      double a = n.get("질서").getOrDefault(key, 0.0) + n.get("혼돈").getOrDefault(key, 0.0);
      if (a == 0 || a != effect.path("level").asDouble()) return null;
      seen.add(key);
    }
    var union = new HashSet<String>(n.get("질서").keySet());
    union.addAll(n.get("혼돈").keySet());
    return union.size() == seen.size() ? n : null;
  }

  private static Map<String, String> effectNames(KrakenScoreEngine.Tables tables) {
    var names = new HashMap<String, String>();
    for (var entry : tables.get("gemEffectLevels").properties())
      names.put(entry.getKey().replaceAll("\\s+", ""), entry.getKey());
    return names;
  }

  private static String normalize(String name) {
    return name.replaceAll("\\s+", "").replace("아군공격력강화", "아군공격강화");
  }

  private static String series(JsonNode core) {
    var name = core.path("name").asString("");
    for (var series : SERIES) if (name.startsWith(series + "의 ")) return series;
    return null;
  }

  /** 원본 u: 젬이 준 포인트를 뺀 코어(17P 아래로는 내리지 않음), 젬 없음. */
  private static JsonNode reducedCore(JsonNode core) {
    if (!(core.deepCopy() instanceof ObjectNode copy)) return core;
    copy.put("point", basePoint(core));
    copy.putNull("gem");
    return copy;
  }

  /** 원본 s: 젬 포인트 합(한 젬 최대 5, 10의 배수 표기는 ÷10)을 뺀 포인트. */
  private static double basePoint(JsonNode core) {
    double point = core.path("point").asDouble();
    double sum = 0;
    if (core.path("gem").isArray())
      for (var gem : core.path("gem")) {
        var p = gem.path("orderChaosPoint");
        double a = p.isNumber() ? p.asDouble() : Double.NaN;
        double v = a > 5 && a % 10 == 0 ? a / 10 : a;
        sum += Math.min(Math.max(v, 0), 5);
      }
    return sum <= 0 || point <= 17 ? point : Math.max(point - sum, 17);
  }

  /** 젬 옵션 목록을 바꾼 입력(null은 빼고, 남는 것이 없으면 null). */
  private static JsonNode withGemEffects(JsonNode e, List<JsonNode> effects) {
    // 부르는 쪽이 hasArkGrid를 확인했다(입력과 아크 그리드 모두 객체).
    var copy = (ObjectNode) e.deepCopy();
    var arkGrid = (ObjectNode) copy.get("arkGrid");
    var kept = new ArrayList<JsonNode>();
    if (effects != null) for (var effect : effects) if (effect != null) kept.add(effect);
    if (kept.isEmpty()) arkGrid.putNull("gemEffects");
    else {
      ArrayNode array = JsonNodeFactory.instance.arrayNode();
      kept.forEach(array::add);
      arkGrid.set("gemEffects", array);
    }
    return copy;
  }

  /** 원본 d: ⌊(전/후 − 1) × 10⁴⌋ ÷ 100, 후가 0 이하이거나 수가 아니면 null. */
  static Double efficiency(double before, double after) {
    if (!Double.isFinite(before) || !Double.isFinite(after) || after <= 0) return null;
    return Math.floor((before / after - 1) * 1e4) / 100;
  }

}
