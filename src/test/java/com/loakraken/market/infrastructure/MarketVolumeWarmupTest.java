package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRateLimitedException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketBoardScan;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.application.MarketCache;
import com.loakraken.market.application.MarketItemHistoryResult;
import com.loakraken.market.application.MarketMetrics;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import com.loakraken.market.domain.MarketVolume;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketVolumeWarmupTest {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final MarketBoardService boards = mock(MarketBoardService.class);
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final MarketCache cache = mock(MarketCache.class);
  private final LostArkRequestGate gate = mock(LostArkRequestGate.class);
  private final LeaderLease lease = mock(LeaderLease.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MovableClock clock = new MovableClock();
  private final LocalDate today = LocalDate.of(2026, 11, 8);

  {
    when(lease.hold(any(), any())).thenReturn(true);
    when(gate.withReserve(anyInt(), any()))
        .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
  }

  @Test
  void 키가_없으면_아무것도_하지_않는다() {
    warmup("", List.of(MarketTab.FOOD), 15, 800).tick();
    verifyNoInteractions(boards, markets, cache, lease);
  }

  @Test
  void 하루_한_바퀴_이미_있는_품목은_건너뛰고_오늘_이력_캐시는_호출_없이_나머지는_상세로_채운다() {
    // FOOD는 스캔 캐시가 있어 미리 세지 않고, LIFE는 캐시가 없어 10번으로 미리 센다.
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(scan(MarketTab.FOOD, 1, 2, 3)));
    when(boards.scanForVolume(MarketTab.FOOD)).thenReturn(scan(MarketTab.FOOD, 1, 2, 3));
    when(boards.scanForVolume(MarketTab.LIFE)).thenReturn(scan(MarketTab.LIFE, 3, 4));
    when(cache.findVolumes(eq(today), any()))
        .thenReturn(Map.of(1L, new MarketVolume(1, today.minusDays(1), 1, 1)));
    when(cache.findItemHistories(any())).thenReturn(Map.of(2L, history(2, clock.now)));
    when(markets.refreshItemHistory(3L)).thenReturn(Optional.of(history(3, clock.now)));
    when(markets.refreshItemHistory(4L)).thenReturn(Optional.empty());

    var job = warmup("key", List.of(MarketTab.FOOD, MarketTab.LIFE), 15, 800);
    job.tick();

    verify(markets, never()).refreshItemHistory(1L);
    verify(markets, never()).refreshItemHistory(2L);
    verify(markets).refreshItemHistory(3L);
    verify(markets).refreshItemHistory(4L);
    verify(cache).addVolumeCalls(today, MarketBoardService.MAX_SCAN_PAGES);
    verify(cache, times(2)).addVolumeCalls(today, 1);
    var saved = savedVolumes();
    assertThat(saved).extracting(MarketVolume::itemId).containsExactly(2L, 3L);
    // 완료일 11-01~11-07, 하루 10개씩: 전일 10, 7일 70. 오늘(부분 집계)은 빠진다.
    assertThat(saved.getLast().volumeDate()).isEqualTo(today.minusDays(1));
    assertThat(saved.getLast().yesterday()).isEqualTo(10);
    assertThat(saved.getLast().sevenDay()).isEqualTo(70);
    assertThat(count("filled")).isEqualTo(1);
    assertThat(count("not_found")).isEqualTo(1);
    assertThat(gauge("loakraken.background.last_success")).isPositive();

    // 같은 날 다시 돌면 공식 호출이 없다.
    clock.now = clock.now.plusSeconds(60);
    job.tick();
    verify(markets, times(2)).refreshItemHistory(anyLong());
    verify(boards, times(2)).scanForVolume(any());
  }

  @Test
  void 분당_상한을_넘으면_멈추고_다음_분에_그_품목부터_이어_받는다() {
    var ids = LongStream.rangeClosed(1, 20).toArray();
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(scan(MarketTab.FOOD, ids)));
    when(boards.scanForVolume(MarketTab.FOOD)).thenReturn(scan(MarketTab.FOOD, ids));
    when(markets.refreshItemHistory(anyLong()))
        .thenAnswer(invocation -> Optional.of(history(invocation.getArgument(0), clock.now)));
    var job = warmup("key", List.of(MarketTab.FOOD), 15, 800);

    job.tick();
    verify(markets, times(15)).refreshItemHistory(anyLong());
    verify(markets, never()).refreshItemHistory(16L);
    assertThat(count("minute_cap")).isEqualTo(1);
    assertThat(gauge("loakraken.market.volume_warmup.pending")).isEqualTo(5);

    clock.now = clock.now.plusSeconds(60);
    job.tick();
    verify(markets, times(20)).refreshItemHistory(anyLong());
    verify(markets).refreshItemHistory(16L);
    assertThat(gauge("loakraken.market.volume_warmup.pending")).isZero();
  }

  @Test
  void 하루_상한에_닿으면_공식_호출을_하지_않는다() {
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(scan(MarketTab.FOOD, 1, 2)));
    when(boards.scanForVolume(MarketTab.FOOD)).thenReturn(scan(MarketTab.FOOD, 1, 2));
    when(cache.addVolumeCalls(eq(today), anyInt())).thenReturn(801L);

    warmup("key", List.of(MarketTab.FOOD), 15, 800).tick();
    verify(markets, never()).refreshItemHistory(anyLong());
    assertThat(count("daily_cap")).isEqualTo(1);
  }

  @Test
  void 캐시에_없는_탭_스캔은_분당_상한을_10번으로_미리_잡고_모자라면_다음_분으로_미룬다() {
    when(cache.findBoardScan(MarketTab.LIFE, null)).thenReturn(Optional.empty());
    when(boards.scanForVolume(MarketTab.LIFE)).thenReturn(scan(MarketTab.LIFE, 1));
    when(markets.refreshItemHistory(anyLong()))
        .thenAnswer(invocation -> Optional.of(history(invocation.getArgument(0), clock.now)));
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(scan(MarketTab.FOOD, LongStream.rangeClosed(11, 18).toArray())));
    when(boards.scanForVolume(MarketTab.FOOD))
        .thenReturn(scan(MarketTab.FOOD, LongStream.rangeClosed(11, 18).toArray()));
    var job = warmup("key", List.of(MarketTab.FOOD, MarketTab.LIFE), 15, 800);

    // FOOD 품목 8번을 부른 뒤 남은 7번으로는 LIFE 스캔(10번)을 잡지 못한다.
    job.tick();
    verify(markets, times(8)).refreshItemHistory(anyLong());
    verify(boards, never()).scanForVolume(MarketTab.LIFE);

    clock.now = clock.now.plusSeconds(60);
    job.tick();
    verify(boards).scanForVolume(MarketTab.LIFE);
    verify(markets).refreshItemHistory(1L);
  }

  @Test
  void 관문이_미루면_이번_주기를_멈추고_다음_주기에_그_품목부터_다시_받는다() {
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(scan(MarketTab.FOOD, 1, 2)));
    when(boards.scanForVolume(MarketTab.FOOD)).thenReturn(scan(MarketTab.FOOD, 1, 2));
    when(markets.refreshItemHistory(1L))
        .thenThrow(new LostArkRateLimitedException(LostArkRateLimitedException.Cause.GATE_BUDGET))
        .thenReturn(Optional.of(history(1, clock.now)));
    when(markets.refreshItemHistory(2L)).thenReturn(Optional.of(history(2, clock.now)));
    var job = warmup("key", List.of(MarketTab.FOOD), 15, 800);

    job.tick();
    verify(markets, never()).refreshItemHistory(2L);
    assertThat(count("gate_reject")).isEqualTo(1);

    clock.now = clock.now.plusSeconds(60);
    job.tick();
    verify(markets, times(2)).refreshItemHistory(1L);
    verify(markets).refreshItemHistory(2L);
    assertThat(count("filled")).isEqualTo(2);
  }

  @Test
  void 날짜가_바뀌면_처음부터_다시_한_바퀴_돈다() {
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(scan(MarketTab.FOOD, 1)));
    when(boards.scanForVolume(MarketTab.FOOD)).thenReturn(scan(MarketTab.FOOD, 1));
    when(markets.refreshItemHistory(1L))
        .thenAnswer(invocation -> Optional.of(history(1, clock.now)));
    var job = warmup("key", List.of(MarketTab.FOOD), 15, 800);

    job.tick();
    // 다음 날 KST 00:05
    clock.now = Instant.parse("2026-11-08T15:05:00Z");
    job.tick();
    verify(markets, times(2)).refreshItemHistory(1L);
    verify(cache).saveVolumes(eq(today.plusDays(1)), any());
  }

  @Test
  void 리더가_아니면_돌지_않는다() {
    reset(lease);
    when(lease.hold(any(), any())).thenReturn(false);
    warmup("key", List.of(MarketTab.FOOD), 15, 800).tick();
    verify(lease).hold(eq(MarketVolumeWarmup.LEASE), any());
    verifyNoInteractions(boards, markets);
  }

  @Test
  void 분당_상한은_탭_스캔_한_번_이상이고_하루_상한은_분당_이상이어야_한다() {
    assertThatThrownBy(() -> properties(List.of(MarketTab.FOOD), 9, 800))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(List.of(MarketTab.FOOD), 15, 14))
        .isInstanceOf(IllegalArgumentException.class);
    var defaults = properties(null, 15, 800);
    assertThat(defaults.tabs()).isEmpty();
  }

  private List<MarketVolume> savedVolumes() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<MarketVolume>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(cache, atLeastOnce()).saveVolumes(eq(today), captor.capture());
    var all = new ArrayList<MarketVolume>();
    captor.getAllValues().forEach(all::addAll);
    return all;
  }

  private double count(String event) {
    var counter = registry.find("loakraken.market.volume_warmup").tag("event", event).counter();
    return counter == null ? 0 : counter.count();
  }

  private double gauge(String name) {
    var found = registry.find(name).gauge();
    return found == null ? Double.NaN : found.value();
  }

  private MarketVolumeWarmup warmup(String key, List<MarketTab> tabs, int perMinute, int perDay) {
    var lostArk =
        new LostArkProperties(
            URI.create("http://127.0.0.1:1"),
            key,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            90,
            4,
            Duration.ofMinutes(5));
    return new MarketVolumeWarmup(
        boards,
        markets,
        cache,
        new MarketMetrics(registry),
        properties(tabs, perMinute, perDay),
        lostArk,
        gate,
        lease,
        clock,
        registry);
  }

  private static MarketVolumeWarmupProperties properties(
      List<MarketTab> tabs, int perMinute, int perDay) {
    return new MarketVolumeWarmupProperties(
        true, Duration.ofSeconds(60), Duration.ZERO, perMinute, perDay, tabs);
  }

  private static MarketBoardScan scan(MarketTab tab, long... ids) {
    return new MarketBoardScan(
        tab,
        tab.defaultTier(),
        Instant.parse("2026-11-08T03:00:00Z"),
        ids.length,
        LongStream.of(ids).mapToObj(MarketVolumeWarmupTest::item).toList());
  }

  private static MarketPrice item(long id) {
    return new MarketPrice(
        id, "품목" + id, "일반", null, 1, null, BigDecimal.valueOf(100), null, BigDecimal.TEN);
  }

  /** observedAt(KST) 날짜 기준 앞 7일 완료일 하루 10개씩 + 오늘 부분 집계 3개. */
  private static MarketItemHistoryResult history(long id, Instant observedAt) {
    var day = LocalDate.ofInstant(observedAt, KST);
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    days.add(new MarketItemHistory.DailyStat(day, BigDecimal.TEN, 3));
    for (int i = 1; i <= 7; i++)
      days.add(new MarketItemHistory.DailyStat(day.minusDays(i), BigDecimal.TEN, 10));
    return new MarketItemHistoryResult(
        observedAt,
        new MarketItemHistory(
            id, List.of(new MarketItemHistory.Variant("품목" + id, null, 1, days))));
  }

  private static final class MovableClock extends Clock {
    Instant now = Instant.parse("2026-11-08T03:00:00Z");
    private final ZoneId zone;

    MovableClock() {
      this(ZoneOffset.UTC);
    }

    private MovableClock(ZoneId zone) {
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      var outer = this;
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
          return outer.withZone(other);
        }

        @Override
        public Instant instant() {
          return outer.now;
        }
      };
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
