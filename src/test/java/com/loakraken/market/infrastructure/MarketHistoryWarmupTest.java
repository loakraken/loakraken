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
import com.loakraken.market.application.MarketResult;
import com.loakraken.market.application.MarketSource;
import com.loakraken.market.application.RelicBookPriceService;
import com.loakraken.market.application.RelicBookScan;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketPrice;
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
  private final RelicBookPriceService relicBooks = mock(RelicBookPriceService.class);
  private RelicBookWarmupProperties relicProperties =
      new RelicBookWarmupProperties(false, Duration.ofMinutes(30), 10);
  private final com.loakraken.market.application.RefiningMaterialPriceService refiningMaterials =
      mock(com.loakraken.market.application.RefiningMaterialPriceService.class);
  private RefiningMaterialWarmupProperties refiningMaterialProperties =
      new RefiningMaterialWarmupProperties(false, Duration.ofMinutes(10), 5);
  private final com.loakraken.market.application.MaterialUnitPriceService lifeMaterials =
      mock(com.loakraken.market.application.MaterialUnitPriceService.class);
  private LifeMaterialWarmupProperties lifeMaterialProperties =
      new LifeMaterialWarmupProperties(false, Duration.ofMinutes(10), 6);

  {
    when(lease.hold(any(), any())).thenReturn(true);
    when(gate.withReserve(anyInt(), any()))
        .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
  }

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<Object> published = new java.util.ArrayList<>();

  @Test
  void 보드를_갱신하면_받은_가격을_시세_알림에_알리고_실패해도_예열은_계속한다() {
    when(boards.refreshDefaultPage(MarketTab.REFINING)).thenReturn(page(1, 3));
    warmup("key", List.of(), 15).warmBoards();
    assertThat(published).hasSize(1);
    var event = (com.loakraken.market.application.MarketPricesObserved) published.getFirst();
    assertThat(event.observedAt()).isEqualTo(clock.now);
    assertThat(event.sampleEvery()).isEqualTo(Duration.ofSeconds(30));
    assertThat(event.prices()).extracting(MarketPrice::id).containsExactly(1000L, 1001L, 1002L);

    // 듣는 쪽이 실패해도 예외가 예열 밖으로 나가지 않는다.
    var failing =
        warmup(
            "key",
            List.of(),
            15,
            event2 -> {
              throw new IllegalStateException("듣는 쪽 실패");
            });
    org.assertj.core.api.Assertions.assertThatCode(failing::warmBoards).doesNotThrowAnyException();
  }

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
    // 이력 예열과 같은 임대(#158 P2)
    verify(lease).hold(eq(MarketHistoryWarmup.LEASE), eq(Duration.ofMinutes(10)));
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
    when(lease.hold(eq(MarketHistoryWarmup.LEASE), any())).thenReturn(false);
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
    return warmup(key, ids, maxCalls, published::add);
  }

  private MarketHistoryWarmup warmup(
      String key,
      List<Long> ids,
      int maxCalls,
      org.springframework.context.ApplicationEventPublisher events) {
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
        lease,
        relicBooks,
        relicProperties,
        refiningMaterials,
        refiningMaterialProperties,
        lifeMaterials,
        lifeMaterialProperties,
        events);
  }

  @Test
  void 관문이_미룬_예열은_DEFERRED로_공식_429는_FAILED로_이유와_함께_남기고_다음_주기에_다시_채운다() {
    lifeMaterialProperties = new LifeMaterialWarmupProperties(true, Duration.ofMinutes(10), 6);
    when(lifeMaterials.fetchCategoryPage(anyInt(), anyInt()))
        .thenAnswer(invocation -> page(invocation.getArgument(1), 34));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(MarketHistoryWarmup.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var warmup = warmup("key", List.of());
      // 1주기: 관문이 사용자 몫(reserve)을 남기려고 미룸 → 저장 없음, DEFERRED, 이유 GATE_BUDGET.
      doThrow(
              new com.loakraken.common.lostark.LostArkRateLimitedException(
                  com.loakraken.common.lostark.LostArkRateLimitedException.Cause.GATE_BUDGET))
          .when(gate)
          .withReserve(anyInt(), any());
      warmup.warmLifeMaterialPages();
      verify(lifeMaterials, never()).saveCategoryScan(anyInt(), any(), any());
      assertThat(lifeCount("deferred")).isEqualTo(1);
      assertThat(lifeCount("failed")).isZero();
      // 2주기(1분 뒤): 공식 서버 429 → FAILED, 이유 UPSTREAM_429.
      doThrow(
              new com.loakraken.common.lostark.LostArkRateLimitedException(
                  com.loakraken.common.lostark.LostArkRateLimitedException.Cause.UPSTREAM_429))
          .when(gate)
          .withReserve(anyInt(), any());
      clock.now = clock.now.plus(Duration.ofMinutes(1));
      warmup.warmLifeMaterialPages();
      verify(lifeMaterials, never()).saveCategoryScan(anyInt(), any(), any());
      assertThat(lifeCount("failed")).isEqualTo(1);
      assertThat(appender.list)
          .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .anyMatch(line -> line.contains("관문이 미뤘습니다") && line.contains("reason=GATE_BUDGET"))
          .anyMatch(line -> line.contains("실패했습니다") && line.contains("reason=UPSTREAM_429"));
      // 3주기: 관문이 허가하면 미뤘던 생활 시세를 다시 채운다(주기 대기 없이 다음 틱).
      doAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get())
          .when(gate)
          .withReserve(anyInt(), any());
      clock.now = clock.now.plus(Duration.ofMinutes(1));
      warmup.warmLifeMaterialPages();
      verify(lifeMaterials).saveCategoryScan(eq(90000), any(), eq(Duration.ofMinutes(30)));
      verify(lifeMaterials).saveCategoryScan(eq(60000), any(), eq(Duration.ofMinutes(30)));
      assertThat(lifeCount("refreshed")).isEqualTo(2);
    } finally {
      logger.detachAppender(appender);
    }
  }

  private double lifeCount(String outcome) {
    var counter =
        registry
            .find("loakraken.market.warmup")
            .tag("target", "life_materials")
            .tag("outcome", outcome)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void 생활_재료_예열은_플래그가_꺼져_있으면_부르지_않는다() {
    warmup("key", List.of()).warmLifeMaterialPages();
    verifyNoInteractions(lifeMaterials);
  }

  @Test
  void 분류_예열은_생활과_전투_용품을_분류마다_끝까지_받아_다_받음으로_저장하고_주기_전에는_다시_부르지_않는다() {
    lifeMaterialProperties = new LifeMaterialWarmupProperties(true, Duration.ofMinutes(10), 6);
    // 생활 분류 34종 = 4페이지(2026-10-14 공식 TotalCount), 전투 용품은 시험값 15종 = 2페이지.
    when(lifeMaterials.fetchCategoryPage(eq(90000), anyInt()))
        .thenAnswer(invocation -> page(invocation.getArgument(1), 34));
    when(lifeMaterials.fetchCategoryPage(eq(60000), anyInt()))
        .thenAnswer(invocation -> page(invocation.getArgument(1), 15));
    var capped = warmup("key", List.of(), 3);
    capped.warmLifeMaterialPages();
    verify(lifeMaterials, times(3)).fetchCategoryPage(anyInt(), anyInt());
    verify(lifeMaterials, never()).saveCategoryScan(anyInt(), any(), any());

    var warmup = warmup("key", List.of());
    warmup.warmLifeMaterialPages();
    var life =
        org.mockito.ArgumentCaptor.forClass(
            com.loakraken.market.application.LifeMaterialScan.class);
    verify(lifeMaterials).saveCategoryScan(eq(90000), life.capture(), eq(Duration.ofMinutes(30)));
    assertThat(life.getValue().items()).hasSize(34);
    assertThat(life.getValue().completeCategories()).containsExactly(90000);
    var battle =
        org.mockito.ArgumentCaptor.forClass(
            com.loakraken.market.application.LifeMaterialScan.class);
    verify(lifeMaterials).saveCategoryScan(eq(60000), battle.capture(), eq(Duration.ofMinutes(30)));
    assertThat(battle.getValue().items()).hasSize(15);
    assertThat(battle.getValue().completeCategories()).containsExactly(60000);
    verify(lifeMaterials, never()).fetchCategoryPage(90000, 5);
    verify(lifeMaterials, never()).fetchCategoryPage(60000, 3);
    // 사용자 몫(reserve)을 남기는 쪽으로만 부른다.
    verify(gate, atLeast(6)).withReserve(eq(MarketHistoryWarmup.RESERVE), any());
    org.mockito.Mockito.clearInvocations(lifeMaterials);
    clock.now = clock.now.plus(Duration.ofMinutes(5));
    warmup.warmLifeMaterialPages();
    verifyNoInteractions(lifeMaterials);
  }

  @Test
  void 각인서_예열은_플래그가_꺼져_있으면_부르지_않는다() {
    warmup("key", List.of()).warmRelicBookPages();
    verifyNoInteractions(relicBooks);
  }

  @Test
  void 각인서_예열은_전체_페이지를_모아_주기의_3배_TTL로_저장하고_주기_전에는_다시_부르지_않는다() {
    relicProperties = new RelicBookWarmupProperties(true, Duration.ofMinutes(30), 10);
    for (int page = 1; page <= 3; page++)
      when(relicBooks.fetchPage(page)).thenReturn(page(page, 23));
    var warmup = warmup("key", List.of());
    warmup.warmRelicBookPages();
    verify(relicBooks, times(3)).fetchPage(anyInt());
    var scan = org.mockito.ArgumentCaptor.forClass(RelicBookScan.class);
    verify(relicBooks).save(scan.capture(), eq(Duration.ofMinutes(90)));
    assertThat(scan.getValue().items()).hasSize(23);
    assertThat(scan.getValue().totalItems()).isEqualTo(23);
    assertThat(count("refreshed")).isEqualTo(1);
    // 주기(30분) 전에는 부르지 않는다.
    clock.now = clock.now.plus(Duration.ofMinutes(10));
    warmup.warmRelicBookPages();
    verify(relicBooks, times(3)).fetchPage(anyInt());
    clock.now = clock.now.plus(Duration.ofMinutes(21));
    warmup.warmRelicBookPages();
    verify(relicBooks, times(6)).fetchPage(anyInt());
  }

  @Test
  void 각인서_예열은_분당_상한에_걸리면_다음_틱에_남은_페이지를_이어_받는다() {
    relicProperties = new RelicBookWarmupProperties(true, Duration.ofMinutes(30), 10);
    for (int page = 1; page <= 3; page++)
      when(relicBooks.fetchPage(page)).thenReturn(page(page, 23));
    var warmup = warmup("key", List.of(), 2);
    warmup.warmRelicBookPages();
    verify(relicBooks, times(2)).fetchPage(anyInt());
    verify(relicBooks, never()).save(any(), any());
    assertThat(count("capped")).isEqualTo(1);
    clock.now = clock.now.plus(Duration.ofMinutes(1));
    warmup.warmRelicBookPages();
    verify(relicBooks).fetchPage(3);
    verify(relicBooks).save(any(), eq(Duration.ofMinutes(90)));
  }

  @Test
  void 재련_재료_예열은_플래그가_꺼져_있으면_부르지_않는다() {
    warmup("key", List.of()).warmRefiningMaterialPages();
    verifyNoInteractions(refiningMaterials);
  }

  @Test
  void 재련_재료_예열은_두_탭을_끝까지_받아_주기의_3배_TTL로_저장하고_분당_상한에_걸리면_다음_틱에_처음부터_받는다() {
    refiningMaterialProperties =
        new RefiningMaterialWarmupProperties(true, Duration.ofMinutes(10), 5);
    var refining = com.loakraken.market.domain.MarketTab.REFINING;
    var extra = com.loakraken.market.domain.MarketTab.REFINING_EXTRA;
    // 재련 재료 11종(2페이지), 추가 재료 22종(3페이지).
    when(refiningMaterials.fetchPage(eq(refining), anyInt()))
        .thenAnswer(invocation -> page(invocation.getArgument(1), 11));
    when(refiningMaterials.fetchPage(eq(extra), anyInt()))
        .thenAnswer(invocation -> page(invocation.getArgument(1), 22));
    // 분당 상한 4: 다섯 번째 페이지에서 멈추고 저장하지 않는다.
    var capped = warmup("key", List.of(), 4);
    capped.warmRefiningMaterialPages();
    verify(refiningMaterials, times(4)).fetchPage(any(), anyInt());
    verify(refiningMaterials, never()).save(any(), any());
    clock.now = clock.now.plus(Duration.ofMinutes(1));
    capped.warmRefiningMaterialPages();
    // 처음부터 다시: 재련 재료 2 + 추가 재료 3 = 5회 중 4회까지 → 아직 저장 없음.
    verify(refiningMaterials, never()).save(any(), any());

    var warmup = warmup("key", List.of());
    warmup.warmRefiningMaterialPages();
    var scan =
        org.mockito.ArgumentCaptor.forClass(
            com.loakraken.market.application.RefiningMaterialScan.class);
    verify(refiningMaterials).save(scan.capture(), eq(Duration.ofMinutes(30)));
    assertThat(scan.getValue().items()).hasSize(33);
    verify(refiningMaterials, never()).fetchPage(refining, 3);
    verify(refiningMaterials, never()).fetchPage(extra, 4);
    // 주기(10분) 전에는 다시 부르지 않는다.
    org.mockito.Mockito.clearInvocations(refiningMaterials);
    clock.now = clock.now.plus(Duration.ofMinutes(5));
    warmup.warmRefiningMaterialPages();
    verifyNoInteractions(refiningMaterials);
  }

  private MarketResult page(int page, int total) {
    var items = new java.util.ArrayList<MarketPrice>();
    for (int i = (page - 1) * 10; i < Math.min(page * 10, total); i++)
      items.add(
          new MarketPrice(
              1000 + i,
              "유물 각인" + i + " 각인서",
              "유물",
              null,
              1,
              null,
              null,
              null,
              java.math.BigDecimal.valueOf(100 + i)));
    return new MarketResult(
        MarketSource.ACTIVE_LISTING,
        clock.now,
        RelicBookPriceService.search(page),
        new com.loakraken.market.application.MarketPage(page, 10, total, items));
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
