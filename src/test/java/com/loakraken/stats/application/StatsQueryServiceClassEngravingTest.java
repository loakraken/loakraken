package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/** 직업 각인 필터(#618): 모르는 수를 세지 못해도 랭킹·선택지는 200이고 수만 null이다. */
class StatsQueryServiceClassEngravingTest {
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
  private final StatsQueryService service =
      new StatsQueryService(port, cache, Clock.systemUTC(), StatsRules.MIN_SAMPLE);
  private final StatsConditions conditions =
      new StatsConditions(
          null,
          null,
          "블레이드",
          StatsRules.DEFAULT_ACTIVE_DAYS,
          new BigDecimal("1680"),
          null,
          null,
          null,
          false);

  @Test
  void 모르는_수_집계가_시간_초과면_랭킹은_그대로_주고_모르는_수만_null이다() {
    when(port.coverage()).thenReturn(new StatsPort.Coverage(10, 10, 1));
    when(port.ranking(any(), any(), any(), anyInt(), anyInt(), eq("버스트")))
        .thenReturn(new StatsPort.RankingPage(0, List.of()));
    when(port.classEngravingCounts(any(), any())).thenThrow(new QueryTimeoutException("timeout"));
    var result = service.ranking(conditions, StatsRules.Metric.COMBAT_POWER, 1, 50, "버스트");
    assertThat(result.classEngraving()).isEqualTo("버스트");
    assertThat(result.classEngravingUnknown()).isNull();

    var choices = service.classEngravings(conditions, List.of("버스트", "잔재된 기운"));
    assertThat(choices.classEngravings())
        .extracting(StatsQueryService.ClassEngravingCount::count)
        .containsOnlyNulls();
    assertThat(choices.unknown()).isNull();
  }

  @Test
  void 수를_세면_목록_이름별로_공백을_무시하고_더한다() {
    when(port.classEngravingCounts(any(), any()))
        .thenReturn(Map.of("버스트", 2L, "잔재된기운", 1L, "잔재된 기운", 3L));
    var choices = service.classEngravings(conditions, List.of("버스트", "잔재된 기운"));
    assertThat(choices.classEngravings())
        .extracting(StatsQueryService.ClassEngravingCount::count)
        .containsExactly(2L, 4L);
    assertThat(choices.unknown()).isZero();
  }
}
