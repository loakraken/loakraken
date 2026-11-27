package com.loakraken.character.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * 2부 대비 원본 형태 검사: 공식 armories 응답이 오늘 알려진 모양인지 본다(계산 검증 명세 2026-11-28, H1–H7 · W1–W3).
 *
 * <p>HARD 규칙(H)이 하나라도 어긋나면 그 원문은 전투력·스펙업 모델이 아는 모양이 아니다. WATCH 규칙(W)은 세기만 하고 막을 이유가 되지 않는다. 이 클래스는
 * 판정만 하고, 세기·로그·막기는 부르는 쪽이 정한다. 규칙 id는 고정된 문자열이라 지표 태그로 써도 개수가 늘지 않는다.
 *
 * <p>오늘의 실제 모양은 막지 않는다: 어빌리티 스톤·문장·보주·부적·완갑·장신구가 없는 경우, 고대·유물이 아닌 장신구의 연마 효과 없음, 아크 패시브 미개방(null 또는
 * 빈 Points), 보석·아크 그리드 없음, 최상위 키 일부 없음.
 */
public final class RawShapeGuard {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final Pattern TAG = Pattern.compile("<[^>]+>");
  private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private static final Map<String, Integer> MAX_PARTS =
      Map.ofEntries(
          Map.entry("무기", 1),
          Map.entry("투구", 1),
          Map.entry("상의", 1),
          Map.entry("하의", 1),
          Map.entry("장갑", 1),
          Map.entry("어깨", 1),
          Map.entry("목걸이", 1),
          Map.entry("귀걸이", 2),
          Map.entry("반지", 2),
          Map.entry("어빌리티 스톤", 1),
          Map.entry("팔찌", 1),
          Map.entry("나침반", 1),
          Map.entry("부적", 1),
          Map.entry("문장", 1),
          Map.entry("보주", 1),
          Map.entry("완갑", 1));
  private static final List<String> CORE_PARTS = List.of("무기", "투구", "상의", "하의", "장갑", "어깨");
  private static final Set<String> ACCESSORIES = Set.of("목걸이", "귀걸이", "반지");
  private static final Set<String> QUALITY_PARTS =
      Set.of("무기", "투구", "상의", "하의", "장갑", "어깨", "목걸이", "귀걸이", "반지");
  private static final Set<String> POLISH_GRADES = Set.of("고대", "유물");
  private static final Set<String> POINT_NAMES = Set.of("진화", "깨달음", "도약");
  // 3티어 멸화·홍염도 오늘의 모양이다(전투력 보석 모델·장비 요약이 읽음, 2026-10 표본에 다수).
  private static final Pattern GEM_NAME =
      Pattern.compile("^\\d+레벨 (겁화|작열|광휘|멸화|홍염)의 보석( \\(귀속\\))?$");
  private static final List<Pattern> GEM_SKILL =
      List.of(
          Pattern.compile("^피해 [\\d.]+% 증가$"),
          Pattern.compile("^재사용 대기시간 [\\d.]+% 감소$"),
          Pattern.compile("^지원 효과 [\\d.]+ ?% 증가$"));
  private static final Pattern GRID_SLOT = Pattern.compile("^(질서|혼돈)의 (해|달|별) 코어 : .+$");
  private static final Set<String> ENGRAVING_KEYS =
      Set.of("AbilityStoneLevel", "Description", "Grade", "Level", "Name");
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          "ArmoryProfile",
          "ArmoryEquipment",
          "ArmoryAvatars",
          "ArmorySkills",
          "ArmoryEngraving",
          "ArmoryCard",
          "ArmoryGem",
          "ArkPassive",
          "ArkGrid",
          "ColosseumInfo",
          "Collectibles");
  private static final Pattern POINT_DESCRIPTION =
      Pattern.compile("^((\\d+|\\?)랭크 (\\d+|\\?)레벨|미개방|)$");

  public static final String NO_EQUIPMENT = "H1_NO_EQUIPMENT";
  public static final String UNKNOWN_PART = "H1_UNKNOWN_PART";
  public static final String PART_COUNT = "H1_PART_COUNT";
  public static final String CORE_PART_MISSING = "H1_CORE_PART_MISSING";
  public static final String QUALITY = "H2_QUALITY";
  public static final String POLISH = "H3_POLISH";
  public static final String BRACELET = "H4_BRACELET";
  public static final String ARK_PASSIVE = "H5_ARK_PASSIVE";
  public static final String GEM = "H6_GEM";
  public static final String ARK_GRID = "H7_ARK_GRID";
  public static final String ENGRAVING_KEY_SET = "W1_ENGRAVING_KEYS";
  public static final String TOP_LEVEL_KEY = "W2_TOP_LEVEL_KEY";
  public static final String POINT_DESCRIPTION_FORM = "W3_POINT_DESCRIPTION";

  private RawShapeGuard() {}

  /** 판정 결과. {@code hard}가 비어 있으면 오늘의 모양이다. 둘 다 정렬된 규칙 id 목록. */
  public record Verdict(List<String> hard, List<String> watch) {
    public Verdict {
      hard = List.copyOf(hard);
      watch = List.copyOf(watch);
    }

    public boolean supported() {
      return hard.isEmpty();
    }

    /** 지표 태그용 규칙 묶음(H1…H7, W1…W3). 세부 id의 앞 두 글자. */
    public static String group(String ruleId) {
      return ruleId.substring(0, 2);
    }
  }

  public static Verdict check(JsonNode armory) {
    var hard = new TreeSet<String>();
    var watch = new TreeSet<String>();
    JsonNode root = armory == null ? MissingNode.getInstance() : armory;
    var equipment = root.path("ArmoryEquipment");
    if (!equipment.isArray()) hard.add(NO_EQUIPMENT);
    else checkEquipment(equipment, hard);
    checkArkPassive(root.path("ArkPassive").path("Points"), hard, watch);
    checkGems(root.path("ArmoryGem"), hard);
    checkArkGrid(root.path("ArkGrid").path("Slots"), hard);
    checkEngravings(root.path("ArmoryEngraving").path("ArkPassiveEffects"), watch);
    if (root.isObject())
      for (var entry : root.properties())
        if (!TOP_LEVEL_KEYS.contains(entry.getKey())) {
          watch.add(TOP_LEVEL_KEY);
          break;
        }
    return new Verdict(List.copyOf(hard), List.copyOf(watch));
  }

  private static void checkEquipment(JsonNode equipment, Set<String> hard) {
    var count = new HashMap<String, Integer>();
    for (var item : equipment) {
      var type = item.path("Type").asString("");
      var seen = count.merge(type, 1, Integer::sum);
      var max = MAX_PARTS.get(type);
      if (max == null) hard.add(UNKNOWN_PART);
      else if (seen > max) hard.add(PART_COUNT);
      var tooltip = item.path("Tooltip").asString("");
      if (QUALITY_PARTS.contains(type) && !qualityIsNumber(tooltip)) hard.add(QUALITY);
      if (ACCESSORIES.contains(type)
          && POLISH_GRADES.contains(item.path("Grade").asString(""))
          && !strip(tooltip).contains("연마 효과")) hard.add(POLISH);
      if ("팔찌".equals(type) && !strip(tooltip).contains("팔찌 효과")) hard.add(BRACELET);
    }
    for (var core : CORE_PARTS) if (!count.containsKey(core)) hard.add(CORE_PART_MISSING);
  }

  private static boolean qualityIsNumber(String tooltip) {
    try {
      return MAPPER
          .readTree(tooltip)
          .path("Element_001")
          .path("value")
          .path("qualityValue")
          .isNumber();
    } catch (JacksonException exception) {
      return false;
    }
  }

  private static void checkArkPassive(JsonNode points, Set<String> hard, Set<String> watch) {
    if (!points.isArray() || points.isEmpty()) return;
    var names = new HashSet<String>();
    var unknown = false;
    for (var point : points) {
      var name = point.path("Name").asString("");
      names.add(name);
      if (!POINT_NAMES.contains(name)) unknown = true;
      var description = point.path("Description");
      var text =
          description.isNull() || description.isMissingNode() ? "" : description.asString("");
      if (!POINT_DESCRIPTION.matcher(strip(text)).matches()) watch.add(POINT_DESCRIPTION_FORM);
    }
    if (unknown || !names.contains("진화") || !names.contains("도약")) hard.add(ARK_PASSIVE);
  }

  private static void checkGems(JsonNode gem, Set<String> hard) {
    for (var g : gem.path("Gems"))
      if (!GEM_NAME.matcher(strip(g.path("Name").asString(""))).matches()) hard.add(GEM);
    for (var skill : gem.path("Effects").path("Skills")) {
      var description = skill.path("Description");
      Iterable<JsonNode> lines = description.isArray() ? description : List.of(description);
      for (var line : lines) {
        if (line.isMissingNode() || line.isNull()) continue;
        var text = strip(line.asString(""));
        if (GEM_SKILL.stream().noneMatch(p -> p.matcher(text).matches())) hard.add(GEM);
      }
    }
  }

  private static void checkArkGrid(JsonNode slots, Set<String> hard) {
    for (var slot : slots) {
      if (!GRID_SLOT.matcher(strip(slot.path("Name").asString(""))).matches()) hard.add(ARK_GRID);
      for (var g : slot.path("Gems"))
        if (!g.path("Index").isNumber() || !g.path("IsActive").isBoolean()) hard.add(ARK_GRID);
    }
  }

  private static void checkEngravings(JsonNode effects, Set<String> watch) {
    for (var effect : effects) {
      var keys = new HashSet<String>();
      for (var entry : effect.properties()) keys.add(entry.getKey());
      if (!keys.equals(ENGRAVING_KEYS)) watch.add(ENGRAVING_KEY_SET);
    }
  }

  private static String strip(String text) {
    return SPACES.matcher(TAG.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
  }
}
