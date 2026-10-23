package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loakraken.MarketTestFixture;
import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.market.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MaterialQuoteServiceTest {
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final Instant now = Instant.parse("2026-10-04T00:00:00Z");
  private final Instant observed = now.minusSeconds(20);
  private final MaterialQuoteService service =
      new MaterialQuoteService(markets, Clock.fixed(now, ZoneOffset.UTC));
  private final MarketSearch search = MarketTestFixture.search();

  @ParameterizedTest
  @EnumSource(MaterialPriceBasis.class)
  void 가격_기준을_혼합하지_않고_묶음_올림과_관측_시각을_보존한다(MaterialPriceBasis basis) {
    when(markets.search(MarketSource.ACTIVE_LISTING, search))
        .thenReturn(page(List.of(price(123, null, new BigDecimal("30")))));
    var quote = service.quote(command(basis, item(123, 250, 40)));
    var expected =
        switch (basis) {
          case CURRENT_MIN_PRICE -> "90";
          case RECENT_PRICE -> "60";
          case YDAY_AVG_PRICE -> "30.375";
        };
    assertThat(quote.totalCostGold()).isEqualByComparingTo(expected);
    assertThat(quote.knownCostGold()).isEqualByComparingTo(expected);
    assertThat(quote.complete()).isTrue();
    assertThat(quote.calculatedAt()).isEqualTo(now);
    var line = quote.items().getFirst();
    assertThat(line.observedAt()).isEqualTo(observed);
    assertThat(line.purchase().purchaseQuantity()).isEqualTo(300);
    assertThat(line.purchase().surplusQuantity()).isEqualTo(90);
  }

  @Test
  void 같은_페이지를_한번만_읽고_입력_순서대로_합산한다() {
    when(markets.search(any(), any()))
        .thenReturn(
            page(List.of(price(123, null, BigDecimal.TEN), price(124, null, BigDecimal.ONE))));
    var result =
        service.quote(
            command(MaterialPriceBasis.CURRENT_MIN_PRICE, item(124, 101, 0), item(123, 1, 0)));
    assertThat(result.items())
        .extracting(line -> line.requirement().itemId())
        .containsExactly(124L, 123L);
    assertThat(result.totalCostGold()).isEqualByComparingTo("12");
    verify(markets, times(1)).search(MarketSource.ACTIVE_LISTING, search);
  }

  @Test
  void 보유량으로_충당되면_가격과_상품을_조회하지_않는다() {
    var result =
        service.quote(
            command(MaterialPriceBasis.CURRENT_MIN_PRICE, item(123, 250, 300), item(124, 0, 0)));
    assertThat(result.complete()).isTrue();
    assertThat(result.totalCostGold()).isZero();
    assertThat(result.items())
        .allSatisfy(
            line -> {
              assertThat(line.status()).isEqualTo(MaterialQuote.Status.COVERED_BY_INVENTORY);
              assertThat(line.observedAt()).isNull();
              assertThat(line.pricePerBundle()).isNull();
              assertThat(line.requirement().missingQuantity()).isZero();
            });
    verifyNoInteractions(markets);
  }

  @ParameterizedTest
  @EnumSource(MaterialPriceBasis.class)
  void 거래_횟수_제한_상품도_계산하고_쓴_줄의_거래_가능_횟수를_싣는다(MaterialPriceBasis basis) {
    // 공식 거래소 검색 원문(2026-10-18) 값: 각인서는 TradeRemainCount 0, 강화 재료는 null.
    when(markets.search(any(), any()))
        .thenReturn(
            page(
                List.of(
                    row(65200505, "유물 원한 각인서", 1, 0, "112570.3", "111200", "111000"),
                    row(66102101, "수호석 조각", 100, null, "1", "1", "1"))));
    var result = service.quote(command(basis, item(65200505, 5, 0), item(66102101, 250, 0)));
    var expected =
        switch (basis) {
          case CURRENT_MIN_PRICE -> "555003";
          case RECENT_PRICE -> "556003";
          case YDAY_AVG_PRICE -> "562854.5";
        };
    assertThat(result.complete()).isTrue();
    assertThat(result.totalCostGold()).isEqualByComparingTo(expected);
    assertThat(result.items())
        .extracting(MaterialQuote.Line::status, MaterialQuote.Line::tradeRemainCount)
        .containsExactly(
            tuple(MaterialQuote.Status.PRICED, 0), tuple(MaterialQuote.Status.PRICED, null));
  }

  @Test
  void 골드가_같으면_공식_줄_순서와_관계없이_남는_수량이_적은_줄을_쓴다() {
    // 묶음 10개 10골드와 1개 10골드: 1개를 살 때 둘 다 10골드 — 남는 수량 0인 1개 묶음.
    var bundleTen = row(206, "묶음 동률", 10, null, "10", "10", "10");
    var bundleOne = row(206, "묶음 동률", 1, null, "10", "10", "10");
    for (var rows : List.of(List.of(bundleTen, bundleOne), List.of(bundleOne, bundleTen))) {
      when(markets.search(any(), any())).thenReturn(page(rows));
      var line =
          service
              .quote(command(MaterialPriceBasis.CURRENT_MIN_PRICE, item(206, 1, 0)))
              .items()
              .getFirst();
      assertThat(line.bundleCount()).isEqualTo(1);
      assertThat(line.purchase().purchaseQuantity()).isEqualTo(1);
      assertThat(line.purchase().surplusQuantity()).isZero();
    }
    // 골드·남는 수량이 같으면 작은 묶음: 4개를 2개 묶음 5골드 × 2 또는 4개 묶음 10골드 × 1 → 둘 다 10골드·남음 0.
    var two = row(207, "묶음 동률", 2, null, "5", "5", "5");
    var four = row(207, "묶음 동률", 4, null, "10", "10", "10");
    for (var rows : List.of(List.of(four, two), List.of(two, four))) {
      when(markets.search(any(), any())).thenReturn(page(rows));
      var line =
          service
              .quote(command(MaterialPriceBasis.CURRENT_MIN_PRICE, item(207, 4, 0)))
              .items()
              .getFirst();
      assertThat(line.bundleCount()).isEqualTo(2);
      assertThat(line.purchase().costGold()).isEqualByComparingTo("10");
    }
  }

  @Test
  void 같은_상품_여러_줄이면_선택한_가격_기준으로_가장_싸게_사는_줄을_쓴다() {
    when(markets.search(any(), any()))
        .thenReturn(
            page(
                List.of(
                    // 거래 가능 횟수가 남은 변형이 더 비싸면 싼 쪽(0회)을 쓴다.
                    row(201, "변형", 1, 1, "120000", "120000", "120000"),
                    row(201, "변형", 1, 0, "112000", "111500", "111000"),
                    // 값이 같으면 제한 없는 줄, 그다음 거래 가능 횟수가 많은 줄.
                    row(202, "같은 값", 1, 0, "5", "5", "5"),
                    row(202, "같은 값", 1, null, "5", "5", "5"),
                    row(203, "같은 값", 1, 0, "5", "5", "5"),
                    row(203, "같은 값", 1, 2, "5", "5", "5"),
                    // 묶음이 달라도 부족 수량을 살 때 드는 골드로 비교한다.
                    row(204, "묶음", 10, null, "95", "95", "95"),
                    row(204, "묶음", 1, null, "10", "10", "10"),
                    // 가격 없는 줄은 고르지 않는다.
                    row(205, "일부 미제공", 1, null, null, null, null),
                    row(205, "일부 미제공", 1, 0, "7", "7", "7"))));
    var result =
        service.quote(
            command(
                MaterialPriceBasis.CURRENT_MIN_PRICE,
                item(201, 1, 0),
                item(202, 1, 0),
                item(203, 1, 0),
                item(204, 1, 0),
                item(205, 1, 0)));
    assertThat(result.items())
        .extracting(
            MaterialQuote.Line::status,
            MaterialQuote.Line::tradeRemainCount,
            MaterialQuote.Line::bundleCount,
            line -> line.purchase().costGold().stripTrailingZeros().toPlainString())
        .containsExactly(
            tuple(MaterialQuote.Status.PRICED, 0, 1, "111000"),
            tuple(MaterialQuote.Status.PRICED, null, 1, "5"),
            tuple(MaterialQuote.Status.PRICED, 2, 1, "5"),
            tuple(MaterialQuote.Status.PRICED, null, 1, "10"),
            tuple(MaterialQuote.Status.PRICED, 0, 1, "7"));
    assertThat(result.totalCostGold()).isEqualByComparingTo("111027");
  }

  @Test
  void 누락_미제공_0원은_각각_미계산하고_총액을_숨긴다() {
    when(markets.search(any(), any()))
        .thenReturn(
            page(
                List.of(
                    price(123, null, BigDecimal.TEN),
                    price(126, null, null),
                    price(127, null, BigDecimal.ZERO),
                    price(129, null, null),
                    price(129, 0, BigDecimal.ZERO))));
    var result =
        service.quote(
            command(
                MaterialPriceBasis.CURRENT_MIN_PRICE,
                item(123, 1, 0),
                item(126, 1, 0),
                item(127, 1, 0),
                item(128, 1, 0),
                item(129, 1, 0)));
    assertThat(result.complete()).isFalse();
    assertThat(result.totalCostGold()).isNull();
    assertThat(result.knownCostGold()).isEqualByComparingTo("10");
    assertThat(result.items())
        .extracting(MaterialQuote.Line::status)
        .containsExactly(
            MaterialQuote.Status.PRICED,
            MaterialQuote.Status.PRICE_UNAVAILABLE,
            MaterialQuote.Status.ZERO_PRICE,
            MaterialQuote.Status.ITEM_NOT_IN_PAGE,
            MaterialQuote.Status.ZERO_PRICE);
    assertThat(result.items().get(1).pricePerBundle()).isNull();
    assertThat(result.items().get(2).pricePerBundle()).isZero();
    assertThat(result.items().get(4).tradeRemainCount()).isZero();
    assertThat(result.items().subList(1, 5))
        .allSatisfy(line -> assertThat(line.purchase()).isNull());
    // 거래 제한·같은 ID 여러 줄 상태는 값 목록에 남지만 더는 내지 않는다(#617).
    assertThat(result.items())
        .extracting(MaterialQuote.Line::status)
        .doesNotContain(
            MaterialQuote.Status.TRADE_RESTRICTED_ITEM, MaterialQuote.Status.AMBIGUOUS_ITEM);
  }

  @Test
  void 외부_실패는_불완전한_200으로_바꾸지_않고_후속_페이지도_조회하지_않는다() {
    var failure = new BusinessException(LostArkErrorCode.LOST_ARK_RATE_LIMITED);
    when(markets.search(any(), any())).thenThrow(failure);
    var other =
        new MarketSearch(
            50010, "", null, "", "", 2, MarketSearch.Sort.GRADE, MarketSearch.SortCondition.ASC);
    assertThatThrownBy(
            () ->
                service.quote(
                    command(
                        MaterialPriceBasis.CURRENT_MIN_PRICE,
                        item(123, 1, 0),
                        new MaterialQuoteCommand.Item(124, 1, 0, other))))
        .isSameAs(failure);
    verify(markets).search(MarketSource.ACTIVE_LISTING, search);
    verifyNoMoreInteractions(markets);
  }

  @Test
  void 중복_항목은_같은_보유량을_반복_차감하지_않도록_거절한다() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> command(MaterialPriceBasis.CURRENT_MIN_PRICE, item(123, 1, 0), item(123, 2, 1)));
  }

  @Test
  void 캐시_오류는_견적_누락이나_무료_결과로_숨기지_않는다() {
    var failure = new BusinessException(MarketErrorCode.MARKET_CACHE_UNAVAILABLE);
    when(markets.search(any(), any())).thenThrow(failure);
    assertThatThrownBy(
            () -> service.quote(command(MaterialPriceBasis.CURRENT_MIN_PRICE, item(123, 1, 0))))
        .isSameAs(failure);
  }

  private MaterialQuoteCommand command(
      MaterialPriceBasis basis, MaterialQuoteCommand.Item... items) {
    return new MaterialQuoteCommand(basis, List.of(items));
  }

  private MaterialQuoteCommand.Item item(long id, long required, long owned) {
    return new MaterialQuoteCommand.Item(id, required, owned, search);
  }

  private MarketResult page(List<MarketPrice> prices) {
    return new MarketResult(
        MarketSource.ACTIVE_LISTING,
        observed,
        search,
        new MarketPage(1, 10, prices.size(), prices));
  }

  private MarketPrice row(
      long id,
      String name,
      int bundle,
      Integer tradeRemaining,
      String yDayAvg,
      String recent,
      String current) {
    return new MarketPrice(
        id,
        name,
        null,
        null,
        bundle,
        tradeRemaining,
        yDayAvg == null ? null : new BigDecimal(yDayAvg),
        recent == null ? null : new BigDecimal(recent),
        current == null ? null : new BigDecimal(current));
  }

  private MarketPrice price(long id, Integer tradeRemaining, BigDecimal current) {
    return new MarketPrice(
        id,
        "검증 재료",
        null,
        null,
        100,
        tradeRemaining,
        new BigDecimal("10.125"),
        new BigDecimal("20"),
        current);
  }
}
