package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.stats.domain.ClassBuilds;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 코어·스킬 항목(details)의 v2 표본 하한: 운영 기본 30, 로컬 대조에서만 설정으로 낮춘다. */
class StatsQueryServiceDetailMinSampleTest {
  private final StatsPort port = mock(StatsPort.class);
  private final StatsCache cache =
      new StatsCache() {
        @Override
        public <T> Optional<T> find(String key, Class<T> type) {
          return Optional.empty();
        }

        @Override
        public void save(String key, Object value) {}
      };

  private final StatsConditions conditions =
      new StatsConditions(
          null,
          null,
          "버서커",
          StatsRules.DEFAULT_ACTIVE_DAYS,
          new BigDecimal("1680"),
          null,
          null,
          null,
          false);

  private ClassBuilds.Details details(int detailMinSample) {
    var sample =
        new ClassBuilds.Sample(
            new BigDecimal("1700"),
            "광기",
            List.of("원한"),
            null,
            null,
            null,
            null,
            null,
            true,
            List.of(new ClassBuilds.Core("질서", "해", "살상연희")),
            List.of(
                new ClassBuilds.SkillUse(
                    "윈드 컷", List.of(new ClassBuilds.TripodKey(1, "빠른 준비")), "질풍")));
    when(port.classBuildSamples(any(), any()))
        .thenReturn(
            new StatsPort.ClassBuildSamples(10, List.copyOf(Collections.nCopies(10, sample))));
    when(port.coverage()).thenReturn(new StatsPort.Coverage(10, 10, 1));
    var service = new StatsQueryService(port, cache, Clock.systemUTC(), detailMinSample);
    return service.classBuilds(conditions, null, ClassBuilds.DEFAULT_LIMIT, null).details();
  }

  @Test
  void 기본_하한_30이면_v2_표본_10명은_칸이_비어_있다() {
    var details = details(StatsRules.MIN_SAMPLE);
    assertThat(details.ready()).isFalse();
    assertThat(details.detailedSamples()).isEqualTo(10);
    assertThat(details.coverage()).isEqualByComparingTo("100.00");
    assertThat(details.cores()).isEmpty();
  }

  @Test
  void 로컬에서_하한을_5로_낮추면_같은_표본으로_칸을_센다() {
    var details = details(5);
    assertThat(details.ready()).isTrue();
    assertThat(details.cores().get(0).names().cells())
        .extracting(ClassBuilds.Cell::key, ClassBuilds.Cell::count)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("살상연희", 10L));
    assertThat(details.skillDetails().get(0).runes().cells())
        .extracting(ClassBuilds.Cell::key)
        .containsExactly("질풍");
  }

  @Test
  void 하한은_1_이상이다() {
    assertThatThrownBy(() -> new StatsQueryService(port, cache, Clock.systemUTC(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
