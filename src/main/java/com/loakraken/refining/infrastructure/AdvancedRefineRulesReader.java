package com.loakraken.refining.infrastructure;

import com.loakraken.refining.domain.AdvancedRefineChain;
import com.loakraken.refining.domain.AdvancedRefineRules;
import com.loakraken.refining.domain.RefiningRules;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** t4-advanced-refining-rules.json → AdvancedRefineRules. 확률 합이 1이 아니거나 구간이 빠지면 시작하지 않는다. */
final class AdvancedRefineRulesReader {
  private AdvancedRefineRulesReader() {}

  static AdvancedRefineRules read(JsonNode root) {
    var tables = root.path("outcomeByChoice").path("tables");
    if (tables.size() != 4) throw new IllegalStateException("상급 재련 결과 표는 K0~K3 네 줄이어야 합니다.");
    var outcome = new double[4][3];
    for (int k = 0; k < 4; k++) {
      double sum = 0;
      for (int i = 0; i < 3; i++) sum += outcome[k][i] = tables.get(k).get(i).asDouble();
      if (Math.abs(sum - 1) > 1e-9)
        throw new IllegalStateException("상급 재련 결과 표 K" + k + "의 합이 1이 아닙니다.");
    }
    var ancestors = root.path("ancestors");
    var odds =
        new AdvancedRefineChain.Odds(
            outcome,
            ancestors(ancestors.path("stages1to20")),
            ancestors(ancestors.path("stages21to40")),
            ancestors(ancestors.path("enhanced")));
    var gs = root.path("growthSupport");
    var bandsList = new ArrayList<Integer>();
    gs.path("bands").forEach(node -> bandsList.add(node.asInt()));
    var support =
        new AdvancedRefineRules.GrowthSupport(
            List.copyOf(bandsList),
            gs.path("material").asDouble(),
            gs.path("breath").asDouble(),
            gs.path("shard").asDouble(),
            gs.path("silver").asDouble(),
            gs.path("gold").asDouble());
    var bands = new LinkedHashMap<String, List<AdvancedRefineRules.Band>>();
    for (var type : List.of("ARMOR", "WEAPON")) {
      var list = new ArrayList<AdvancedRefineRules.Band>();
      for (var node : root.path("materialsPerTry").path(type)) {
        var materials = new ArrayList<RefiningRules.Material>();
        node.path("materials")
            .forEach(
                m ->
                    materials.add(
                        new RefiningRules.Material(
                            m.path("name").asString(), m.path("count").asLong())));
        var breath = node.path("breath");
        list.add(
            new AdvancedRefineRules.Band(
                node.path("start").asInt(),
                List.copyOf(materials),
                node.path("gold").asLong(),
                new RefiningRules.Material(
                    breath.path("name").asString(), breath.path("count").asLong()),
                node.path("book").asString(),
                node.path("silver").isNull() || node.path("silver").isMissingNode()
                    ? null
                    : node.path("silver").asLong(),
                node.path("singleSource").asBoolean()));
      }
      if (list.stream()
          .map(AdvancedRefineRules.Band::start)
          .toList()
          .equals(List.of(0, 10, 20, 30))) bands.put(type, List.copyOf(list));
      else throw new IllegalStateException("상급 재련 " + type + " 구간은 0·10·20·30이어야 합니다.");
    }
    var single = new ArrayList<String>();
    if (ancestors.path("enhanced").path("singleSource").asBoolean())
      single.add("enhancedAncestors");
    return new AdvancedRefineRules(
        root.path("ruleVersion").asString(),
        Map.of(),
        odds,
        support,
        Map.copyOf(bands),
        List.copyOf(single));
  }

  private static Map<AdvancedRefineChain.Ancestor, Double> ancestors(JsonNode node) {
    var map = new EnumMap<AdvancedRefineChain.Ancestor, Double>(AdvancedRefineChain.Ancestor.class);
    double sum = 0;
    for (var ancestor : AdvancedRefineChain.Ancestor.values()) {
      var value = node.path(ancestor.name());
      if (value.isNumber()) {
        map.put(ancestor, value.asDouble());
        sum += value.asDouble();
      }
    }
    if (Math.abs(sum - 1) > 1e-9) throw new IllegalStateException("상급 재련 선조 확률 합이 1이 아닙니다.");
    return Map.copyOf(map);
  }
}
