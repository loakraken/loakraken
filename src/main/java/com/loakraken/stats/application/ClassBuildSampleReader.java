package com.loakraken.stats.application;

import com.loakraken.stats.domain.ClassBuilds;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비 요약 JSON → 빌드 통계 표본(M12·M26). 집계(SQL로 꺼낸 부분 JSON)와 캐릭터 한 명 칸(저장·캐시 features JSON)이 <b>같은
 * 변환</b>을 쓰게 한 곳에 둔다. 못 읽은 값은 null(그 항목의 분모에서 빠짐)이다.
 */
public final class ClassBuildSampleReader {
  private static final JsonMapper MAPPER = new JsonMapper();

  private ClassBuildSampleReader() {}

  /**
   * 집계 SQL이 꺼낸 부분 JSON: {engravings, gems, cards, points(arkPassive.points), stats,
   * bracelet(braceletLines)}.
   */
  public static ClassBuilds.Sample fromParts(
      BigDecimal itemLevel, String classEngraving, String parts) {
    return sample(itemLevel, classEngraving, MAPPER.readTree(parts));
  }

  /** 장비 요약 features JSON 전체. 집계 SQL의 jsonb_build_object와 같은 경로로 부분을 꺼낸다. */
  public static ClassBuilds.Sample fromFeatures(
      BigDecimal itemLevel, String classEngraving, String features) {
    var node = MAPPER.readTree(features);
    var parts = MAPPER.createObjectNode();
    parts.set("engravings", node.path("engravings"));
    parts.set("gems", node.path("gems"));
    parts.set("cards", node.path("cards"));
    parts.set("points", node.path("arkPassive").path("points"));
    parts.set("stats", node.path("stats"));
    parts.set("bracelet", node.path("braceletLines"));
    return sample(itemLevel, classEngraving, parts);
  }

  private static ClassBuilds.Sample sample(
      BigDecimal itemLevel, String classEngraving, JsonNode node) {
    List<String> engravings = null;
    if (node.path("engravings").isArray()) {
      engravings = new ArrayList<>();
      for (var engraving : node.path("engravings")) {
        var name = text(engraving.path("name"));
        if (name != null) engravings.add(name);
      }
    }
    List<ClassBuilds.Gem> gems = null;
    if (node.path("gems").isArray()) {
      gems = new ArrayList<>();
      for (var gem : node.path("gems"))
        gems.add(
            new ClassBuilds.Gem(
                text(gem.path("kind")), integer(gem.path("level")), text(gem.path("mode"))));
    }
    var cards = node.path("cards");
    var points = node.path("points");
    var stats = node.path("stats");
    List<String> bracelet = null;
    if (node.path("bracelet").isArray()) {
      bracelet = new ArrayList<>();
      for (var line : node.path("bracelet")) {
        var value = text(line);
        if (value != null) bracelet.add(value);
      }
    }
    return new ClassBuilds.Sample(
        itemLevel,
        blankToNull(classEngraving),
        engravings,
        gems,
        cards.isObject()
            ? new ClassBuilds.CardSet(
                text(cards.path("set")),
                integer(cards.path("pieces")),
                integer(cards.path("awakening")))
            : null,
        points.isObject()
            ? new ClassBuilds.Points(
                integer(points.path("evolution")),
                integer(points.path("enlightenment")),
                integer(points.path("leap")))
            : null,
        stats.isObject()
            ? new ClassBuilds.CombatStats(
                integer(stats.path("crit")),
                integer(stats.path("spec")),
                integer(stats.path("swift")),
                integer(stats.path("domination")),
                integer(stats.path("endurance")),
                integer(stats.path("expertise")))
            : null,
        bracelet);
  }

  private static String text(JsonNode node) {
    if (node == null || !node.isString()) return null;
    return blankToNull(node.asString());
  }

  private static Integer integer(JsonNode node) {
    return node != null && node.isIntegralNumber() ? node.asInt() : null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
