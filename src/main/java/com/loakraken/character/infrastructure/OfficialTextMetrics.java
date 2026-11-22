package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.OfficialTextLimit;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 공식 글 길이 상한({@link OfficialTextLimit})에 걸려 읽지 않은 횟수를 지표로 낸다(칸 수가 아니라 읽기 횟수: 긴 칸 하나를 여러 파서가 읽으면 그만큼
 * 오른다). 0이 아니면 공식 응답이 깨졌거나 상한이 실제 글보다 작다는 뜻이라 계산 입력이 빠졌는지 본다.
 */
@Component
class OfficialTextMetrics {
  OfficialTextMetrics(MeterRegistry registry) {
    FunctionCounter.builder(
            "loakraken.official_text.dropped",
            OfficialTextLimit.class,
            ignored -> OfficialTextLimit.dropped())
        .description("공식 글이 길이 상한을 넘어 읽지 않은 횟수(읽기마다, 한 칸이 여러 번 셀 수 있음, 프로세스 시작부터)")
        .register(registry);
  }
}
