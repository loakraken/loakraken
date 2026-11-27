package com.loakraken.character.infrastructure;

import com.loakraken.character.domain.RawShapeGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 2부 대비 원본 형태 검사를 공식 조회마다 돌려 세기만 한다(막기는 아직 없음, 명세의 '로그만 먼저' 단계).
 *
 * <p>지표 {@code loakraken.raw_shape_guard}: 검사한 조회 수({@code rule=ALL, outcome=checked})와 어긋난 규칙 묶음마다
 * {@code outcome=fail}(rule=H1…H7, W1…W3). 태그 값은 고정된 목록이라 늘지 않는다. 어긋나면 경고 로그 한 줄에 세부 규칙 id만 남긴다(캐릭터
 * 이름·원문 없음). 검사가 실패해도 조회는 그대로 진행한다.
 */
@Component
class RawShapeGuardRecorder {
  static final String METER = "loakraken.raw_shape_guard";
  private static final Logger log = LoggerFactory.getLogger(RawShapeGuardRecorder.class);
  private final MeterRegistry registry;

  RawShapeGuardRecorder(MeterRegistry registry) {
    this.registry = registry;
  }

  /** 판정을 세고 돌려준다. 검사 자체가 실패하면 null(세지 않음). */
  RawShapeGuard.Verdict record(JsonNode armory) {
    RawShapeGuard.Verdict verdict;
    try {
      verdict = RawShapeGuard.check(armory);
    } catch (RuntimeException exception) {
      log.warn("event=raw_shape_guard_error type={}", exception.getClass().getSimpleName());
      return null;
    }
    registry.counter(METER, "rule", "ALL", "outcome", "checked").increment();
    var groups = new LinkedHashSet<String>();
    Stream.concat(verdict.hard().stream(), verdict.watch().stream())
        .map(RawShapeGuard.Verdict::group)
        .forEach(groups::add);
    for (var group : groups) registry.counter(METER, "rule", group, "outcome", "fail").increment();
    if (!groups.isEmpty())
      log.warn(
          "event=raw_shape_guard_fail rules={} watch={} enforced=false",
          String.join(",", verdict.hard()),
          String.join(",", verdict.watch()));
    return verdict;
  }
}
