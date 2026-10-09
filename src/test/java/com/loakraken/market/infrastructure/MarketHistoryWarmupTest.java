package com.loakraken.market.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LeaderLease;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.MarketBoardService;
import com.loakraken.market.application.MarketItemHistoryResult;
import com.loakraken.market.application.MarketQueryService;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketTab;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketHistoryWarmupTest {
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final MarketBoardService boards = mock(MarketBoardService.class);
  private final MutableClock clock = new MutableClock();
  private final LostArkRequestGate gate = mock(LostArkRequestGate.class);
  private final LeaderLease lease = mock(LeaderLease.class);

  {
    when(lease.hold(any(), any())).thenReturn(true);
    when(gate.withReserve(anyInt(), any()))
        .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
  }

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void 키가_없으면_아무것도_하지_않는다() {
    warmup("", List.of(1L, 2L)).warm();
    verifyNoInteractions(markets);
  }

  @Test
  void 품목별_실패를_격리하고_결과를_지표로_남긴다() {
    when(markets.refreshItemHistory(1L)).thenReturn(Optional.of(result(1L)));
    when(markets.refreshItemHistory(2L)).thenReturn(Optional.empty());
    when(markets.refreshItemHistory(3L))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_API_ERROR));
    when(markets.refreshItemHistory(4L)).thenThrow(new IllegalStateException("x"));
    when(markets.refreshItemHistory(5L)).thenReturn(Optional.of(result(5L)));
    warmup("key", List.of(1L, 2L, 3L, 4L, 5L)).warm();
    verify(markets, times(5)).refreshItemHistory(anyLong());
    assertThat(count("refreshed")).isEqualTo(2);
    assertThat(count("not_found")).isEqualTo(1);
    assertThat(count("failed")).isEqualTo(2);
  }

  @Test
  void 호출_한도_초과를_만나면_이번_주기를_멈춘다() {
    when(markets.refreshItemHistory(1L))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED));
    warmup("key", List.of(1L, 2L, 3L)).warm();
    verify(markets).refreshItemHistory(1L);
    verify(markets, never()).refreshItemHistory(2L);
  }

  @Test
  void 기본_목록은_프론트_합의_19개이고_잘못된_설정은_거절한다() {
    var defaults =
        new MarketWarmupProperties(
            true,
            Duration.ofMinutes(9),
            Duration.ofSeconds(20),
            Duration.ofSeconds(30),
            List.of(MarketTab.REFINING, MarketTab.REFINING),
            15,
            List.of(
                6861012L, 6861013L, 66102006L, 66102007L, 66102106L, 66102107L, 66110225L,
                66110226L, 66130141L, 66130142L, 66130143L, 66111131L, 66111132L, 66112543L,
                66112551L, 66112553L, 66112546L, 66112552L, 66112554L, 66102006L));
    assertThat(defaults.itemIds()).hasSize(19);
    assertThat(defaults.boardTabs()).containsExactly(MarketTab.REFINING);
    assertThatThrownBy(() -> properties(Duration.ZERO, Duration.ofSeconds(30), 15, List.of(1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> properties(Duration.ofMinutes(9), Duration.ofSeconds(30), 15, List.of(0L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(Duration.ofMinutes(9), Duration.ZERO, 15, List.of(1L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> properties(Duration.ofMinutes(9), Duration.ofSeconds(30), 0, List.of(1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void 기본_보드는_재련_탭_1페이지를_사용자_몫을_남기고_강제_갱신한다() {
    var warmup = warmup("key", List.of(), 15);
    warmup.warmBoards();
    verify(boards).refreshDefaultPage(MarketTab.REFINING);
    verify(gate).withReserve(eq(MarketHistoryWarmup.RESERVE), any());
    verify(lease).hold(eq(MarketHistoryWarmup.BOARD_LEASE), eq(Duration.ofSeconds(60)));
    assertThat(
            registry
                .get("loakraken.background.last_success")
                .tag("job", "warmup_board")
                .gauge()
                .value())
        .isEqualTo(clock.now.getEpochSecond());
    assertThat(
            registry
                .get("loakraken.background.last_success")
                .tag("job", "warmup_history")
                .gauge()
                .value())
        .isZero();
    assertThat(
            registry
                .get("loakraken.market.warmup")
                .tag("target", "board")
                .tag("outcome", "refreshed")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void 임대를_못_얻거나_키가_없으면_보드를_갱신하지_않는다() {
    when(lease.hold(eq(MarketHistoryWarmup.BOARD_LEASE), any())).thenReturn(false);
    warmup("key", List.of(), 15).warmBoards();
    warmup("", List.of(), 15).warmBoards();
    verifyNoInteractions(boards);
  }

  @Test
  void 이력과_보드가_분당_상한을_함께_쓰고_다음_분에_다시_센다() {
    when(markets.refreshItemHistory(anyLong())).thenReturn(Optional.of(result(1L)));
    var warmup = warmup("key", List.of(1L, 2L, 3L, 4L), 3);
    warmup.warm();
    verify(markets, times(3)).refreshItemHistory(anyLong());
    verify(markets, never()).refreshItemHistory(4L);
    warmup.warmBoards();
    verifyNoInteractions(boards);
    assertThat(
            registry
                .get("loakraken.market.warmup")
                .tag("target", "board")
                .tag("outcome", "capped")
                .counter()
                .count())
        .isEqualTo(1);
    clock.now = clock.now.plusSeconds(60);
    warmup.warmBoards();
    verify(boards).refreshDefaultPage(MarketTab.REFINING);
  }

  private MarketHistoryWarmup warmup(String key, List<Long> ids) {
    return warmup(key, ids, 100);
  }

  private static MarketWarmupProperties properties(
      Duration interval, Duration boardInterval, int maxCalls, List<Long> ids) {
    return new MarketWarmupProperties(
        true, interval, Duration.ZERO, boardInterval, null, maxCalls, ids);
  }

  private MarketHistoryWarmup warmup(String key, List<Long> ids, int maxCalls) {
    var lostArk =
        new LostArkProperties(
            URI.create("http://127.0.0.1:1"),
            key,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            90,
            4,
            Duration.ofMinutes(5));
    return new MarketHistoryWarmup(
        markets,
        boards,
        clock,
        new MarketWarmupProperties(
            true,
            Duration.ofMinutes(9),
            Duration.ZERO,
            Duration.ofSeconds(30),
            List.of(MarketTab.REFINING),
            maxCalls,
            ids),
        lostArk,
        registry,
        gate,
        lease);
  }

  private double count(String outcome) {
    var counter = registry.find("loakraken.market.warmup").tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }

  private static final class MutableClock extends java.time.Clock {
    Instant now = Instant.parse("2026-10-09T06:00:05Z");

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static MarketItemHistoryResult result(long id) {
    return new MarketItemHistoryResult(Instant.now(), new MarketItemHistory(id, List.of()));
  }
}
