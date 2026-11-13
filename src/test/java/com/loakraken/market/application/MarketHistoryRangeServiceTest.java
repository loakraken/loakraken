package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.loakraken.common.api.BusinessException;
import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.Role;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import com.loakraken.market.domain.MarketHistoryRange;
import com.loakraken.market.domain.MarketItemHistory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 시세 기간 조회: 30d 이상만 저장 표를 읽고(공식 호출은 지금과 같음), 경매장 칸은 표의 값으로만 지정한다. */
class MarketHistoryRangeServiceTest {
  // KST 2026-11-13 12:00
  private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
  private static final LocalDate TODAY = LocalDate.parse("2026-11-13");
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final MarketDailyStore daily = mock(MarketDailyStore.class);
  private final AuctionPort auctions = mock(AuctionPort.class);
  private final MarketHistoryRangeService service =
      new MarketHistoryRangeService(markets, daily, auctions, Clock.fixed(NOW, ZoneOffset.UTC));

  {
    when(markets.itemHistory(anyLong()))
        .thenReturn(new MarketItemHistoryResult(NOW, new MarketItemHistory(7L, List.of())));
  }

  @Test
  void 거래소_30일_이상은_기준일을_포함한_기간의_저장_완료일을_읽는다() {
    var stored =
        new MarketDailyStore.StoredRange(
            List.of(
                new MarketDailyStore.StoredDay(
                    "-1/1", TODAY.minusDays(20), BigDecimal.TEN, 3, "품목")),
            Map.of("-1/1", TODAY.minusDays(40)));
    when(daily.findRange(7L, TODAY.minusDays(29))).thenReturn(stored);

    var ranged = service.itemHistory(7L, MarketHistoryRange.D30);

    assertThat(ranged.range()).isEqualTo(MarketHistoryRange.D30);
    assertThat(ranged.stored()).isEqualTo(stored);
    verify(daily).findRange(7L, TODAY.minusDays(29));
    service.itemHistory(7L, MarketHistoryRange.D365);
    verify(daily).findRange(7L, TODAY.minusDays(364));
  }

  @Test
  void 거래소_14일_이하는_저장_표를_읽지_않고_읽기_실패는_공식_14일로_답한다() {
    assertThat(service.itemHistory(7L, MarketHistoryRange.D14).stored()).isNull();
    assertThat(service.itemHistory(7L, MarketHistoryRange.D7).stored()).isNull();
    verify(daily, never()).findRange(anyLong(), any());

    when(daily.findRange(anyLong(), any()))
        .thenThrow(new DataAccessResourceFailureException("db down"));
    var ranged = service.itemHistory(7L, MarketHistoryRange.D90);
    assertThat(ranged.stored()).isNull();
    assertThat(ranged.base().history().itemId()).isEqualTo(7L);
  }

  @Test
  void 보석_칸은_표와_같은_키로_기간을_읽는다() {
    var stored =
        new AuctionPort.AuctionDailyRange(
            List.of(new AuctionPort.AuctionDailyDay(TODAY, 1_000L, 5L, NOW)), TODAY.minusDays(3));
    var key = MarketAuctionService.gemQuery("겁화", 10, 1).key();
    when(auctions.dailyRange(key, TODAY.minusDays(29))).thenReturn(stored);

    var ranged = service.gemHistory("겁화", 10, MarketHistoryRange.D30);

    assertThat(ranged.today()).isEqualTo(TODAY);
    assertThat(ranged.stored()).isEqualTo(stored);
    assertThat(MarketHistoryRangeService.gemCellKey("겁화", 10)).isEqualTo(key);
  }

  @Test
  void 장신구_칸은_표에_있는_조합만_받고_표와_같은_키로_읽는다() {
    // 딜러 목걸이 옵션 2개: 상중은 표에 있음
    var levels = List.of(Level.HIGH, Level.MID);
    var key =
        MarketHistoryRangeService.accessoryCellKey(
            Grade.ANCIENT, Role.DEALER, 70, Slot.NECKLACE, levels);
    when(auctions.dailyRange(key, TODAY.minusDays(89)))
        .thenReturn(new AuctionPort.AuctionDailyRange(List.of(), null));

    var ranged =
        service.accessoryHistory(
            Grade.ANCIENT, Role.DEALER, 70, Slot.NECKLACE, levels, MarketHistoryRange.D90);
    assertThat(ranged.stored().days()).isEmpty();
    verify(auctions).dailyRange(key, TODAY.minusDays(89));

    // 옵션 수와 다른 levels, 표에 없는 조합(하하)은 400
    assertThatThrownBy(
            () ->
                service.accessoryHistory(
                    Grade.ANCIENT,
                    Role.DEALER,
                    70,
                    Slot.NECKLACE,
                    List.of(Level.HIGH),
                    MarketHistoryRange.D30))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode())
                    .isEqualTo(MarketErrorCode.INVALID_AUCTION_LEVELS));
    assertThatThrownBy(
            () ->
                service.accessoryHistory(
                    Grade.ANCIENT,
                    Role.DEALER,
                    70,
                    Slot.NECKLACE,
                    List.of(Level.LOW, Level.LOW),
                    MarketHistoryRange.D30))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void 경매장_저장소를_읽지_못하면_503이다() {
    when(auctions.dailyRange(anyString(), any()))
        .thenThrow(new DataAccessResourceFailureException("db down"));
    assertThatThrownBy(() -> service.gemHistory("작열", 7, MarketHistoryRange.D30))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).errorCode())
                    .isEqualTo(MarketErrorCode.MARKET_CACHE_UNAVAILABLE));
  }
}
