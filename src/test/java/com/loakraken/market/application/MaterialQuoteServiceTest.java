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

  @Test
  void 누락_중복_재거래_미제공_0원은_각각_미계산하고_총액을_숨긴다() {
    when(markets.search(any(), any()))
        .thenReturn(
            page(
                List.of(
                    price(123, null, BigDecimal.TEN),
                    price(124, null, BigDecimal.ONE),
                    price(124, null, BigDecimal.TEN),
                    price(125, 0, BigDecimal.TEN),
                    price(126, null, null),
                    price(127, null, BigDecimal.ZERO))));
    var result =
        service.quote(
            command(
                MaterialPriceBasis.CURRENT_MIN_PRICE,
                item(123, 1, 0),
                item(124, 1, 0),
                item(125, 1, 0),
                item(126, 1, 0),
                item(127, 1, 0),
                item(128, 1, 0)));
    assertThat(result.complete()).isFalse();
    assertThat(result.totalCostGold()).isNull();
    assertThat(result.knownCostGold()).isEqualByComparingTo("10");
    assertThat(result.items())
        .extracting(MaterialQuote.Line::status)
        .containsExactly(
            MaterialQuote.Status.PRICED,
            MaterialQuote.Status.AMBIGUOUS_ITEM,
            MaterialQuote.Status.TRADE_RESTRICTED_ITEM,
            MaterialQuote.Status.PRICE_UNAVAILABLE,
            MaterialQuote.Status.ZERO_PRICE,
            MaterialQuote.Status.ITEM_NOT_IN_PAGE);
    assertThat(result.items().get(3).pricePerBundle()).isNull();
    assertThat(result.items().get(4).pricePerBundle()).isZero();
    assertThat(result.items().subList(1, 6))
        .allSatisfy(line -> assertThat(line.purchase()).isNull());
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
