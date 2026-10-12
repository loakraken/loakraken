package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.market.domain.MarketPrice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** T4 재련 재료 시세(L30 후속). 품목 이름·묶음 수는 공식 거래소 재련 재료·추가 재료 탭(2026-10-12 조회)과 같다. */
class RefiningMaterialPriceServiceTest {
  private final MarketCache cache = mock(MarketCache.class);
  private final RefiningMaterialPriceService service =
      new RefiningMaterialPriceService(mock(MarketQueryService.class), cache);
  private final Instant at = Instant.parse("2026-10-12T04:00:00Z");

  @Test
  void 묶음은_1개_값으로_나누고_파편은_주머니_가운데_가장_싼_1개_값이다() {
    when(cache.findRefiningMaterials())
        .thenReturn(
            Optional.of(
                new RefiningMaterialScan(
                    at,
                    List.of(
                        price(1, "운명의 파괴석", 100, "152"),
                        price(2, "운명의 돌파석", 1, "4"),
                        price(3, "운명의 파편 주머니(소)", 1, "87"),
                        price(4, "운명의 파편 주머니(중)", 1, "216"),
                        price(5, "운명의 파편 주머니(대)", 1, "219"),
                        price(6, "용암의 숨결", 1, "459"),
                        price(7, "빙하의 숨결", 1, null)))));
    var prices = service.cached().orElseThrow();
    assertThat(prices.observedAt()).isEqualTo(at);
    assertThat(prices.unit("운명의 파괴석"))
        .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.52"));
    assertThat(prices.unit("운명의 돌파석"))
        .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("4"));
    // (소) 0.087, (중) 0.108, (대) 0.073 → 0.073
    assertThat(prices.unit(MaterialUnitPriceService.SHARD))
        .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.073"));
    assertThat(prices.unit("운명의 파편 주머니(대)")).isEmpty();
    // 즉시 구매가가 없는 품목은 값이 없다(PENDING 근거).
    assertThat(prices.unit("빙하의 숨결")).isEmpty();
  }

  @Test
  void 캐시가_없거나_읽지_못하면_빈_값이다() {
    when(cache.findRefiningMaterials()).thenReturn(Optional.empty());
    assertThat(service.cached()).isEmpty();
    when(cache.findRefiningMaterials()).thenThrow(new IllegalStateException("down"));
    assertThat(service.cached()).isEmpty();
  }

  @Test
  void 예열_검색은_T4_탭을_등급순으로_받는다() {
    var search =
        RefiningMaterialPriceService.search(com.loakraken.market.domain.MarketTab.REFINING, 2);
    assertThat(search.categoryCode()).isEqualTo(50010);
    assertThat(search.itemTier()).isEqualTo(4);
    assertThat(search.pageNo()).isEqualTo(2);
  }

  private static MarketPrice price(long id, String name, int bundle, String currentMin) {
    return new MarketPrice(
        id,
        name,
        "영웅",
        null,
        bundle,
        null,
        null,
        null,
        currentMin == null ? null : new BigDecimal(currentMin));
  }
}
