package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.stats.domain.ClassBuilds;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ClassBuildSampleReaderTest {
  @Test
  void 트라이포드_단계는_저장된_공식_Tier_0부터를_게임_화면_단계_1부터로_읽는다() {
    var parts =
        """
        {"schema":"equipment-summary-v2","skills":[{"name":"윈드 컷","level":14,"tripods":[
          {"tier":0,"slot":1,"name":"빠른 준비"},{"tier":1,"slot":2,"name":"강화된 일격"},
          {"tier":2,"slot":1,"name":"질풍"},{"tier":null,"slot":1,"name":"단계 없음"}],
         "rune":{"name":"질풍","grade":"전설"}}]}
        """;

    var sample = ClassBuildSampleReader.fromParts(new BigDecimal("1700"), "광기", parts);

    assertThat(sample.detailed()).isTrue();
    assertThat(sample.skills().get(0).tripods())
        .containsExactly(
            new ClassBuilds.TripodKey(1, "빠른 준비"),
            new ClassBuilds.TripodKey(2, "강화된 일격"),
            new ClassBuilds.TripodKey(3, "질풍"),
            new ClassBuilds.TripodKey(null, "단계 없음"));
    assertThat(sample.skills().get(0).rune()).isEqualTo("질풍");
  }

  @Test
  void v1_행은_상세_표본이_아니다() {
    var sample =
        ClassBuildSampleReader.fromParts(
            new BigDecimal("1700"), "광기", "{\"schema\":\"equipment-summary-v1\"}");
    assertThat(sample.detailed()).isFalse();
  }
}
