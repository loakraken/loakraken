package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.AuctionPort.AuctionListing;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.AuctionQuery;
import com.loakraken.market.domain.AuctionCatalog;
import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.Role;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketAuctionServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");
  private final FakeAuctions port = new FakeAuctions();
  private final LostArkRequestGate gate = mock(LostArkRequestGate.class);
  private final MarketAuctionService service =
      new MarketAuctionService(port, port, gate, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void 보석_표는_20칸을_채우고_캐시된_칸은_다시_부르지_않으며_전일_대비를_준다() {
    when(gate.backgroundAvailable(anyInt())).thenReturn(true);
    var tenFire = MarketAuctionService.gemQuery("겁화", 10, 1).key();
    port.previous.put(tenFire, 2_000_000L);
    var cells = service.gems();
    assertThat(cells).hasSize(20);
    assertThat(cells.get(0).itemName()).isEqualTo("10레벨 겁화의 보석");
    assertThat(cells.get(10).itemName()).isEqualTo("10레벨 작열의 보석");
    assertThat(cells).allMatch(cell -> cell.status() == MarketAuctionService.Status.READY);
    assertThat(cells.get(0).cell().minBuyPrice()).isEqualTo(2_500_000L);
    assertThat(cells.get(0).previousDayChangeRate()).isEqualByComparingTo("25.0");
    assertThat(port.searches).isEqualTo(20);
    assertThat(port.daily).containsKey(tenFire + "@" + LocalDate.of(2026, 10, 9));
    service.gems();
    assertThat(port.searches).isEqualTo(20);
  }

  @Test
  void 사용자_몫이_없으면_칸을_채우지_않고_PENDING으로_준다() {
    when(gate.backgroundAvailable(anyInt())).thenReturn(true, true, true, false);
    var cells = service.gems();
    assertThat(port.searches).isEqualTo(3);
    assertThat(cells.stream().filter(cell -> cell.status() == MarketAuctionService.Status.PENDING))
        .hasSize(17);
    assertThat(cells.get(5).cell()).isNull();
  }

  @Test
  void 장신구_표는_한_요청에_최대_24칸만_채운다() {
    when(gate.backgroundAvailable(anyInt())).thenReturn(true);
    var slots = service.accessories(Grade.ANCIENT, Role.DEALER, 70);
    assertThat(slots).hasSize(3);
    assertThat(slots.get(0).cells()).hasSize(8);
    assertThat(slots.get(0).cells().get(0).label()).isEqualTo("상상");
    assertThat(slots.get(0).cells().get(5).optionLabels()).containsExactly("적주피 하", "추피 상");
    assertThat(port.searches).isEqualTo(24);
    // 서포터는 귀걸이가 무공% 단일 3칸이라 19칸이다.
    var support = service.accessories(Grade.ANCIENT, Role.SUPPORT, 70);
    assertThat(support.get(1).cells())
        .extracting(MarketAuctionService.AccessoryCell::label)
        .containsExactly("상", "중", "하");
    assertThat(port.searches).isEqualTo(24 + 19);
  }

  @Test
  void 장신구_조건은_공식_연마_값과_품질로_만든다() {
    var query =
        MarketAuctionService.accessoryQuery(
            Grade.ANCIENT,
            Slot.NECKLACE,
            AuctionCatalog.options(Role.DEALER, Slot.NECKLACE),
            List.of(Level.LOW, Level.HIGH),
            70,
            1);
    assertThat(query.categoryCode()).isEqualTo(200010);
    assertThat(query.itemGrade()).isEqualTo("고대");
    assertThat(query.minQuality()).isEqualTo(70);
    assertThat(query.etcOptions())
        .containsExactly(
            new AuctionPort.EtcFilter(7, 42, 55, 55), new AuctionPort.EtcFilter(7, 41, 260, 260));
    assertThat(AuctionCatalog.PolishOption.ENEMY_DAMAGE.levelOf(new BigDecimal("2.0")))
        .contains(Level.HIGH);
    assertThat(AuctionCatalog.PolishOption.ENEMY_DAMAGE.levelOf(new BigDecimal("0.9"))).isEmpty();
    assertThatThrownBy(
            () ->
                service.accessoryListings(
                    Grade.ANCIENT,
                    Role.SUPPORT,
                    Slot.EARRING,
                    List.of(Level.HIGH, Level.MID),
                    70,
                    1))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> MarketAuctionService.gemQuery("멸화", 10, 1))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void 전일_대비는_한쪽이_없으면_null이다() {
    assertThat(MarketAuctionService.changeRate(new AuctionCell(110L, 1, NOW), 100L))
        .isEqualByComparingTo("10.0");
    assertThat(MarketAuctionService.changeRate(new AuctionCell(null, 0, NOW), 100L)).isNull();
    assertThat(MarketAuctionService.changeRate(new AuctionCell(110L, 1, NOW), null)).isNull();
  }

  private static final class FakeAuctions implements AuctionPort, AuctionSearchPort {
    int searches;
    final Map<String, AuctionCell> cells = new HashMap<>();
    final Map<String, Long> previous = new HashMap<>();
    final Map<String, AuctionCell> daily = new HashMap<>();

    @Override
    public AuctionPage search(AuctionQuery query) {
      searches++;
      var items = new ArrayList<AuctionListing>();
      items.add(new AuctionListing("매물", "고대", 80, 0L, 100L, 100L, NOW, 0, List.of()));
      items.add(new AuctionListing("매물", "고대", 80, 2_500_000L, 0L, 0L, NOW, 0, List.of()));
      return new AuctionPage(25, items, NOW);
    }

    @Override
    public Optional<AuctionCell> findCell(String key) {
      return Optional.ofNullable(cells.get(key));
    }

    @Override
    public void saveCell(String key, AuctionCell cell) {
      cells.put(key, cell);
    }

    @Override
    public Optional<AuctionPage> findListings(String key) {
      return Optional.empty();
    }

    @Override
    public void saveListings(String key, AuctionPage page) {}

    @Override
    public void recordDaily(String key, LocalDate day, AuctionCell cell) {
      daily.put(key + "@" + day, cell);
    }

    @Override
    public Map<String, Long> previousDay(List<String> keys, LocalDate day) {
      return previous;
    }
  }
}
