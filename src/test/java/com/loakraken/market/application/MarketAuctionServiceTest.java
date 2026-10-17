package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkMetrics;
import com.loakraken.common.lostark.LostArkProperties;
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
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private LostArkRequestGate gate = gate(90);
  private final FakeAuctions port = new FakeAuctions();
  private MarketAuctionService service = service();

  /** 일별 기록은 테스트에서 같은 스레드로 바로 쓴다. */
  private MarketAuctionService service() {
    return new MarketAuctionService(port, port, gate, clock, Runnable::run);
  }

  /** 실제 메모리 게이트. 가짜 검색이 실제 클라이언트처럼 호출마다 acquire한다. */
  private LostArkRequestGate gate(int budget) {
    return new LostArkRequestGate(
        clock,
        new LostArkProperties(
            java.net.URI.create("https://example.com"),
            "test",
            java.time.Duration.ofSeconds(3),
            java.time.Duration.ofSeconds(5),
            budget,
            4,
            java.time.Duration.ofMinutes(5)),
        new LostArkMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
  }

  @Test
  void 보석_표는_요청마다_5칸씩_채우고_캐시된_칸은_다시_부르지_않으며_전일_대비를_준다() {
    var tenFire = MarketAuctionService.gemQuery("겁화", 10, 1).key();
    port.previous.put(tenFire, 2_000_000L);
    // 첫 요청: 5칸만 채우고 나머지 15칸은 PENDING(#526).
    var firstVisit = service.gems();
    assertThat(port.searches).isEqualTo(5);
    assertThat(
            firstVisit.stream().filter(cell -> cell.status() == MarketAuctionService.Status.READY))
        .hasSize(5);
    for (int visit = 2; visit <= 4; visit++) service.gems();
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
    // 예산 43 − 사용자 몫 40 = 3칸만 채울 수 있다.
    gate = gate(43);
    service = service();
    var cells = service.gems();
    assertThat(port.searches).isEqualTo(3);
    assertThat(cells.stream().filter(cell -> cell.status() == MarketAuctionService.Status.PENDING))
        .hasSize(17);
    assertThat(cells.get(5).cell()).isNull();
    // 칸 채우기가 사용자 몫을 남겼으므로 사용자 경로(reserve 0)는 아직 호출할 수 있다.
    gate.acquire();
    gate.release();
  }

  @Test
  void 사용자_몫이_처음부터_없으면_공식_호출이_0이다() {
    gate = gate(40);
    service = service();
    var cells = service.gems();
    assertThat(port.searches).isZero();
    assertThat(cells).allMatch(cell -> cell.status() == MarketAuctionService.Status.PENDING);
  }

  @Test
  void 장신구_표는_한_요청에_최대_5칸만_채운다() {
    var slots = service.accessories(Grade.ANCIENT, Role.DEALER, 70);
    assertThat(slots).hasSize(3);
    assertThat(slots.get(0).cells()).hasSize(8);
    assertThat(slots.get(0).cells().get(0).label()).isEqualTo("상상");
    assertThat(slots.get(0).cells().get(5).optionLabels()).containsExactly("적주피 하", "추피 상");
    assertThat(port.searches).isEqualTo(5);
    // 딜러 표 24칸은 5번째 요청에서 다 찬다(5 × 4 + 4).
    for (int visit = 2; visit <= 5; visit++) service.accessories(Grade.ANCIENT, Role.DEALER, 70);
    assertThat(port.searches).isEqualTo(24);
    // 서포터는 귀걸이가 무공% 단일 3칸이라 19칸이다(요청마다 5칸).
    var support = service.accessories(Grade.ANCIENT, Role.SUPPORT, 70);
    assertThat(support.get(1).cells())
        .extracting(MarketAuctionService.AccessoryCell::label)
        .containsExactly("상", "중", "하");
    assertThat(port.searches).isEqualTo(24 + 5);
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

  private final class FakeAuctions implements AuctionPort, AuctionSearchPort {
    int searches;
    final Map<String, AuctionCell> cells = new HashMap<>();
    final Map<String, Long> previous = new HashMap<>();
    final Map<String, AuctionCell> daily = new HashMap<>();

    @Override
    public AuctionPage search(AuctionQuery query) {
      gate.acquire();
      gate.release();
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
    public Optional<String> acquireGemFilterQuota(int limit) {
      return Optional.of("quota");
    }

    @Override
    public void releaseGemFilterQuota(String key) {}

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
