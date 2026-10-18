package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarketBoardServiceTest {
  private static final Instant OBSERVED = Instant.parse("2026-10-08T03:00:00Z");
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final MarketCache cache = mock(MarketCache.class);
  private final MarketBoardService service = new MarketBoardService(markets, cache);

  @BeforeEach
  void noCache() {
    when(cache.findBoardScan(any(), any())).thenReturn(Optional.empty());
    when(cache.findItemHistories(any())).thenReturn(Map.of());
  }

  @Test
  void 기본_고가_저가는_공식_정렬_한_페이지를_그대로_쓴다() {
    stubPages(MarketTab.REFINING, 4, 11);
    var board = service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.HIGH, 2);
    var captor = ArgumentCaptor.forClass(MarketSearch.class);
    verify(markets).search(eq(MarketSource.ACTIVE_LISTING), captor.capture());
    var search = captor.getValue();
    assertThat(search.categoryCode()).isEqualTo(50010);
    assertThat(search.itemTier()).isEqualTo(4);
    assertThat(search.pageNo()).isEqualTo(2);
    assertThat(search.sort()).isEqualTo(MarketSearch.Sort.CURRENT_MIN_PRICE);
    assertThat(search.sortCondition()).isEqualTo(MarketSearch.SortCondition.DESC);
    assertThat(board.totalItems()).isEqualTo(11);
    assertThat(board.scannedItems()).isEqualTo(1);
    assertThat(board.complete()).isTrue();
    verify(cache, never()).findBoardScan(any(), any());

    stubPages(MarketTab.ARK_GRID, null, 5);
    service.board(MarketTab.ARK_GRID, null, MarketBoardRules.Sort.LOW, 1);
    service.board(MarketTab.ARK_GRID, 3, MarketBoardRules.Sort.DEFAULT, 1);
    var all = ArgumentCaptor.forClass(MarketSearch.class);
    verify(markets, times(3)).search(any(), all.capture());
    assertThat(all.getAllValues().get(1).sortCondition()).isEqualTo(MarketSearch.SortCondition.ASC);
    assertThat(all.getAllValues().get(1).itemTier()).isNull();
    assertThat(all.getAllValues().get(2).sort()).isEqualTo(MarketSearch.Sort.GRADE);
    assertThat(all.getAllValues().get(2).itemTier()).isEqualTo(3);
  }

  @Test
  void 기본_보드_예열은_사용자_기본_보드와_같은_검색_키를_강제_갱신한다() {
    stubPages(MarketTab.REFINING, 4, 11);
    service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.DEFAULT, 1);
    var userKey = ArgumentCaptor.forClass(MarketSearch.class);
    verify(markets).search(eq(MarketSource.ACTIVE_LISTING), userKey.capture());
    service.refreshDefaultPage(MarketTab.REFINING);
    var warmKey = ArgumentCaptor.forClass(MarketSearch.class);
    verify(markets).refreshSearch(eq(MarketSource.ACTIVE_LISTING), warmKey.capture());
    assertThat(warmKey.getValue()).isEqualTo(userKey.getValue());
    assertThat(warmKey.getValue().itemTier()).isEqualTo(4);
  }

  @Test
  void 상승_정렬은_탭_전체를_순차_스캔해_등락률로_정렬하고_스캔을_저장한다() {
    stubPages(MarketTab.ENGRAVING_BOOK, null, 23);
    var board = service.board(MarketTab.ENGRAVING_BOOK, null, MarketBoardRules.Sort.RISE, 1);
    verify(markets, times(3)).search(eq(MarketSource.ACTIVE_LISTING), any());
    assertThat(board.totalItems()).isEqualTo(23);
    assertThat(board.scannedItems()).isEqualTo(23);
    assertThat(board.complete()).isTrue();
    assertThat(board.rows()).hasSize(10);
    // 품목 i의 등락률은 i%라 가장 큰 23부터 내려간다.
    assertThat(board.rows()).extracting(row -> row.price().id()).startsWith(23L, 22L, 21L);
    assertThat(board.rows().getFirst().changeDay()).isEqualByComparingTo("23.0");
    var saved = ArgumentCaptor.forClass(MarketBoardScan.class);
    verify(cache).saveBoardScan(saved.capture());
    assertThat(saved.getValue().items()).hasSize(23);

    var last = service.board(MarketTab.ENGRAVING_BOOK, null, MarketBoardRules.Sort.FALL, 3);
    assertThat(last.rows()).extracting(row -> row.price().id()).containsExactly(21L, 22L, 23L);
  }

  @Test
  void 캐시된_스캔은_외부_조회_없이_쓰고_페이지_상한을_넘으면_미완료로_표시한다() {
    var items = IntStream.rangeClosed(1, 5).mapToObj(MarketBoardServiceTest::item).toList();
    when(cache.findBoardScan(MarketTab.FOOD, null))
        .thenReturn(Optional.of(new MarketBoardScan(MarketTab.FOOD, null, OBSERVED, 150, items)));
    var board = service.board(MarketTab.FOOD, null, MarketBoardRules.Sort.FALL, 1);
    verifyNoInteractions(markets);
    assertThat(board.complete()).isFalse();
    assertThat(board.totalItems()).isEqualTo(150);
    assertThat(board.scannedItems()).isEqualTo(5);

    stubPages(MarketTab.LIFE, null, 150);
    var capped = service.board(MarketTab.LIFE, null, MarketBoardRules.Sort.RISE, 1);
    verify(markets, times(MarketBoardService.MAX_SCAN_PAGES)).search(any(), any());
    assertThat(capped.scannedItems()).isEqualTo(100);
    assertThat(capped.complete()).isFalse();
  }

  @Test
  void 이력_캐시가_있으면_주간_등락률과_7일_스파크를_오래된_날부터_채운다() {
    stubPages(MarketTab.REFINING, 4, 1);
    var today = LocalDate.ofInstant(OBSERVED, ZoneId.of("Asia/Seoul"));
    var days = new ArrayList<MarketItemHistory.DailyStat>();
    days.add(new MarketItemHistory.DailyStat(today, new BigDecimal("999"), 1)); // 부분 집계
    for (int i = 1; i <= 9; i++)
      days.add(new MarketItemHistory.DailyStat(today.minusDays(i), BigDecimal.valueOf(100), 10));
    var history =
        new MarketItemHistoryResult(
            OBSERVED,
            new MarketItemHistory(
                1L,
                List.of(
                    new MarketItemHistory.Variant("품목1", 3, 1, List.of()),
                    new MarketItemHistory.Variant("품목1", null, 1, days))));
    when(cache.findItemHistories(List.of(1L))).thenReturn(Map.of(1L, history));
    var row =
        service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.DEFAULT, 1).rows().getFirst();
    assertThat(row.historyAvailable()).isTrue();
    // 현재 최저가 101 ÷ 7일 가중 평균 100 − 1
    assertThat(row.changeWeek()).isEqualByComparingTo("1.0");
    assertThat(row.spark7()).hasSize(7);
    assertThat(row.spark7().getFirst().date()).isEqualTo(today.minusDays(7));
    assertThat(row.spark7().getLast().date()).isEqualTo(today.minusDays(1));
  }

  @Test
  void 이름_검색은_등락_정렬과_같은_스캔_캐시에서_이름_초성으로_거른_뒤_정렬_페이지를_적용한다() {
    var items =
        List.of(
            named(1, "운명의 파괴석", "30"),
            named(2, "운명의 수호석", "10"),
            named(3, "정제된 파괴강석", "50"),
            named(4, "Abydos Item", null),
            named(5, "아비도스 융화 재료", "70"));
    when(cache.findBoardScan(MarketTab.REFINING, 4))
        .thenReturn(Optional.of(new MarketBoardScan(MarketTab.REFINING, 4, OBSERVED, 150, items)));
    var board = service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.DEFAULT, 1, "파괴");
    // 캐시가 있으면 공식 호출 0, 기본 정렬은 스캔 순서.
    verifyNoInteractions(markets);
    assertThat(board.rows()).extracting(row -> row.price().id()).containsExactly(1L, 3L);
    assertThat(board.totalItems()).isEqualTo(2);
    assertThat(board.scannedItems()).isEqualTo(5);
    assertThat(board.complete()).isFalse();
    assertThat(board.observedAt()).isEqualTo(OBSERVED);
    assertThat(service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.HIGH, 1, "파괴").rows())
        .extracting(row -> row.price().id())
        .containsExactly(3L, 1L);
    assertThat(service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.LOW, 1, "ㅍㄱ").rows())
        .extracting(row -> row.price().id())
        .containsExactly(1L, 3L);
    // 초성은 앞이 아니어도 일치, 영문은 대소문자·공백 무시, 가격 없는 품목은 고가·저가에서 맨 뒤.
    assertThat(
            service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.DEFAULT, 1, "ㅁㅇㅍ").rows())
        .extracting(row -> row.price().id())
        .containsExactly(1L);
    assertThat(
            service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.LOW, 1, "abydosi").rows())
        .extracting(row -> row.price().id())
        .containsExactly(4L);
    assertThat(service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.HIGH, 1, "ㅇ").rows())
        .extracting(row -> row.price().id())
        .containsExactly(5L, 1L, 2L);
    // 없는 이름은 빈 페이지, 범위를 넘는 페이지도 빈 페이지.
    var none = service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.DEFAULT, 1, "없는품목");
    assertThat(none.rows()).isEmpty();
    assertThat(none.totalItems()).isZero();
    assertThat(
            service.board(MarketTab.REFINING, null, MarketBoardRules.Sort.DEFAULT, 3, "파괴").rows())
        .isEmpty();
    verifyNoInteractions(markets);
  }

  @Test
  void 이름_검색은_스캔이_없으면_등락_정렬과_같은_키로_한_번_스캔하고_저장한다() {
    stubPages(MarketTab.ENGRAVING_BOOK, null, 23);
    var board = service.board(MarketTab.ENGRAVING_BOOK, null, MarketBoardRules.Sort.RISE, 1, "품목2");
    verify(markets, times(3)).search(eq(MarketSource.ACTIVE_LISTING), any());
    // 품목2·품목20~23, 등락률 큰 순.
    assertThat(board.rows())
        .extracting(row -> row.price().id())
        .containsExactly(23L, 22L, 21L, 20L, 2L);
    assertThat(board.complete()).isTrue();
    var saved = ArgumentCaptor.forClass(MarketBoardScan.class);
    verify(cache).saveBoardScan(saved.capture());
    assertThat(saved.getValue().tab()).isEqualTo(MarketTab.ENGRAVING_BOOK);
    assertThat(saved.getValue().tier()).isNull();
    assertThat(saved.getValue().items()).hasSize(23);
  }

  private static MarketPrice named(long id, String name, String current) {
    return new MarketPrice(
        id,
        name,
        "일반",
        null,
        1,
        null,
        BigDecimal.valueOf(100),
        null,
        current == null ? null : new BigDecimal(current));
  }

  /** totalCount개 품목을 10개씩 페이지로 나눠 돌려준다. 품목 i는 전일 100, 현재 100+i. */
  private void stubPages(MarketTab tab, Integer tier, int totalCount) {
    when(markets.search(
            eq(MarketSource.ACTIVE_LISTING),
            argThat(s -> s != null && s.categoryCode() == tab.categoryCode())))
        .thenAnswer(
            invocation -> {
              MarketSearch search = invocation.getArgument(1);
              int from = (search.pageNo() - 1) * 10 + 1;
              int to = Math.min(totalCount, from + 9);
              List<MarketPrice> items =
                  from > totalCount
                      ? List.of()
                      : IntStream.rangeClosed(from, to)
                          .mapToObj(MarketBoardServiceTest::item)
                          .toList();
              return new MarketResult(
                  MarketSource.ACTIVE_LISTING,
                  OBSERVED,
                  search,
                  new MarketPage(search.pageNo(), 10, totalCount, items));
            });
  }

  private static MarketPrice item(int i) {
    return new MarketPrice(
        i,
        "품목" + i,
        "일반",
        null,
        1,
        null,
        BigDecimal.valueOf(100),
        null,
        BigDecimal.valueOf(100 + i));
  }
}
