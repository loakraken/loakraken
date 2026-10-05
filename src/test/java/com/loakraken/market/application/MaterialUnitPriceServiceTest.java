package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MaterialPriceBasis;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialUnitPriceServiceTest {
  private final MarketQueryService markets = mock(MarketQueryService.class);
  private final MaterialUnitPriceService service = new MaterialUnitPriceService(markets);
  private final Instant observed = Instant.parse("2026-10-05T00:00:00Z");

  @Test
  void 이름이_정확히_같은_상품만_쓰고_묶음_가격을_1개당으로_나눈다() {
    stub("운명의 수호석", price(1, "운명의 수호석 결정", 100, "176"), price(2, "운명의 수호석", 100, "31"));
    var result = service.prices(MaterialPriceBasis.RECENT_PRICE, List.of("운명의 수호석"));
    var item = result.getFirst();
    assertThat(item.status()).isEqualTo(MaterialUnitPriceService.UnitPrice.Status.PRICED);
    assertThat(item.sourceItemName()).isEqualTo("운명의 수호석");
    assertThat(item.unitPriceGold()).isEqualByComparingTo("0.31");
    assertThat(item.bundleCount()).isEqualTo(100);
    assertThat(item.bundlePrice()).isEqualByComparingTo("31");
    assertThat(item.observedAt()).isEqualTo(observed);
    verify(markets)
        .search(
            eq(MarketSource.ACTIVE_LISTING),
            argThat(
                search -> search.itemName().equals("운명의 수호석") && search.categoryCode() == 50000));
  }

  @Test
  void 운명의_파편은_가장_싼_주머니의_파편_1개당_가격이다() {
    stub(
        "운명의 파편 주머니",
        price(11, "운명의 파편 주머니(소)", 1, "85"),
        price(12, "운명의 파편 주머니(중)", 1, "194"),
        price(13, "운명의 파편 주머니(대)", 1, "232"));
    var item = service.prices(MaterialPriceBasis.RECENT_PRICE, List.of("운명의 파편")).getFirst();
    assertThat(item.status()).isEqualTo(MaterialUnitPriceService.UnitPrice.Status.PRICED);
    assertThat(item.sourceItemName()).isEqualTo("운명의 파편 주머니(대)");
    assertThat(item.contentsPerItem()).isEqualTo(3000);
    assertThat(item.unitPriceGold()).isEqualByComparingTo("0.077333");
  }

  @Test
  void 같은_이름이_없거나_가격이_없으면_상태로_구분하고_중복_이름은_한번만_조회한다() {
    stub("없는 재료", price(1, "다른 재료", 1, "10"));
    stub("빙하의 숨결", price(2, "빙하의 숨결", 1, null));
    var result =
        service.prices(MaterialPriceBasis.RECENT_PRICE, List.of("없는 재료", "빙하의 숨결", "없는 재료"));
    assertThat(result).hasSize(2);
    assertThat(result.get(0).status())
        .isEqualTo(MaterialUnitPriceService.UnitPrice.Status.NOT_FOUND);
    assertThat(result.get(0).unitPriceGold()).isNull();
    assertThat(result.get(1).status())
        .isEqualTo(MaterialUnitPriceService.UnitPrice.Status.PRICE_UNAVAILABLE);
    assertThat(result.get(1).sourceItemName()).isEqualTo("빙하의 숨결");
    verify(markets, times(2)).search(any(), any());
  }

  private void stub(String itemName, MarketPrice... items) {
    when(markets.search(
            eq(MarketSource.ACTIVE_LISTING),
            argThat(search -> search != null && search.itemName().equals(itemName))))
        .thenAnswer(
            invocation ->
                new MarketResult(
                    MarketSource.ACTIVE_LISTING,
                    observed,
                    invocation.getArgument(1),
                    new MarketPage(1, 10, items.length, List.of(items))));
  }

  private MarketPrice price(long id, String name, int bundle, String recent) {
    return new MarketPrice(
        id,
        name,
        "일반",
        null,
        bundle,
        null,
        null,
        recent == null ? null : new BigDecimal(recent),
        null);
  }
}
