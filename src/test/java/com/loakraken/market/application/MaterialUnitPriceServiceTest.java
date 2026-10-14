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
  private final MarketCache cache = mock(MarketCache.class);
  private final com.loakraken.common.lostark.LostArkRequestGate gate =
      mock(com.loakraken.common.lostark.LostArkRequestGate.class);
  private final MaterialUnitPriceService service =
      new MaterialUnitPriceService(
          markets,
          cache,
          gate,
          java.time.Clock.fixed(Instant.parse("2026-10-05T00:01:00Z"), java.time.ZoneOffset.UTC));

  {
    when(cache.findRefiningMaterials()).thenReturn(java.util.Optional.empty());
    when(cache.findLifeMaterials()).thenReturn(java.util.Optional.empty());
    when(gate.withReserve(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
  }

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
    // 없는 재료는 강화 재료·생활 두 분류를 보고(2번), 빙하의 숨결은 강화 재료에서 찾는다(1번). 중복 이름은 다시 보지 않는다.
    verify(markets, times(3)).search(any(), any());
    verify(markets)
        .search(
            any(),
            argThat(search -> search.itemName().equals("없는 재료") && search.categoryCode() == 90000));
  }

  @Test
  void 예열_스캔에_있는_이름은_검색하지_않고_한도도_쓰지_않는다() {
    when(cache.findLifeMaterials())
        .thenReturn(
            java.util.Optional.of(
                new LifeMaterialScan(observed, List.of(price(21, "목재", 100, "285")))));
    when(cache.findRefiningMaterials())
        .thenReturn(
            java.util.Optional.of(
                new RefiningMaterialScan(
                    observed,
                    List.of(
                        price(22, "아비도스 융화 재료", 1, "88"), price(23, "운명의 파편 주머니(대)", 1, "232")))));
    var result =
        service.requestPrices(
            MaterialPriceBasis.RECENT_PRICE, List.of("목재", "아비도스 융화 재료", "운명의 파편"));
    assertThat(result)
        .extracting(MaterialUnitPriceService.UnitPrice::status)
        .containsOnly(MaterialUnitPriceService.UnitPrice.Status.PRICED);
    assertThat(result.get(0).unitPriceGold()).isEqualByComparingTo("2.85");
    assertThat(result.get(0).observedAt()).isEqualTo(observed);
    assertThat(result.get(2).contentsPerItem()).isEqualTo(3000);
    verifyNoInteractions(markets);
    verify(gate, never()).chargeClient(any());
  }

  @Test
  void 캐시에_없으면_요청을_한_번만_세고_사용자_몫을_남기며_요청당_검색_상한까지만_본다() {
    // 어디에도 없는 이름 25개 = 이름마다 분류 2번 = 50번이 필요하지만 상한 45번에서 멈춘다.
    when(markets.search(any(), any()))
        .thenAnswer(
            invocation ->
                new MarketResult(
                    MarketSource.ACTIVE_LISTING,
                    observed,
                    invocation.getArgument(1),
                    new MarketPage(1, 10, 0, List.of())));
    var names = java.util.stream.IntStream.range(0, 25).mapToObj(i -> "없는 재료" + i).toList();
    var result = service.requestPrices(MaterialPriceBasis.RECENT_PRICE, names);
    verify(gate, times(1))
        .chargeClient(com.loakraken.common.lostark.ClientLimiter.Bucket.MATERIAL_PRICES);
    verify(gate).withReserve(eq(MaterialUnitPriceService.REQUEST_RESERVE), any());
    verify(markets, times(MaterialUnitPriceService.MAX_REQUEST_SEARCHES)).search(any(), any());
    assertThat(result).hasSize(25);
    // 22개는 두 분류를 다 봐서 NOT_FOUND, 23번째는 한 분류만 보고 상한, 그 뒤는 보지 못함.
    assertThat(result.subList(0, 22))
        .extracting(MaterialUnitPriceService.UnitPrice::status)
        .containsOnly(MaterialUnitPriceService.UnitPrice.Status.NOT_FOUND);
    assertThat(result.subList(22, 25))
        .extracting(MaterialUnitPriceService.UnitPrice::status)
        .containsOnly(MaterialUnitPriceService.UnitPrice.Status.PRICE_UNAVAILABLE);
  }

  @Test
  void 공유_예산에_걸리면_그_뒤_이름은_알_수_없음이고_캐시에서_답한_이름은_그대로다() {
    when(cache.findLifeMaterials())
        .thenReturn(
            java.util.Optional.of(
                new LifeMaterialScan(observed, List.of(price(21, "목재", 100, "285")))));
    when(markets.search(any(), any()))
        .thenThrow(
            new com.loakraken.common.api.BusinessException(
                com.loakraken.common.lostark.LostArkErrorCode.LOST_ARK_RATE_LIMITED));
    var result =
        service.requestPrices(MaterialPriceBasis.RECENT_PRICE, List.of("목재", "모르는 재료", "다른 재료"));
    assertThat(result.get(0).status()).isEqualTo(MaterialUnitPriceService.UnitPrice.Status.PRICED);
    assertThat(result.subList(1, 3))
        .extracting(MaterialUnitPriceService.UnitPrice::status)
        .containsOnly(MaterialUnitPriceService.UnitPrice.Status.PRICE_UNAVAILABLE);
    verify(markets, times(1)).search(any(), any());
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
