package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.common.api.EfficiencyRuleVersions;
import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 효율 랭킹 기준 설정(B3): 꺼지면 전투력 기여 모델 열, 켜지면 실 효율 열(딜러만)로 세고, 두 기준의 캐시 키가 다르다. */
class StatsQueryServiceEfficiencySourceTest {
  private final StatsPort port = mock(StatsPort.class);
  private final List<String> keys = new ArrayList<>();
  private final StatsCache cache =
      new StatsCache() {
        @Override
        public <T> Optional<T> find(String key, Class<T> type) {
          keys.add(key);
          return Optional.empty();
        }

        @Override
        public void save(String key, Object value) {}
      };
  private final StatsConditions conditions =
      new StatsConditions(
          null,
          null,
          null,
          StatsRules.DEFAULT_ACTIVE_DAYS,
          new BigDecimal("1680"),
          null,
          null,
          null,
          false);

  @BeforeEach
  void 준비() {
    when(port.coverage()).thenReturn(new StatsPort.Coverage(10, 10, 1));
    when(port.efficiencyRanking(any(), any(), any(), anyInt(), anyInt()))
        .thenAnswer(
            call ->
                new StatsPort.EfficiencyPage(
                    0, call.<StatsPort.EfficiencyColumns>getArgument(2).ruleVersion(), List.of()));
  }

  private StatsQueryService service(boolean real) {
    return new StatsQueryService(port, cache, Clock.systemUTC(), StatsRules.MIN_SAMPLE, real);
  }

  @Test
  void 설정이_꺼지면_전투력_기여_모델_열로_센다() {
    var result =
        service(false).efficiencyRanking(conditions, StatsPort.EfficiencyKind.BRACELET, 1, 50);
    verify(port)
        .efficiencyRanking(
            eq(conditions),
            any(),
            eq(
                new StatsPort.EfficiencyColumns(
                    "bracelet_efficiency_pct",
                    "bracelet_rule_version",
                    EfficiencyRuleVersions.COMBAT_POWER_SIM,
                    false)),
            eq(0),
            eq(50));
    assertThat(result.ruleVersion()).isEqualTo(EfficiencyRuleVersions.COMBAT_POWER_SIM);
  }

  /**
   * 2부 대비 막기(계약 2026-11-28): 스위치는 요청마다 읽는다. 막기만 켜면 판정이 있는 행을 빼고(FAILED), 카드 끄기가 켜지면 모두 뺀다(ALL). 거르기
   * 값이 캐시 키에 들어가 켠 뒤 옛 캐시를 내지 않는다.
   */
  @Test
  void 막기_스위치에_따라_효율_랭킹을_거르고_캐시_키가_다르다() {
    var environment = new org.springframework.mock.env.MockEnvironment();
    var service = service(false);
    service.guard(
        new com.loakraken.character.application.CombatPowerGuard(
            environment, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    var captured = new ArrayList<StatsPort.ShapeFilter>();
    org.mockito.Mockito.doAnswer(
            call -> {
              var columns = call.<StatsPort.EfficiencyColumns>getArgument(2);
              captured.add(columns.shapeFilter());
              return new StatsPort.EfficiencyPage(0, columns.ruleVersion(), List.of());
            })
        .when(port)
        .efficiencyRanking(any(), any(), any(), anyInt(), anyInt());

    service.efficiencyRanking(conditions, StatsPort.EfficiencyKind.BRACELET, 1, 50);
    environment.setProperty("combat-power.raw-shape-guard.enforce", "true");
    service.efficiencyRanking(conditions, StatsPort.EfficiencyKind.BRACELET, 1, 50);
    environment.setProperty("combat-power.kill.card", "true");
    service.efficiencyRanking(conditions, StatsPort.EfficiencyKind.BRACELET, 1, 50);

    assertThat(captured)
        .containsExactly(
            StatsPort.ShapeFilter.NONE, StatsPort.ShapeFilter.FAILED, StatsPort.ShapeFilter.ALL);
    assertThat(keys.stream().filter(key -> key.startsWith("efficiency-ranking:")))
        .hasSize(3)
        .doesNotHaveDuplicates();
  }

  @Test
  void 설정이_켜지면_실_효율_열로_딜러만_세고_캐시_키가_다르다() {
    service(false).efficiencyRanking(conditions, StatsPort.EfficiencyKind.ARK_GRID_GEM, 1, 50);
    var result =
        service(true).efficiencyRanking(conditions, StatsPort.EfficiencyKind.ARK_GRID_GEM, 1, 50);
    verify(port)
        .efficiencyRanking(
            eq(conditions),
            any(),
            eq(
                new StatsPort.EfficiencyColumns(
                    "ark_grid_real_pct",
                    "ark_grid_real_rule_version",
                    EfficiencyRuleVersions.ARK_GRID_REAL,
                    true)),
            eq(0),
            eq(50));
    assertThat(result.ruleVersion()).isEqualTo(EfficiencyRuleVersions.ARK_GRID_REAL);
    var rankingKeys = keys.stream().filter(key -> key.startsWith("efficiency-ranking:")).toList();
    assertThat(rankingKeys).hasSize(2).doesNotHaveDuplicates();
  }
}
