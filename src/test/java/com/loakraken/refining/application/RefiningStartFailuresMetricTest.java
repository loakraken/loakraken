package com.loakraken.refining.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import com.loakraken.refining.domain.OptimalStageCache;
import com.loakraken.refining.domain.RefiningRules;
import com.loakraken.refining.domain.VambraceRules;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 현재 성공 확률로 실패 횟수를 정한 결과가 지표로 남는다(운영에서 화면 값과 규칙이 어긋나는지 보려고). */
class RefiningStartFailuresMetricTest {
  @Test
  void 맞음_성공_확률_불일치_장인의_기운_불일치를_따로_센다() {
    var registry = new SimpleMeterRegistry();
    var service =
        new RefiningPlanService(
            read("/refining/t4-refining-rules.json", RefiningRules.class),
            null,
            read("/refining/t4-vambrace-rules.json", VambraceRules.class),
            OptimalStageCache.NONE,
            registry,
            2);
    var grade = RefiningRules.Grade.UPHWA;
    var armor = RefiningRules.EquipmentType.ARMOR;
    // 업화 방어구 →17: 3.30% = 실패 1회, 기운 1.39~4.18%.
    assertThat(service.failuresFromSuccessRate(grade, armor, 17, 330, 139)).isEqualTo(1);
    assertThat(
            service.failuresFromSuccessRate(
                null, RefiningRules.EquipmentType.ARM_GUARD, 1, 1650, 697))
        .isEqualTo(1);
    assertThatThrownBy(() -> service.failuresFromSuccessRate(grade, armor, 17, 372, 139))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(RefiningErrorCode.REFINING_SUCCESS_RATE_MISMATCH));
    assertThatThrownBy(() -> service.failuresFromSuccessRate(grade, armor, 17, 330, 0))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode())
                    .isEqualTo(RefiningErrorCode.REFINING_ARTISAN_ENERGY_MISMATCH));
    assertThat(count(registry, "matched")).isEqualTo(2);
    assertThat(count(registry, "rate_mismatch")).isEqualTo(1);
    assertThat(count(registry, "energy_mismatch")).isEqualTo(1);
  }

  private static double count(SimpleMeterRegistry registry, String result) {
    return registry
        .get("loakraken.refining.start_failures")
        .tag("result", result)
        .counter()
        .count();
  }

  private static <T> T read(String resource, Class<T> type) {
    try (var input = RefiningStartFailuresMetricTest.class.getResourceAsStream(resource)) {
      return JsonMapper.builder().build().readValue(input, type);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
