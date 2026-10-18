package com.loakraken.character.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 크라켄 점수 입력 변환(#559) 고정 테스트: 같은 시각에 받은 기준 캐릭터 6명(l01~l06)과 서폿 공증 조건(스킬 + 빠른 준비)이 참인 서폿 표본 1명(l07)의
 * 공식 armories 응답(변환기가 읽는 부분만, 식별 정보 없음)을 바꾼 입력이 비교 기준 B 입력의 '엔진이 읽는 경로' 값과 같고, 그 입력의 점수가 비교 기준 B
 * 점수와 double 끝자리까지 같은지 본다.
 */
class KrakenScoreInputMapperTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final KrakenScoreEngine.Tables TABLES = tables();
  private static final Pattern SEGMENT = Pattern.compile("([^\\[]+)((?:\\[\\d+\\])*)");

  private static KrakenScoreEngine.Tables tables() {
    try {
      var doc = read("reference-tables");
      return new KrakenScoreEngine.Tables(doc.path("savedAt").asString(""), doc.path("tables"));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static JsonNode read(String id) throws IOException {
    return MAPPER.readTree(
        new ClassPathResource("kraken-score/" + id + ".json")
            .getContentAsString(StandardCharsets.UTF_8));
  }

  /** "a.b[2].c[0]" 같은 경로의 값(없으면 missing). */
  private static JsonNode at(JsonNode root, String path) {
    var node = root;
    for (var part : path.split("\\.")) {
      var m = SEGMENT.matcher(part);
      if (!m.matches()) return tools.jackson.databind.node.MissingNode.getInstance();
      node = node.path(m.group(1));
      var idx = m.group(2);
      if (!idx.isEmpty())
        for (var i : idx.substring(1, idx.length() - 1).split("\\]\\["))
          node = node.path(Integer.parseInt(i));
    }
    return node;
  }

  private static boolean same(JsonNode expected, JsonNode actual) {
    if (expected.isNull()) return actual.isNull() || actual.isMissingNode();
    if (expected.isNumber()) return actual.isNumber() && expected.asDouble() == actual.asDouble();
    return expected.equals(actual);
  }

  @ParameterizedTest
  @ValueSource(strings = {"l01", "l02", "l03", "l04", "l05", "l06", "l07"})
  void 공식_원문에서_만든_입력이_비교_기준_입력의_엔진이_읽는_값과_같다(String id) throws IOException {
    var doc = read(id);
    var input = KrakenScoreInputMapper.from(doc.path("raw"));
    var mismatches = new ArrayList<String>();
    for (var entry : doc.path("expected").properties()) {
      var path = entry.getKey();
      if (path.equals("skills") || path.contains("[]")) continue;
      var actual = at(input, path);
      if (!same(entry.getValue(), actual))
        mismatches.add(path + ": 기대 " + entry.getValue() + " / 변환 " + actual);
    }
    // 서폿 공증 가동률이 읽는 스킬 이름·고른 트라이포드 이름(순서까지).
    var skills = input.path("skills");
    var expectedSkills = doc.path("expected").path("skills");
    assertThat(skills.size()).as(id + " 스킬 수").isEqualTo(expectedSkills.size());
    for (int i = 0; i < expectedSkills.size(); i++) {
      var want = expectedSkills.get(i);
      var got = skills.get(i);
      assertThat(got.path("name").asString("")).isEqualTo(want.path("name").asString(""));
      var names = new ArrayList<String>();
      got.path("tripods").forEach(t -> names.add(t.path("name").asString("")));
      var wantNames = new ArrayList<String>();
      want.path("tripods").forEach(t -> wantNames.add(t.asString("")));
      assertThat(names).as(id + " " + want.path("name").asString("")).isEqualTo(wantNames);
    }
    assertThat(mismatches).as(id).isEmpty();
    assertThat(doc.path("expected").size()).as(id + " 대조한 경로 수").isGreaterThan(100);
  }

  @ParameterizedTest
  @ValueSource(strings = {"l01", "l02", "l03", "l04", "l05", "l06", "l07"})
  void 변환_입력의_점수가_비교_기준_점수와_끝자리까지_같다(String id) throws IOException {
    var doc = read(id);
    var input = KrakenScoreInputMapper.from(doc.path("raw"));
    double score =
        "SUPPORT".equals(doc.path("role").asString(""))
            ? KrakenScoreSupport.score(input, TABLES)
            : KrakenScoreEngine.dealerScore(input, TABLES);
    assertThat(score).isEqualTo(doc.path("expectedScore").asDouble());
  }

  /**
   * 스톤 이동속도 감소 레벨의 점수 영향(원본 함수 값): m01~m03은 실제 질풍노도 원문과 그 감소 줄만 Lv.1·2로 바꾼 것 — 팔찌에 치명 관련 옵션이 없어 그
   * 분기가 꺼져 세 점수가 같다. m04·m05는 질풍노도 + 팔찌 치명 옵션 + 이동속도 상한 아래 합성(감소 Lv.1·2)이라 점수가 바뀐다.
   */
  @ParameterizedTest
  @ValueSource(strings = {"m01", "m02", "m03", "m04", "m05"})
  void 스톤_이동속도_감소_레벨의_점수_영향이_원본과_끝자리까지_같다(String id) throws IOException {
    var doc = read(id);
    var input = KrakenScoreInputMapper.from(doc.path("raw"));
    assertThat(KrakenScoreEngine.dealerScore(input, TABLES))
        .as(id + " " + doc.path("note").asString(""))
        .isEqualTo(doc.path("expectedScore").asDouble());
  }

  @Test
  void 빈_원문이나_모르는_모양이어도_예외_없이_빈_입력을_만든다() throws IOException {
    for (var raw :
        new String[] {"{}", "{\"ArmoryEquipment\":[{\"Type\":\"무기\",\"Tooltip\":\"깨진\"}]}", "[]"}) {
      var input = KrakenScoreInputMapper.from(MAPPER.readTree(raw));
      assertThat(input.path("profile").path("supportCheck").asBoolean(true)).isFalse();
      assertThat(input.path("gem").path("gems").size()).isZero();
    }
  }

  @Test
  void 서포터_여부는_깨달음_노드로_정하고_발키리는_해방자가_서포터다() throws IOException {
    for (var c :
        new String[][] {
          {"발키리", "해방자", "true"},
          {"발키리", "빛의 기사", "false"},
          {"도화가", "만개", "true"},
          {"바드", "진실된 용맹", "false"}
        }) {
      var raw =
          MAPPER.readTree(
              "{\"ArmoryProfile\":{\"CharacterClassName\":\""
                  + c[0]
                  + "\"},\"ArkPassive\":{\"Title\":\""
                  + c[1]
                  + "\",\"Effects\":[{\"Name\":\"깨달음\",\"Description\":\"<FONT color='#83E9FF'>깨달음</FONT> 1티어 <FONT color='#83E9FF'>"
                  + c[1]
                  + " Lv.1</FONT>\"}]}}");
      var input = KrakenScoreInputMapper.from(raw);
      assertThat(input.path("profile").path("supportCheck").asBoolean())
          .as(c[0] + " " + c[1])
          .isEqualTo(Boolean.parseBoolean(c[2]));
    }
  }

  @Test
  void 어빌리티_스톤의_감소_각인_줄도_원문_순서대로_넣는다() throws IOException {
    // 합성: l05(딜러)의 스톤 무작위 각인에 '이동속도 감소 Lv.2'를 넣는다 — 질풍노도 등은 이 레벨을 읽는다.
    var doc = read("l05");
    var raw = doc.path("raw").deepCopy();
    for (var item : raw.path("ArmoryEquipment")) {
      if (!"어빌리티 스톤".equals(item.path("Type").asString(""))) continue;
      var tooltip = item.path("Tooltip").asString("");
      var replaced =
          tooltip.replaceFirst(
              "\\[<FONT COLOR='#FE2E2E'>[^<]+</FONT>\\]", "[<FONT COLOR='#FE2E2E'>이동속도 감소</FONT>]");
      replaced = replaced.replaceFirst("(이동속도 감소</FONT>\\][^L]*)Lv\\.0", "$1Lv.2");
      ((tools.jackson.databind.node.ObjectNode) item).put("Tooltip", replaced);
    }
    var base = KrakenScoreInputMapper.from(doc.path("raw"));
    var input = KrakenScoreInputMapper.from(raw);
    var options = new ArrayList<String>();
    input
        .path("armory")
        .path("abilityStone")
        .path("option")
        .forEach(o -> options.add(o.asString("")));
    var baseOptions = new ArrayList<String>();
    base.path("armory")
        .path("abilityStone")
        .path("option")
        .forEach(o -> baseOptions.add(o.asString("")));
    assertThat(baseOptions).hasSize(3).last().asString().endsWith("Lv.0");
    assertThat(options).containsExactly(baseOptions.get(0), baseOptions.get(1), "이동속도 감소 Lv.2");
    assertThat(input.path("armory").path("abilityStone").path("attackbonus").asDouble())
        .isEqualTo(base.path("armory").path("abilityStone").path("attackbonus").asDouble());
    // 이 레벨이 점수에 주는 영향(질풍노도 속도 계산)은 엔진 고정 테스트·계산 검증 대조가 원본 값으로 확인한다.
  }

  @Test
  void 응답_때_점수_계산은_한_번에_수_밀리초_안이다() throws IOException {
    // 연결 PR 조건: 응답 p95 증가 ≤ 5ms. 응답 때 하는 일은 캐시에 든 입력으로 점수 한 번 계산뿐이다(변환은 공식 조회 때 한 번).
    var inputs = new ArrayList<JsonNode>();
    var roles = new ArrayList<Boolean>();
    for (var id : Arrays.asList("l01", "l02", "l03", "l04", "l05", "l06", "l07")) {
      var doc = read(id);
      inputs.add(KrakenScoreInputMapper.from(doc.path("raw")));
      roles.add("SUPPORT".equals(doc.path("role").asString("")));
    }
    for (int i = 0; i < 300; i++) run(inputs, roles, i);
    int n = 1200;
    long[] nanos = new long[n];
    for (int i = 0; i < n; i++) {
      long start = System.nanoTime();
      run(inputs, roles, i);
      nanos[i] = System.nanoTime() - start;
    }
    Arrays.sort(nanos);
    double p95 = nanos[(int) Math.ceil(n * 0.95) - 1] / 1e6;
    System.out.printf("kraken-score compute p50=%.3fms p95=%.3fms%n", nanos[n / 2] / 1e6, p95);
    assertThat(p95).isLessThan(5.0);
  }

  private static double run(ArrayList<JsonNode> inputs, ArrayList<Boolean> roles, int i) {
    var input = inputs.get(i % inputs.size());
    return roles.get(i % roles.size())
        ? KrakenScoreSupport.score(input, TABLES)
        : KrakenScoreEngine.dealerScore(input, TABLES);
  }
}
