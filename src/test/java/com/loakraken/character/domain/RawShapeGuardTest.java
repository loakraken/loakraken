package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 2부 대비 원본 형태 검사(계산 검증 명세 2026-11-28). 이름을 지운 전체 원문 하나에 바뀐 모양을 하나씩 넣어, 각 변경이 자기 규칙만 내는지 본다. 오늘의 실제
 * 모양(미개방 아크 패시브, 전설 장신구, 스톤 없음)은 막지 않아야 한다.
 */
class RawShapeGuardTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static ObjectNode base() {
    try (InputStream in =
        RawShapeGuardTest.class.getResourceAsStream("/fixtures/armory-guard-full.json")) {
      return (ObjectNode) MAPPER.readTree(in);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static ArrayNode equipment(ObjectNode d) {
    return (ArrayNode) d.get("ArmoryEquipment");
  }

  private static ObjectNode part(ObjectNode d, String type) {
    for (var e : equipment(d)) if (type.equals(e.path("Type").asString(""))) return (ObjectNode) e;
    throw new AssertionError("fixture has no " + type);
  }

  private static void replaceInTooltip(ObjectNode item, String from, String to) {
    var tooltip = item.get("Tooltip").asString();
    assertThat(tooltip).contains(from);
    item.put("Tooltip", tooltip.replace(from, to));
  }

  private static void editQuality(ObjectNode item, Consumer<ObjectNode> edit) {
    var tooltip = (ObjectNode) MAPPER.readTree(item.get("Tooltip").asString());
    edit.accept((ObjectNode) tooltip.path("Element_001").path("value"));
    item.put("Tooltip", tooltip.toString());
  }

  @Test
  void fullFixtureHasTodaysShape() {
    var verdict = RawShapeGuard.check(base());

    assertThat(verdict.hard()).isEmpty();
    assertThat(verdict.watch()).isEmpty();
    assertThat(verdict.supported()).isTrue();
  }

  static Stream<Arguments> mutations() {
    return Stream.of(
        Arguments.of(
            "new part 허리띠",
            List.of(RawShapeGuard.UNKNOWN_PART),
            (Consumer<ObjectNode>)
                d -> equipment(d).add(part(d, "투구").deepCopy().put("Type", "허리띠"))),
        Arguments.of(
            "two 목걸이",
            List.of(RawShapeGuard.PART_COUNT),
            (Consumer<ObjectNode>) d -> equipment(d).add(part(d, "목걸이").deepCopy())),
        Arguments.of(
            "weapon without quality",
            List.of(RawShapeGuard.QUALITY),
            (Consumer<ObjectNode>) d -> editQuality(part(d, "무기"), v -> v.remove("qualityValue"))),
        Arguments.of(
            "armor quality as text",
            List.of(RawShapeGuard.QUALITY),
            (Consumer<ObjectNode>)
                d -> editQuality(part(d, "상의"), v -> v.put("qualityValue", "-"))),
        Arguments.of(
            "ancient ring without 연마 효과",
            List.of(RawShapeGuard.POLISH),
            (Consumer<ObjectNode>) d -> replaceInTooltip(part(d, "반지"), "연마 효과", "기타 효과")),
        Arguments.of(
            "bracelet without 팔찌 효과",
            List.of(RawShapeGuard.BRACELET),
            (Consumer<ObjectNode>) d -> replaceInTooltip(part(d, "팔찌"), "팔찌 효과", "기본 효과")),
        Arguments.of(
            "ark passive 깨달음 only",
            List.of(RawShapeGuard.ARK_PASSIVE),
            (Consumer<ObjectNode>)
                d -> {
                  var points = (ArrayNode) d.path("ArkPassive").path("Points");
                  for (int i = points.size() - 1; i >= 0; i--)
                    if (!"깨달음".equals(points.get(i).path("Name").asString(""))) points.remove(i);
                }),
        Arguments.of(
            "new gem 성휘",
            List.of(RawShapeGuard.GEM),
            (Consumer<ObjectNode>)
                d ->
                    ((ObjectNode) d.path("ArmoryGem").path("Gems").get(0))
                        .put("Name", "10레벨 성휘의 보석")),
        Arguments.of(
            "new gem skill text",
            List.of(RawShapeGuard.GEM),
            (Consumer<ObjectNode>)
                d ->
                    ((ObjectNode) d.path("ArmoryGem").path("Effects").path("Skills").get(0))
                        .putArray("Description")
                        .add("치명타 적중률 3% 증가")),
        Arguments.of(
            "ark grid gem Index as text",
            List.of(RawShapeGuard.ARK_GRID),
            (Consumer<ObjectNode>)
                d ->
                    ((ObjectNode) d.path("ArkGrid").path("Slots").get(0).path("Gems").get(0))
                        .put("Index", "0")),
        Arguments.of(
            "core part renamed",
            List.of(RawShapeGuard.CORE_PART_MISSING, RawShapeGuard.UNKNOWN_PART),
            (Consumer<ObjectNode>) d -> part(d, "장갑").put("Type", "손")),
        Arguments.of(
            "no equipment array",
            List.of(RawShapeGuard.NO_EQUIPMENT),
            (Consumer<ObjectNode>) d -> d.putNull("ArmoryEquipment")),
        Arguments.of(
            "ark passive null (not opened) stays clean",
            List.of(),
            (Consumer<ObjectNode>) d -> d.putNull("ArkPassive")),
        Arguments.of(
            "legendary accessory without 연마 효과 stays clean",
            List.of(),
            (Consumer<ObjectNode>)
                d -> {
                  var necklace = part(d, "목걸이");
                  necklace.put("Grade", "전설");
                  replaceInTooltip(necklace, "연마 효과", "기타 효과");
                }),
        Arguments.of(
            "no ability stone stays clean",
            List.of(),
            (Consumer<ObjectNode>)
                d -> {
                  var parts = equipment(d);
                  for (int i = parts.size() - 1; i >= 0; i--)
                    if ("어빌리티 스톤".equals(parts.get(i).path("Type").asString(""))) parts.remove(i);
                }),
        Arguments.of(
            "gem skill description as one string stays clean",
            List.of(),
            (Consumer<ObjectNode>)
                d -> {
                  var skill =
                      (ObjectNode) d.path("ArmoryGem").path("Effects").path("Skills").get(0);
                  skill.put("Description", skill.path("Description").get(0).asString());
                }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mutations")
  void eachChangeGivesExactlyItsRule(
      String label, List<String> expected, Consumer<ObjectNode> mutation) {
    var d = base();
    mutation.accept(d);

    var verdict = RawShapeGuard.check(d);

    assertThat(verdict.hard())
        .as(label)
        .containsExactlyElementsOf(expected.stream().sorted().toList());
    assertThat(verdict.watch()).as(label).isEmpty();
  }

  @Test
  void watchRulesAreCountedButNeverHard() {
    var engraving = base();
    ((ObjectNode) engraving.path("ArmoryEngraving").path("ArkPassiveEffects").get(0))
        .put("SlotLevel", 1);
    var topLevel = base();
    topLevel.putObject("ArmoryNewSection");
    var point = base();
    ((ObjectNode) point.path("ArkPassive").path("Points").get(0)).put("Description", "7단계");

    assertThat(RawShapeGuard.check(engraving).watch())
        .containsExactly(RawShapeGuard.ENGRAVING_KEY_SET);
    assertThat(RawShapeGuard.check(topLevel).watch()).containsExactly(RawShapeGuard.TOP_LEVEL_KEY);
    assertThat(RawShapeGuard.check(point).watch())
        .containsExactly(RawShapeGuard.POINT_DESCRIPTION_FORM);
    assertThat(Stream.of(engraving, topLevel, point).map(RawShapeGuard::check).map(v -> v.hard()))
        .allMatch(List::isEmpty);
  }

  @Test
  void todaysPointDescriptionFormsAreNotWatched() {
    for (var form : List.of("6랭크 21레벨", "", "미개방", "?랭크 ?레벨")) {
      var d = base();
      ((ObjectNode) d.path("ArkPassive").path("Points").get(0)).put("Description", form);

      assertThat(RawShapeGuard.check(d).watch()).as(form).isEmpty();
    }
  }

  @Test
  void tierThreeGemsAreTodaysShape() {
    // 3티어 보석(멸화·홍염)을 아직 낀 캐릭터는 오늘의 모양이다(저장 원문 표본에는 없어서 따로 본다).
    for (var name : List.of("10레벨 멸화의 보석", "7레벨 홍염의 보석", "9레벨 멸화의 보석 (귀속)")) {
      var d = base();
      ((ObjectNode) d.path("ArmoryGem").path("Gems").get(0)).put("Name", name);

      assertThat(RawShapeGuard.check(d).hard()).as(name).isEmpty();
    }
  }

  @Test
  void missingSectionsAreTodaysShape() {
    var d = base();
    d.remove(List.of("ArmoryGem", "ArkGrid", "ArmoryEngraving", "ArkPassive"));

    assertThat(RawShapeGuard.check(d).hard()).isEmpty();
    assertThat(RawShapeGuard.check(d).watch()).isEmpty();
  }

  @Test
  void ruleGroupIsTheFixedTagValue() {
    assertThat(RawShapeGuard.Verdict.group(RawShapeGuard.CORE_PART_MISSING)).isEqualTo("H1");
    assertThat(RawShapeGuard.Verdict.group(RawShapeGuard.POINT_DESCRIPTION_FORM)).isEqualTo("W3");
  }

  /** 코덱스 #1125 P2: 닫는 '>' 없는 '<'가 많아도 한 번 훑기라 빠르고, 상한을 넘는 칸은 읽지 않는다(빈 값). */
  @Test
  void stripIsLinearAndRespectsTheOfficialTextLimit() {
    var underLimit = "<".repeat(OfficialTextLimit.MAX_CHARS - 1) + "x";
    long started = System.nanoTime();
    var stripped = RawShapeGuard.strip(underLimit);
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;

    assertThat(stripped).isEqualTo(underLimit);
    assertThat(elapsedMs)
        .as("strip of %d '<' took %d ms", underLimit.length(), elapsedMs)
        .isLessThan(100);
    assertThat(RawShapeGuard.strip("<".repeat(OfficialTextLimit.MAX_CHARS + 1))).isEmpty();
  }

  /** 한 번 훑기 태그 떼기는 예전 패턴('<[^>]+>' → 공백)과 같은 결과다(빈 '<>', 태그 안의 '<', 닫히지 않은 '<' 포함). */
  @Test
  void stripTagsMatchesTheOldPatternExactly() {
    var random = new java.util.Random(1125);
    var alphabet = new char[] {'<', '>', 'a', ' ', '가'};
    for (int n = 0; n < 20_000; n++) {
      var text = new StringBuilder();
      int length = random.nextInt(24);
      for (int i = 0; i < length; i++) text.append(alphabet[random.nextInt(alphabet.length)]);
      var input = text.toString();

      assertThat(RawShapeGuard.stripTags(input))
          .as(input)
          .isEqualTo(input.replaceAll("<[^>]+>", " "));
    }
    assertThat(RawShapeGuard.stripTags("<FONT COLOR='#A9D0F5'>연마 효과</FONT>")).isEqualTo(" 연마 효과 ");
  }

  @Test
  void nullRawHasNoEquipment() {
    assertThat(RawShapeGuard.check((JsonNode) null).hard())
        .containsExactly(RawShapeGuard.NO_EQUIPMENT);
  }
}
