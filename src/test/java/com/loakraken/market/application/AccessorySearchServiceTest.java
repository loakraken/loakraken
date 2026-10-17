package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.ClientLimiter;
import com.loakraken.common.lostark.LostArkMetrics;
import com.loakraken.common.lostark.LostArkProperties;
import com.loakraken.common.lostark.LostArkRequestGate;
import com.loakraken.market.application.AccessorySearch.Command;
import com.loakraken.market.application.AccessorySearch.PolishFilter;
import com.loakraken.market.application.AuctionPort.AuctionListing;
import com.loakraken.market.application.AuctionPort.AuctionPage;
import com.loakraken.market.application.AuctionPort.AuctionQuery;
import com.loakraken.market.application.AuctionPort.EtcFilter;
import com.loakraken.market.application.AuctionPort.ListingOption;
import com.loakraken.market.domain.AuctionCatalog.Grade;
import com.loakraken.market.domain.AuctionCatalog.Level;
import com.loakraken.market.domain.AuctionCatalog.PolishOption;
import com.loakraken.market.domain.AuctionCatalog.Slot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AccessorySearchServiceTest {
  private static final Instant NOW = Instant.parse("2026-10-12T03:00:20Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final FakeStore store = new FakeStore();
  private final AtomicInteger calls = new AtomicInteger();
  private final List<AuctionQuery> queries = new ArrayList<>();
  private Function<AuctionQuery, AuctionPage> pages = query -> page(10, 25, 17000);
  private LostArkRequestGate gate = spy(gate(90));

  /** 실제 메모리 게이트. 가짜 검색이 실제 클라이언트처럼 호출마다 acquire한다. */
  private LostArkRequestGate gate(int budget) {
    return new LostArkRequestGate(
        clock,
        new LostArkProperties(
            URI.create("https://example.com"),
            "test",
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            budget,
            4,
            Duration.ofMinutes(5)),
        new LostArkMetrics(new SimpleMeterRegistry()));
  }

  private AccessorySearchService service() {
    AuctionSearchPort search =
        query -> {
          gate.acquire();
          try {
            calls.incrementAndGet();
            synchronized (queries) {
              queries.add(query);
            }
            return pages.apply(query);
          } finally {
            gate.release();
          }
        };
    return new AccessorySearchService(
        search,
        store,
        gate,
        new AccessorySearchPolicy(20, 30, 3, Duration.ofSeconds(120)),
        new MarketMetrics(new SimpleMeterRegistry()),
        clock);
  }

  private static Command command(List<PolishFilter> polish, Integer pct) {
    return new Command(Slot.NECKLACE, Grade.ANCIENT, polish, null, pct, null, 1);
  }

  @Test
  void 조건은_공식_서버_필터로_바뀐다() {
    var query =
        AccessorySearchService.query(
            new Command(
                Slot.NECKLACE,
                Grade.ANCIENT,
                List.of(
                    new PolishFilter(PolishOption.ENEMY_DAMAGE, Level.HIGH),
                    new PolishFilter(PolishOption.ADDITIONAL_DAMAGE, null)),
                12,
                50,
                70,
                2));
    assertThat(query.categoryCode()).isEqualTo(200010);
    assertThat(query.itemTier()).isEqualTo(4);
    assertThat(query.itemGrade()).isEqualTo("고대");
    assertThat(query.minQuality()).isEqualTo(70);
    assertThat(query.pageNo()).isEqualTo(2);
    assertThat(query.etcOptions())
        .containsExactly(
            // 하·중·상 값은 PolishOption과 같다.
            new EtcFilter(7, 42, PolishOption.ENEMY_DAMAGE.value(Level.HIGH), 200),
            // 단계 무관이면 T4 하~상 범위다.
            new EtcFilter(7, 41, 70, 260),
            // 깨달음 최솟값 ~ 고대 목걸이 최댓값 13.
            new EtcFilter(8, 1, 12, 13),
            // 범위 % 50의 0단계 하한 12678 + floor(2679 × 0.5) ~ 3단계 최댓값.
            new EtcFilter(1, 11, 12678 + 1339, 17857));
  }

  @Test
  void 매물은_연마_줄_깨달음_힘민지_범위_퍼센트로_바뀐다() {
    var item = AccessorySearchService.item(Slot.NECKLACE, listing(17000, 1000L));
    assertThat(item.polishStage()).isEqualTo(3);
    assertThat(item.enlightenment()).isEqualTo(13);
    assertThat(item.mainStat()).isEqualTo(17000);
    // (17000 − 15178) ÷ 2679 × 100 = 68.01
    assertThat(item.mainStatRangePct()).isEqualByComparingTo("68.01");
    assertThat(item.polish().get(0).option()).isEqualTo(PolishOption.ADDITIONAL_DAMAGE);
    assertThat(item.polish().get(0).level()).isEqualTo(Level.MID);
    // 공식 이름 끝 공백은 지운다. 무기 공격력 +(고정값)은 검색 조건 옵션이 아니다.
    assertThat(item.polish().get(1).name()).isEqualTo("무기 공격력");
    assertThat(item.polish().get(1).option()).isNull();
    assertThat(item.polish().get(1).level()).isNull();
  }

  @Test
  void 기본_효과는_STAT_줄만_공식_순서로_담고_이미지가_없으면_null이다() {
    var item = AccessorySearchService.item(Slot.NECKLACE, listing(17000, 1000L));
    assertThat(item.icon()).isNull();
    assertThat(item.baseEffects())
        .extracting(AccessorySearch.BaseEffect::name)
        .containsExactly("힘", "민첩", "지능", "체력");
    assertThat(item.baseEffects().get(3).value()).isEqualByComparingTo("4100");
    var noStat =
        AccessorySearchService.item(
            Slot.NECKLACE,
            new AuctionListing("목걸이", "고대", 90, 1000L, 0L, 1000L, NOW, 0, List.of(), " "));
    assertThat(noStat.baseEffects()).isEmpty();
    assertThat(noStat.icon()).isNull();
  }

  @Test
  void 입찰만_가능한_매물의_즉시_구매가는_0이_아니라_null이다() {
    var bidOnly = listing(17000, 0L);
    var item = AccessorySearchService.item(Slot.NECKLACE, bidOnly);
    assertThat(item.buyPrice()).isNull();
    assertThat(AccessorySearchService.item(Slot.NECKLACE, listing(17000, 1500L)).buyPrice())
        .isEqualTo(1500L);
  }

  @Test
  void 캐시_적중이면_공식_호출이_0이다() {
    var service = service();
    var first = service.search(command(List.of(), null));
    assertThat(first.cached()).isFalse();
    assertThat(first.complete()).isTrue();
    assertThat(first.scannedPages()).isEqualTo(1);
    assertThat(first.nextPage()).isEqualTo(2);
    var second = service.search(command(List.of(), null));
    assertThat(second.cached()).isTrue();
    assertThat(second.items()).hasSize(10);
    assertThat(calls).hasValue(1);
    assertThat(store.ttl).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void 빈_결과도_캐시한다() {
    pages = query -> page(0, 0, 17000);
    var service = service();
    var result = service.search(command(List.of(), null));
    assertThat(result.items()).isEmpty();
    assertThat(result.nextPage()).isNull();
    assertThat(service.search(command(List.of(), null)).cached()).isTrue();
    assertThat(calls).hasValue(1);
  }

  @Test
  void 같은_조건_동시_요청은_공식_호출_하나로_묶는다() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    pages =
        query -> {
          entered.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return page(10, 25, 17000);
        };
    var service = service();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(() -> service.search(command(List.of(), null)));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var b = executor.submit(() -> service.search(command(List.of(), null)));
      Thread.sleep(100);
      release.countDown();
      assertThat(a.get(5, TimeUnit.SECONDS).items()).hasSize(10);
      assertThat(b.get(5, TimeUnit.SECONDS).items()).hasSize(10);
    }
    assertThat(calls).hasValue(1);
  }

  @Test
  void 공식_호출은_사용자_몫_30을_남기고_클라이언트_한도에도_매긴다() {
    service().search(command(List.of(), null));
    verify(gate).withUserReserve(eq(30), any());
    verify(gate).chargeClient(ClientLimiter.Bucket.AUCTION_SEARCH);
  }

  @Test
  void 기능_몫을_넘으면_503_BUSY와_Retry_After이고_캐시된_결과는_계속_준다() {
    var service = service();
    service.search(command(List.of(), null));
    store.quotaLeft = 0;
    assertThatThrownBy(
            () ->
                service.search(
                    command(List.of(new PolishFilter(PolishOption.ENEMY_DAMAGE, null)), null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> {
              assertThat(exception.errorCode()).isEqualTo(MarketErrorCode.MARKET_SEARCH_BUSY);
              // 03:00:20 → 이번 분이 끝날 때까지 40초.
              assertThat(exception.retryAfter()).contains(Duration.ofSeconds(40));
            });
    assertThat(service.search(command(List.of(), null)).cached()).isTrue();
    assertThat(calls).hasValue(1);
  }

  @Test
  void 공유_게이트가_사용자_몫만_남았으면_503_BUSY다() {
    // 예산 30 = reserve 30이라 이 기능은 한 번도 부를 수 없다.
    gate = spy(gate(30));
    assertThatThrownBy(() -> service().search(command(List.of(), null)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.errorCode()).isEqualTo(MarketErrorCode.MARKET_SEARCH_BUSY));
    assertThat(calls).hasValue(0);
    assertThat(store.saved).isEmpty();
  }

  @Test
  void 범위_퍼센트_뒤_필터는_3페이지까지만_읽고_complete_false다() {
    // 페이지마다 10개 중 4개(18000, 범위 %≥ 100)만 조건에 맞는다. 3페이지 12개.
    pages = query -> mixedPage(query.pageNo(), 100);
    var result = service().search(command(List.of(), 90));
    assertThat(calls).hasValue(3);
    // 클라이언트 한도는 검색 한 번이 아니라 공식 호출(페이지)마다 센다.
    verify(gate, times(3)).chargeClient(ClientLimiter.Bucket.AUCTION_SEARCH);
    assertThat(queries).extracting(AuctionQuery::pageNo).containsExactly(1, 2, 3);
    assertThat(result.scannedPages()).isEqualTo(3);
    assertThat(result.items()).hasSize(12);
    assertThat(result.items())
        .allMatch(item -> item.mainStatRangePct().compareTo(BigDecimal.valueOf(90)) >= 0);
    // 10개가 찼으므로 complete다.
    assertThat(result.complete()).isTrue();
    assertThat(result.nextPage()).isEqualTo(4);

    calls.set(0);
    pages = query -> mixedPage(query.pageNo(), 100, 1);
    var sparse =
        service().search(new Command(Slot.NECKLACE, Grade.ANCIENT, List.of(), null, 95, null, 2));
    assertThat(calls).hasValue(3);
    assertThat(sparse.items()).hasSize(3);
    assertThat(sparse.complete()).isFalse();
    assertThat(sparse.scannedPages()).isEqualTo(3);
    assertThat(sparse.page()).isEqualTo(2);
    assertThat(sparse.nextPage()).isEqualTo(5);
  }

  @Test
  void 공식_매물을_끝까지_읽으면_complete_true이고_다음_페이지가_없다() {
    pages = query -> query.pageNo() == 1 ? mixedPage(1, 15, 1) : page(5, 15, 12000);
    var result = service().search(command(List.of(), 95));
    assertThat(calls).hasValue(2);
    assertThat(result.items()).hasSize(1);
    assertThat(result.complete()).isTrue();
    assertThat(result.nextPage()).isNull();
  }

  @Test
  void 뒤_필터_중간에_몫이_떨어지면_읽은_만큼_주고_캐시하지_않는다() {
    pages = query -> mixedPage(query.pageNo(), 100, 1);
    store.quotaLeft = 2;
    var result = service().search(command(List.of(), 95));
    assertThat(calls).hasValue(2);
    assertThat(result.scannedPages()).isEqualTo(2);
    assertThat(result.complete()).isFalse();
    assertThat(result.nextPage()).isEqualTo(3);
    assertThat(store.saved).isEmpty();
  }

  @Test
  void 잘못된_조건은_INVALID_AUCTION_FILTER이고_공식_호출이_없다() {
    var service = service();
    for (var command :
        List.of(
            // 목걸이에 치피(반지 효과)
            command(List.of(new PolishFilter(PolishOption.CRIT_DAMAGE, null)), null),
            // 같은 효과 두 번
            command(
                List.of(
                    new PolishFilter(PolishOption.ENEMY_DAMAGE, Level.HIGH),
                    new PolishFilter(PolishOption.ENEMY_DAMAGE, Level.LOW)),
                null),
            // 4줄
            command(
                List.of(
                    new PolishFilter(PolishOption.ENEMY_DAMAGE, null),
                    new PolishFilter(PolishOption.ADDITIONAL_DAMAGE, null),
                    new PolishFilter(PolishOption.BRAND, null),
                    new PolishFilter(PolishOption.IDENTITY_GAUGE, null)),
                null),
            // 유물 반지 깨달음 최댓값 9를 넘음
            new Command(Slot.RING, Grade.RELIC, List.of(), 10, null, null, 1),
            new Command(Slot.RING, Grade.RELIC, List.of(), null, 101, null, 1),
            new Command(Slot.RING, Grade.RELIC, List.of(), null, null, null, 11)))
      assertThatThrownBy(() -> service.search(command))
          .as(command.toString())
          .isInstanceOfSatisfying(
              BusinessException.class,
              exception ->
                  assertThat(exception.errorCode())
                      .isEqualTo(MarketErrorCode.INVALID_AUCTION_FILTER));
    assertThat(calls).hasValue(0);
  }

  private static AuctionPage page(int size, long total, int mainStat) {
    var items = new ArrayList<AuctionListing>();
    for (int i = 0; i < size; i++) items.add(listing(mainStat, 1000L + i));
    return new AuctionPage(total, items, NOW);
  }

  /** 10개 중 앞 matching개는 힘민지 17857(범위 % 100), 나머지는 15178(0%). */
  private static AuctionPage mixedPage(int pageNo, long total, int matching) {
    var items = new ArrayList<AuctionListing>();
    for (int i = 0; i < 10; i++)
      items.add(listing(i < matching ? 17857 : 15178, pageNo * 100L + i));
    return new AuctionPage(total, items, NOW);
  }

  private static AuctionPage mixedPage(int pageNo, long total) {
    return mixedPage(pageNo, total, 4);
  }

  /** 공식 응답 표본(2026-10-12 고대 목걸이 3연마)과 같은 모양. */
  private static AuctionListing listing(int mainStat, long price) {
    return new AuctionListing(
        "도래한 결전의 목걸이",
        "고대",
        94,
        price,
        0L,
        price,
        NOW.plusSeconds(3600),
        2,
        List.of(
            new ListingOption("ARK_PASSIVE", "깨달음", new BigDecimal("13.0"), false, null),
            new ListingOption("ACCESSORY_UPGRADE", "추가 피해", new BigDecimal("1.6"), true, null),
            new ListingOption("ACCESSORY_UPGRADE", "무기 공격력 ", new BigDecimal("195.0"), false, null),
            new ListingOption("ACCESSORY_UPGRADE", "최대 생명력", new BigDecimal("3250.0"), false, null),
            new ListingOption("STAT", "힘", BigDecimal.valueOf(mainStat), false, null),
            new ListingOption("STAT", "민첩", BigDecimal.valueOf(mainStat), false, null),
            new ListingOption("STAT", "지능", BigDecimal.valueOf(mainStat), false, null),
            new ListingOption("STAT", "체력", new BigDecimal("4100.0"), false, null)));
  }

  private static final class FakeStore implements AccessorySearchStorePort {
    final Map<String, AccessorySearch.Result> saved = new HashMap<>();
    Duration ttl;
    int quotaLeft = Integer.MAX_VALUE;

    @Override
    public synchronized Optional<AccessorySearch.Result> find(String key) {
      return Optional.ofNullable(saved.get(key));
    }

    @Override
    public synchronized void save(String key, AccessorySearch.Result result, Duration ttl) {
      saved.put(key, result);
      this.ttl = ttl;
    }

    @Override
    public synchronized boolean tryAcquireQuota(int limit) {
      if (quotaLeft <= 0) return false;
      quotaLeft--;
      return true;
    }
  }
}
