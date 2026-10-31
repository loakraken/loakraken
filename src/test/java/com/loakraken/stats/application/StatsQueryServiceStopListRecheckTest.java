package com.loakraken.stats.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loakraken.stats.domain.StatsConditions;
import com.loakraken.stats.domain.StatsRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 캐시된 랭킹에 캐시 뒤 처리정지 목록에 든 캐릭터가 있으면 캐시를 쓰지 않고 다시 센다(B3 신선도). */
class StatsQueryServiceStopListRecheckTest {
  private final StatsPort port = mock(StatsPort.class);
  private final List<Object> saved = new ArrayList<>();
  private Object stored;
  private final StatsCache cache =
      new StatsCache() {
        @Override
        public <T> Optional<T> find(String key, Class<T> type) {
          return Optional.ofNullable(type.isInstance(stored) ? type.cast(stored) : null);
        }

        @Override
        public void save(String key, Object value) {
          saved.add(value);
          stored = value;
        }
      };
  private final StatsQueryService service =
      new StatsQueryService(port, cache, Clock.systemUTC(), StatsRules.MIN_SAMPLE);
  private final StatsConditions conditions =
      new StatsConditions(
          null, null, null, StatsRules.DEFAULT_ACTIVE_DAYS, null, null, null, null, false);

  private StatsQueryService.EfficiencyRankingResult cached(String name) {
    var meta =
        new StatsQueryService.Meta(
            1,
            conditions,
            Instant.EPOCH,
            null,
            "stats-v1",
            30,
            StatsRules.SampleStatus.READY,
            null);
    return new StatsQueryService.EfficiencyRankingResult(
        meta,
        StatsPort.EfficiencyKind.BRACELET,
        "combat-power-sim-v8",
        1,
        50,
        List.of(
            new StatsQueryService.EfficiencyRankedCharacter(
                1,
                name,
                "서버",
                "블레이드",
                "DEALER",
                null,
                null,
                BigDecimal.TEN,
                null,
                null,
                null,
                null)));
  }

  @BeforeEach
  void 준비() {
    when(port.coverage()).thenReturn(new StatsPort.Coverage(10, 10, 1));
    when(port.efficiencyRanking(any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new StatsPort.EfficiencyPage(0, "combat-power-sim-v8", List.of()));
  }

  @Test
  void 캐시된_랭킹에_처리정지_캐릭터가_없으면_캐시를_쓴다() {
    stored = cached("일반캐");
    when(port.stopListed(any())).thenReturn(Set.of());
    var result = service.efficiencyRanking(conditions, StatsPort.EfficiencyKind.BRACELET, 1, 50);
    assertThat(result.rows()).hasSize(1);
    verify(port, never()).efficiencyRanking(any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void 캐시된_랭킹에_처리정지_캐릭터가_있으면_다시_세고_캐시를_바꾼다() {
    stored = cached("정지캐");
    when(port.stopListed(any())).thenReturn(Set.of(new StatsPort.Named("정지캐", "서버")));
    var result = service.efficiencyRanking(conditions, StatsPort.EfficiencyKind.BRACELET, 1, 50);
    assertThat(result.rows()).isEmpty();
    verify(port).efficiencyRanking(any(), any(), any(), anyInt(), anyInt());
    // 다시 센 랭킹으로 캐시를 바꾼다(메타 계산의 다른 캐시 저장은 세지 않는다).
    assertThat(saved)
        .filteredOn(StatsQueryService.EfficiencyRankingResult.class::isInstance)
        .singleElement()
        .satisfies(
            value ->
                assertThat(((StatsQueryService.EfficiencyRankingResult) value).rows()).isEmpty());
  }

  @Test
  void 원정대_랭킹은_대표뿐_아니라_구성_캐릭터도_다시_확인_대상이다() {
    var result =
        new StatsQueryService.RosterRankingResult(
            null,
            "ITEM_LEVEL",
            "TOP6_AVG",
            1,
            50,
            0,
            List.of(
                new StatsQueryService.RankedRoster(
                    1,
                    "대표캐",
                    "서버",
                    "블레이드",
                    BigDecimal.TEN,
                    null,
                    2,
                    2,
                    BigDecimal.TEN,
                    List.of(
                        new StatsPort.RosterMember("대표캐", "블레이드", BigDecimal.TEN),
                        new StatsPort.RosterMember("구성캐", "바드", BigDecimal.ONE)),
                    null)));
    assertThat(result.listedCharacters())
        .contains(new StatsPort.Named("대표캐", "서버"), new StatsPort.Named("구성캐", "서버"));
  }

  @Test
  void 캐시된_수집품_순위의_캐릭터가_처리정지_목록에_들면_다시_센다() {
    var meta =
        new StatsQueryService.Meta(
            1,
            conditions,
            Instant.EPOCH,
            null,
            "stats-v1",
            30,
            StatsRules.SampleStatus.READY,
            null);
    stored =
        new StatsQueryService.CollectibleStandingResult(
            meta, "ROSTER", "정지캐", "서버", 1L, 40, null, null, 10L, 20L, null);
    when(port.stopListed(any())).thenReturn(Set.of(new StatsPort.Named("정지캐", "서버")));
    when(port.collectibleStanding(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(
            new StatsPort.CollectibleStanding("NOT_OBSERVED", "정지캐", null, null, 0, null, null));
    var result = service.collectibleStanding(conditions, "정지캐", false);
    assertThat(result.rank()).isNull();
    assertThat(result.reason()).isEqualTo("NOT_OBSERVED");
    verify(port)
        .collectibleStanding(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }
}
