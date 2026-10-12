package com.loakraken.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.refining.domain.RefiningRules;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 계산 검증팀 표본 회귀(refining/ref-b-2026-10-09.json). 표본의 expected·tolerance를 그대로 쓴다. 표본 파일은 계산 검증팀이
 * 관리하고, 백엔드는 읽기만 한다. 같은 사례의 equivalentRequest(숨결 대신 책)도 같은 값이어야 한다.
 */
class RefiningVerificationTest {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final String SAMPLE = "/verification/refining/ref-b-2026-10-09.json";
  private static final String RULES = "/refining/t4-refining-rules.json";

  @Test
  void 표본은_현재_규칙_버전과_같다() {
    assertThat(read(SAMPLE).path("ruleVersion").asText()).isEqualTo("t4-refining-v1");
    assertThat(read(SAMPLE).path("cases").size()).isGreaterThanOrEqualTo(3);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void 비교_기준_B_표본과_첫_시도_확률_최대_시도_기대_시도가_같다(String id, JsonNode request, JsonNode expected) {
    var rules = MAPPER.readValue(stream(RULES), RefiningRules.class);
    var grade = RefiningRules.Grade.valueOf(request.path("grade").asText());
    var type = RefiningRules.EquipmentType.valueOf(request.path("equipmentType").asText());
    var options =
        new RefiningPlan.Options(
            request.path("fromStage").asInt(),
            request.path("toStage").asInt(),
            request.path("useBreath").asBoolean(),
            request.path("useBook").asBoolean(),
            request.path("growthSupport").asBoolean(false),
            0,
            0);
    var stage = RefiningPlan.calculate(rules.track(grade, type), grade, options).stages().getLast();
    assertThat(stage.firstAttemptSuccessRateBp())
        .as(id)
        .isEqualTo(expected.path("firstAttemptSuccessRateBp").asInt());
    assertThat(stage.maxAttempts()).as(id).isEqualTo(expected.path("maxAttempts").asInt());
    var tolerance = expected.path("tolerance").decimalValue();
    assertThat(stage.expectedAttempts())
        .as(id)
        .isCloseTo(
            expected.path("expectedAttemptsFromRefBTable").decimalValue(),
            org.assertj.core.data.Offset.offset(tolerance.max(new BigDecimal("0.005"))));
  }

  static Stream<Arguments> cases() {
    var result = new ArrayList<Arguments>();
    for (var item : read(SAMPLE).path("cases")) {
      var request = item.path("request");
      result.add(Arguments.of(item.path("id").asText(), request, item.path("expected")));
      if (item.has("equivalentRequest")) {
        var merged = MAPPER.createObjectNode();
        merged.setAll((tools.jackson.databind.node.ObjectNode) request);
        merged.setAll((tools.jackson.databind.node.ObjectNode) item.path("equivalentRequest"));
        result.add(Arguments.of(item.path("id").asText() + "(책)", merged, item.path("expected")));
      }
    }
    return result.stream();
  }

  private static JsonNode read(String path) {
    return MAPPER.readTree(stream(path));
  }

  private static InputStream stream(String path) {
    var input = RefiningVerificationTest.class.getResourceAsStream(path);
    if (input == null) throw new IllegalStateException("리소스 없음: " + path);
    return input;
  }
}
