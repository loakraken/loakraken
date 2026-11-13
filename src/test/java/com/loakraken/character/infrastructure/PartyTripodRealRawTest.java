package com.loakraken.character.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loakraken.character.domain.CritSpeedCalculation;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 실제 공식 트라이포드 원문(저장된 원문 2026-10-18, 태그 포함)을 원문 정리(LostArkText)까지 거쳐 자신 및 파티원 공이속으로 읽는다. 공식 호출 없음.
 */
class PartyTripodRealRawTest {
  private static final String FIXTURE = "/verification/crit-speed/party-tripods-2026-10-18.json";

  @Test
  void 실제_원문의_자신_및_파티원_공이속은_그_절의_값만_읽고_자기만_오르는_문구는_파티가_아니다() throws Exception {
    var read = new LinkedHashMap<String, String>();
    for (var entry : tripods().entrySet()) {
      var party = CritSpeedCalculation.partyTripodSpeeds(LostArkText.plain(entry.getValue()));
      read.put(entry.getKey(), party.found() ? party.attack() + "/" + party.move() : "아님");
    }
    assertThat(read)
        .containsExactly(
            // 공격 속도 8%, 마나 회복 속도 50%(속도 아님)
            Map.entry("발키리 숭고한 도약 마나의 축복", "8.0/null"),
            // 공격 속도와 이동 속도가 각각 8%
            Map.entry("발키리 숭고한 도약 바람의 축복", "8.0/8.0"),
            Map.entry("스트라이커 번개의 속삭임 번개의 축복", "8.0/null"),
            // 피격이상 면역 절 뒤의 자신 및 파티원 절
            Map.entry("워로드 넬라시아의 기운 리더쉽", "25.0/null"),
            // 자기만 오르는 콤보 적중 문구는 파티 규칙이 아니다(이름 표를 따름).
            Map.entry("스트라이커 붕천퇴 바람의 축복", "아님"));
  }

  private static Map<String, String> tripods() throws Exception {
    try (InputStream in = PartyTripodRealRawTest.class.getResourceAsStream(FIXTURE)) {
      JsonNode root = new ObjectMapper().readTree(in);
      var result = new LinkedHashMap<String, String>();
      for (var node : root.path("tripods"))
        result.put(
            node.path("className").asText()
                + " "
                + node.path("skill").asText()
                + " "
                + node.path("tripod").path("Name").asText(),
            node.path("tripod").path("Tooltip").asText());
      return result;
    }
  }
}
