package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.stats.domain.ClassBuilds;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 캐릭터 한 명의 칸(cellsOf)이 집계(aggregate)에서 그 캐릭터가 세지는 칸과 같은지(M26 2단계, 웹 재구현 제거 조건). 표본은 저장 features
 * JSON을 집계와 같은 변환(ClassBuildSampleReader)으로 읽는다. 같은 표본 5개를 집계에 넣으면 모든 칸이 최소 칸(5)을 넘어 그 캐릭터의 칸이 그대로
 * 나온다.
 */
class ClassBuildCellsConsistencyTest {
  private static final BigDecimal BRACELET_MIN = new BigDecimal("1680");

  /** 12가지: 동률·못 읽음·빈 목록·3티어 보석·팔찌 기준 미만·특성 일부 없음 등. */
  private static final List<Object[]> VARIANTS =
      List.of(
          variant(
              "1805",
              "광기",
              full("세상을 구하는 빛", 6, 30, "1851, 600, 100, 40, 30, 20", "\"치명 +100\",\"특화 +90\"")),
          variant(
              "1770",
              "광전사의 비기",
              full("카제로스의 군단장", 6, 30, "1851, 1851, 100, 40, 30, 20", "\"신속 +100\",\"치명 +90\"")),
          variant(
              "1700", "축복의 오라", full("알고 보면", 3, 0, "100, 1700, 100, 40, 30, 20", "\"신속 +100\"")),
          variant(
              "1660", "광기", full("부르는 소리 있도다", 3, 15, "100, 100, 1700, 40, 30, 20", "\"신속 +100\"")),
          variant(
              "1720",
              "광기",
              "{\"engravings\":null,\"gems\":null,\"cards\":null,\"arkPassive\":null,\"stats\":null,\"braceletLines\":null}"),
          variant(
              "1690",
              "",
              "{\"engravings\":[],\"gems\":[],\"cards\":{\"set\":null,\"pieces\":null,\"awakening\":null},\"arkPassive\":{\"points\":{\"evolution\":140,\"enlightenment\":null,\"leap\":70}},\"stats\":{\"crit\":1800,\"spec\":null,\"swift\":1,\"domination\":1,\"endurance\":1,\"expertise\":1},\"braceletLines\":[]}"),
          variant(
              "1750",
              "광기",
              full(
                  "세상을 구하는 빛",
                  6,
                  30,
                  "500, 500, 500, 500, 500, 500",
                  "\"치명 +100\",\"치명 +50\",\"치명타 피해 +8.40%\"")),
          variant(
              "1800",
              "광기",
              gemsOnly(
                  "[{\"kind\":\"겁화\",\"level\":10,\"mode\":\"DAMAGE\"},{\"kind\":\"작열\",\"level\":9,\"mode\":\"COOLDOWN\"},{\"kind\":\"멸화\",\"level\":10,\"mode\":\"DAMAGE\"},{\"kind\":\"광휘\",\"level\":8,\"mode\":\"DAMAGE\"},{\"kind\":\"겁화\",\"level\":null,\"mode\":\"DAMAGE\"},{\"kind\":\"겁화\",\"level\":7,\"mode\":null}]")),
          variant(
              "1640",
              "광기",
              full("세상을 구하는 빛", 6, 30, "100, 1700, 100, 40, 30, 20", "\"치명 +100\",\"특화 +90\"")),
          variant(
              "1680", "광기", full("세상을 구하는 빛", 6, null, "100, 1700, 100, 40, 30, 20", "\"특화 +90\"")),
          variant(
              "1710.83",
              "광기",
              full("힘찬 화염의 숨결", 6, 24, "1700, 100, 1700, 40, 30, 20", "\"신속 +100\",\"치명 +90\"")),
          variant(
              "1785", "광전사의 비기", full("세상을 구하는 빛", 6, 30, "100, 100, 100, 100, 100, 1700", "")));

  @Test
  void 캐릭터_칸은_같은_표본을_집계했을_때_그_캐릭터가_세지는_칸과_같다() {
    for (var variant : VARIANTS) {
      var sample =
          ClassBuildSampleReader.fromFeatures(
              new BigDecimal((String) variant[0]), (String) variant[1], (String) variant[2]);
      var cells = ClassBuilds.cellsOf(sample, BRACELET_MIN);
      var five = Collections.nCopies(5, sample);
      var result = ClassBuilds.aggregate(five, five, ClassBuilds.MAX_LIMIT, BRACELET_MIN);
      var label = (String) variant[2];

      assertThat(keys(result.classEngravings()))
          .as(label)
          .isEqualTo(single(cells.classEngraving()));
      assertThat(keys(result.engravingCombos()))
          .as(label)
          .isEqualTo(single(cells.engravingCombo()));
      assertThat(new HashSet<>(keys(result.engravings())))
          .as(label)
          .isEqualTo(
              cells.engravings() == null ? new HashSet<>() : new HashSet<>(cells.engravings()));
      assertThat(keys(result.evolutionPoints()))
          .as(label)
          .isEqualTo(single(cells.evolutionPoints()));
      assertThat(keys(result.enlightenmentPoints()))
          .as(label)
          .isEqualTo(single(cells.enlightenmentPoints()));
      assertThat(keys(result.leapPoints())).as(label).isEqualTo(single(cells.leapPoints()));
      assertThat(new HashSet<>(keys(result.damageGems())))
          .as(label)
          .isEqualTo(
              cells.damageGems() == null ? new HashSet<>() : new HashSet<>(cells.damageGems()));
      assertThat(result.damageGems().known())
          .as(label)
          .isEqualTo(cells.damageGems() == null ? 0 : 5L * cells.damageGems().size());
      assertThat(new HashSet<>(keys(result.cooldownGems())))
          .as(label)
          .isEqualTo(
              cells.cooldownGems() == null ? new HashSet<>() : new HashSet<>(cells.cooldownGems()));
      assertThat(keys(result.cardSets())).as(label).isEqualTo(single(cells.cardSet()));
      var topStats = result.topStats();
      assertThat(topStats.cells().stream().map(ClassBuilds.Cell::key).toList())
          .as(label)
          .isEqualTo(single(cells.topStat() == null ? null : cells.topStat().stat()));
      assertThat(topStats.ties())
          .as(label)
          .isEqualTo(cells.topStat() != null && cells.topStat().tie() ? 5 : 0);
      var bracelet = result.braceletStats();
      assertThat(
              bracelet.cells().stream()
                  .map(ClassBuilds.Cell::key)
                  .collect(java.util.stream.Collectors.toSet()))
          .as(label)
          .isEqualTo(
              cells.braceletStats() == null
                  ? new HashSet<>()
                  : new HashSet<>(cells.braceletStats()));
      if (!cells.braceletEligible()) assertThat(bracelet.known() + bracelet.unread()).isZero();
      // 못 읽은 항목은 분모에서도 빠진다(칸 null ↔ known 0).
      assertThat(result.cardSets().known()).as(label).isEqualTo(cells.cardSet() == null ? 0 : 5);
      assertThat(result.engravingCombos().known())
          .as(label)
          .isEqualTo(cells.engravingCombo() == null ? 0 : 5);
    }
  }

  @Test
  void 저장_부분_JSON과_전체_features_JSON은_같은_표본이_된다() {
    var features = full("세상을 구하는 빛", 6, 30, "1851, 600, 100, 40, 30, 20", "\"치명 +100\"");
    var parts =
        features
            .replace("\"arkPassive\":{\"points\":", "\"points\":")
            .replace("}},\"stats\"", "},\"stats\"")
            .replace("\"braceletLines\"", "\"bracelet\"");
    var fromFeatures = ClassBuildSampleReader.fromFeatures(BigDecimal.TEN, "광기", features);
    var fromParts = ClassBuildSampleReader.fromParts(BigDecimal.TEN, "광기", parts);
    assertThat(fromFeatures).isEqualTo(fromParts);
  }

  private static <T> List<T> keys(ClassBuilds.Section<T> section) {
    return section.cells().stream().map(ClassBuilds.Cell::key).toList();
  }

  private static <T> List<T> single(T value) {
    var list = new ArrayList<T>();
    if (value != null) list.add(value);
    return list;
  }

  private static Object[] variant(String itemLevel, String classEngraving, String features) {
    return new Object[] {itemLevel, classEngraving, features};
  }

  private static String full(
      String set, int pieces, Integer awakening, String stats, String bracelet) {
    var s = stats.split(",\\s*");
    return "{\"engravings\":[{\"name\":\"원한\",\"grade\":\"유물\",\"level\":4,\"stone\":3},"
        + "{\"name\":\"아드레날린\",\"grade\":\"유물\",\"level\":4,\"stone\":0},"
        + "{\"name\":\"예리한 둔기\",\"grade\":\"유물\",\"level\":4,\"stone\":0}],"
        + "\"gems\":[{\"kind\":\"겁화\",\"level\":10,\"mode\":\"DAMAGE\"},{\"kind\":\"겁화\",\"level\":9,\"mode\":\"DAMAGE\"},"
        + "{\"kind\":\"작열\",\"level\":8,\"mode\":\"COOLDOWN\"}],"
        + "\"cards\":{\"set\":\""
        + set
        + "\",\"pieces\":"
        + pieces
        + ",\"awakening\":"
        + awakening
        + ",\"elementPct\":0},"
        + "\"arkPassive\":{\"points\":{\"evolution\":140,\"enlightenment\":101,\"leap\":70}},"
        + "\"stats\":{\"crit\":"
        + s[0]
        + ",\"spec\":"
        + s[1]
        + ",\"swift\":"
        + s[2]
        + ",\"domination\":"
        + s[3]
        + ",\"endurance\":"
        + s[4]
        + ",\"expertise\":"
        + s[5]
        + "},"
        + "\"braceletLines\":["
        + bracelet
        + "]}";
  }

  private static String gemsOnly(String gems) {
    return "{\"engravings\":null,\"gems\":"
        + gems
        + ",\"cards\":null,\"arkPassive\":null,\"stats\":null,\"braceletLines\":null}";
  }
}
