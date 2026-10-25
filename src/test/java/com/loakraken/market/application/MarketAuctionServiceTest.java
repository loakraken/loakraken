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
import java.time.ZoneId;
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
  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private MarketAuctionService service = service();

  /** 일별 기록은 테스트에서 같은 스레드로 바로 쓴다. */
  private MarketAuctionService service() {
    return new MarketAuctionService(
        port, port, gate, clock, new MarketMetrics(meters), Runnable::run);
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

  @Test
  void 칸이_만료돼_채우지_못하면_마지막_관측_값을_그_시각과_함께_준다() {
    // PM 총괄 승인 10-25: 3분 칸이 비었을 때 '집계 중' 대신 마지막 관측 값(관측 시각 그대로)을 보인다.
    service.gems();
    assertThat(port.last).hasSize(5);
    port.cells.clear(); // 3분 칸 만료
    gate = gate(10); // 사용자 몫이 남지 않아 이번 요청은 채우지 못함
    service = service();
    var cells = service.gems();
    assertThat(port.searches).isEqualTo(5);
    var stale = cells.stream().filter(MarketAuctionService.GemCell::stale).toList();
    assertThat(stale)
        .hasSize(5)
        .allMatch(cell -> cell.status() == MarketAuctionService.Status.READY)
        .allMatch(cell -> cell.cell().observedAt().equals(NOW));
    assertThat(cells.stream().filter(cell -> cell.status() == MarketAuctionService.Status.PENDING))
        .hasSize(15);
    // 장신구 표 칸은 채워도 마지막 값을 남기지 않는다(키가 품질 조건마다 늘 수 있어서).
    gate = gate(90);
    service = service();
    int before = port.searches;
    service.accessories(Grade.ANCIENT, Role.DEALER, null);
    assertThat(port.searches).isGreaterThan(before);
    assertThat(port.last).hasSize(5);
  }

  @Test
  void 자정을_넘는_요청도_채운_칸과_마지막_값_칸을_같은_전날과_비교한다() {
    // 코드 리뷰 10-25: 요청 중에 KST 자정을 넘으면 채운 칸과 마지막 값 칸이 서로 다른 '전날'과 비교됐다.
    service.gems();
    port.cells.clear();
    port.previousDays.clear();
    gate = gate(10);
    var crossing =
        new MarketAuctionService(
            port,
            port,
            gate,
            ticking(Instant.parse("2026-10-09T14:59:59Z"), Instant.parse("2026-10-09T15:00:01Z")),
            new MarketMetrics(meters),
            Runnable::run);
    crossing.gems();
    assertThat(port.previousDays).hasSize(2).containsOnly(LocalDate.parse("2026-10-09"));
  }

  /** 처음 한 번은 first, 그 뒤로는 then을 돌려주는 시계(withZone으로 만든 시계도 같은 상태를 쓴다). */
  private static Clock ticking(Instant first, Instant then) {
    var used = new java.util.concurrent.atomic.AtomicBoolean();
    return ticking(first, then, used, ZoneOffset.UTC);
  }

  private static Clock ticking(
      Instant first, Instant then, java.util.concurrent.atomic.AtomicBoolean used, ZoneId zone) {
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return zone;
      }

      @Override
      public Clock withZone(ZoneId other) {
        return ticking(first, then, used, other);
      }

      @Override
      public Instant instant() {
        return used.getAndSet(true) ? then : first;
      }
    };
  }

  @Test
  void 연마_시세는_표와_같은_키의_캐시를_쓰고_스위치가_꺼져_있으면_채우지_않는다() {
    // 귀걸이 무공% 중 · 공% 중 = 표 칸(고대, 품질 조건 없음)과 같은 키. 줄 순서가 달라도 같은 칸.
    var key =
        MarketAuctionService.accessoryQuery(
                Grade.ANCIENT,
                Slot.EARRING,
                List.of(
                    AuctionCatalog.PolishOption.WEAPON_ATTACK_PERCENT,
                    AuctionCatalog.PolishOption.ATTACK_PERCENT),
                List.of(Level.MID, Level.MID),
                null,
                1)
            .key();
    port.cells.put(key, new AuctionCell(6_000L, 3, NOW));
    var lines =
        List.of(
            new MarketAuctionService.PolishLine("공격력", true, "+0.95%"),
            new MarketAuctionService.PolishLine("무기 공격력", true, "+1.80%"));
    var off = new MarketAuctionService.PolishFill(false);
    var hit = service.polishPrice("귀걸이", lines, off);
    assertThat(hit.status()).isEqualTo(MarketAuctionService.PolishStatus.OK);
    assertThat(hit.minBuyPrice()).isEqualTo(6_000L);
    var other =
        List.of(
            new MarketAuctionService.PolishLine("공격력", true, "+0.40%"),
            new MarketAuctionService.PolishLine("무기 공격력", true, "+1.80%"));
    assertThat(service.polishPrice("귀걸이", other, off).status())
        .isEqualTo(MarketAuctionService.PolishStatus.MISSING);
    assertThat(port.searches).isZero();
  }

  @Test
  void 연마_시세_채우기는_요청마다_5칸까지다() {
    var fill = new MarketAuctionService.PolishFill(true);
    var levels = List.of("+0.40%", "+0.95%", "+1.55%");
    int ok = 0;
    for (var a : levels)
      for (var b : List.of("+0.80%", "+1.80%")) {
        var price =
            service.polishPrice(
                "귀걸이",
                List.of(
                    new MarketAuctionService.PolishLine("공격력", true, a),
                    new MarketAuctionService.PolishLine("무기 공격력", true, b)),
                fill);
        if (price.status() == MarketAuctionService.PolishStatus.OK) ok++;
      }
    assertThat(ok).isEqualTo(5);
    assertThat(port.searches).isEqualTo(5);
    // 지표: 5칸 채움, 6번째는 몫이 없어 no_budget
    assertThat(polishEvents("filled")).isEqualTo(5);
    assertThat(polishEvents("no_budget")).isEqualTo(1);
  }

  /** 연마 시세 지표 사건(observability 10-25): 캐시·꺼짐·잠금·공식 실패·표현 불가를 나눠 센다. 잠금을 못 잡으면 몫을 쓰지 않는다. */
  @Test
  void 연마_시세_지표는_사건마다_세고_잠금을_못_잡으면_몫을_쓰지_않는다() throws Exception {
    var lines =
        List.of(
            new MarketAuctionService.PolishLine("공격력", true, "+0.95%"),
            new MarketAuctionService.PolishLine("무기 공격력", true, "+1.80%"));
    service.polishPrice("귀걸이", lines, new MarketAuctionService.PolishFill(false));
    assertThat(polishEvents("fill_off")).isEqualTo(1);
    service.polishPrice(
        "귀걸이",
        List.of(new MarketAuctionService.PolishLine("공격력", false, "+80")),
        new MarketAuctionService.PolishFill(true));
    assertThat(polishEvents("unsupported")).isEqualTo(1);
    // 다른 스레드가 채우기 잠금을 잡고 있으면 lock_busy, 몫은 그대로 5칸
    var lock = MarketAuctionService.class.getDeclaredField("fillLock");
    lock.setAccessible(true);
    var fillLock = (java.util.concurrent.locks.ReentrantLock) lock.get(service);
    var held = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var holder =
        new Thread(
            () -> {
              fillLock.lock();
              held.countDown();
              try {
                release.await();
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
              } finally {
                fillLock.unlock();
              }
            });
    holder.start();
    held.await();
    var fill = new MarketAuctionService.PolishFill(true);
    assertThat(service.polishPrice("귀걸이", lines, fill).status())
        .isEqualTo(MarketAuctionService.PolishStatus.MISSING);
    assertThat(polishEvents("lock_busy")).isEqualTo(1);
    release.countDown();
    holder.join();
    assertThat(service.polishPrice("귀걸이", lines, fill).status())
        .isEqualTo(MarketAuctionService.PolishStatus.OK);
    assertThat(polishEvents("filled")).isEqualTo(1);
    // 이제 캐시에 있다
    service.polishPrice("귀걸이", lines, new MarketAuctionService.PolishFill(false));
    assertThat(polishEvents("cache_hit")).isEqualTo(1);
    // 공식 호출 한도면 official_failed, 그 요청은 더 채우지 않는다
    gate = gate(10);
    service = service();
    var other =
        List.of(
            new MarketAuctionService.PolishLine("공격력", true, "+0.40%"),
            new MarketAuctionService.PolishLine("무기 공격력", true, "+1.80%"));
    var limited = new MarketAuctionService.PolishFill(true);
    assertThat(service.polishPrice("귀걸이", other, limited).status())
        .isEqualTo(MarketAuctionService.PolishStatus.MISSING);
    assertThat(polishEvents("official_failed")).isEqualTo(1);
  }

  private double polishEvents(String event) {
    var counter = meters.find("loakraken.market.polish_fill").tag("event", event).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void 경매장_옵션으로_나타낼_수_없는_연마_줄은_시세를_찾지_않는다() {
    var fill = new MarketAuctionService.PolishFill(true);
    // 고정값 줄, 그 부위에 붙지 않는 옵션, 세 줄인데 모두 상이 아님, 표에 없는 값
    for (var lines :
        List.of(
            List.of(new MarketAuctionService.PolishLine("공격력", false, "+80")),
            List.of(new MarketAuctionService.PolishLine("치명타 적중률", true, "+0.95%")),
            List.of(
                new MarketAuctionService.PolishLine("공격력", true, "+1.55%"),
                new MarketAuctionService.PolishLine("무기 공격력", true, "+3.00%"),
                new MarketAuctionService.PolishLine("공격력", true, "+0.40%")),
            List.of(new MarketAuctionService.PolishLine("공격력", true, "+0.41%"))))
      assertThat(service.polishPrice("귀걸이", lines, fill).status())
          .as(lines.toString())
          .isEqualTo(MarketAuctionService.PolishStatus.UNSUPPORTED);
    assertThat(port.searches).isZero();
  }

  @Test
  void 스펙업_보석_시세는_3분_칸이_없으면_마지막_값을_stale로_준다() {
    // PM 총괄 10-25: 이전 시세는 그 관측 시각과 함께 stale로만(지금 시세처럼 보이지 않게).
    service.gems();
    port.cells.clear();
    var cells = service.cachedGems();
    var stale = cells.stream().filter(MarketAuctionService.GemCell::stale).toList();
    assertThat(stale)
        .hasSize(5)
        .allMatch(cell -> cell.status() == MarketAuctionService.Status.READY)
        .allMatch(cell -> cell.cell().observedAt().equals(NOW));
    assertThat(cells.stream().filter(cell -> cell.status() == MarketAuctionService.Status.PENDING))
        .hasSize(15);
    // 3분 칸이 있으면 그 칸(stale 아님)
    service.gems();
    assertThat(service.cachedGems().stream().filter(cell -> cell.cell() != null && !cell.stale()))
        .isNotEmpty();
  }

  @Test
  void 보석_칸_예열은_오래된_칸부터_정한_칸_수만_받고_한도에서_멈춘다() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var first = service.warmGems(2, () -> true, search -> {
      calls.incrementAndGet();
      return search.get();
    });
    assertThat(first.refreshed()).isEqualTo(2);
    assertThat(first.outcome()).isEqualTo("refreshed");
    assertThat(port.last).hasSize(2);
    // 이미 받은 칸은 뒤로 — 다음 예열은 다른 칸부터
    var second = service.warmGems(2, () -> true, search -> search.get());
    assertThat(second.refreshed()).isEqualTo(2);
    assertThat(port.last).hasSize(4);
    // 분당 상한
    var capped = service.warmGems(2, () -> false, search -> search.get());
    assertThat(capped.outcome()).isEqualTo("capped");
    assertThat(capped.refreshed()).isZero();
    assertThat(MarketAuctionService.GEM_CELLS).isEqualTo(20);
  }

  private final class FakeAuctions implements AuctionPort, AuctionSearchPort {
    int searches;
    final Map<String, AuctionCell> cells = new HashMap<>();
    final Map<String, Long> previous = new HashMap<>();
    final Map<String, AuctionCell> daily = new HashMap<>();
    final Map<String, AuctionCell> last = new HashMap<>();

    @Override
    public Optional<AuctionCell> findLastCell(String key) {
      return Optional.ofNullable(last.get(key));
    }

    @Override
    public void saveLastCell(String key, AuctionCell cell) {
      last.put(key, cell);
    }

    @Override
    public AuctionPage search(AuctionQuery query) {
      gate.acquire();
      gate.release();
      searches++;
      var items = new ArrayList<AuctionListing>();
      items.add(new AuctionListing("매물", "고대", 80, 0L, 100L, 100L, NOW, 0, List.of(), null));
      items.add(new AuctionListing("매물", "고대", 80, 2_500_000L, 0L, 0L, NOW, 0, List.of(), null));
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

    final List<LocalDate> previousDays = new ArrayList<>();

    @Override
    public Map<String, Long> previousDay(List<String> keys, LocalDate day) {
      previousDays.add(day);
      return previous;
    }
  }
}
