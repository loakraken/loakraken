package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loakraken.market.domain.ArkGridGemCatalog;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * M49 G5: 아크 그리드 젬 시세(PM 총괄 11-22, 공식 230000 분류 전수 조회 18개). 캐시만 읽는다 — 젬 순위 스냅숏(15분)이 먼저, 없으면 보드
 * 캐시(3분)의 아크 그리드 탭 스캔.
 */
class ArkGridGemPriceServiceTest {
  private static final Instant OBSERVED = Instant.parse("2026-11-22T07:20:00Z");

  private final MarketCache cache = mock(MarketCache.class);
  private final MarketBoardService boards = mock(MarketBoardService.class);
  private final ArkGridGemPriceService service = new ArkGridGemPriceService(cache);

  private static MarketPrice item(long id, String name, String grade, int bundle, Long min) {
    return new MarketPrice(
        id, name, grade, null, bundle, 0, null, null, min == null ? null : BigDecimal.valueOf(min));
  }

  private static MarketBoardScan scan(Instant observedAt, MarketPrice... items) {
    return new MarketBoardScan(MarketTab.ARK_GRID, null, observedAt, 18, List.of(items));
  }

  private void snapshot(MarketPrice... items) {
    when(cache.findArkGridGemSnapshot()).thenReturn(Optional.of(scan(OBSERVED, items)));
  }

  private ArkGridGemPriceService.Price only(String type, String grade) {
    var gem = ArkGridGemCatalog.load().find(type, grade).orElseThrow();
    return service.cachedPrices().stream()
        .filter(price -> price.gemId() == gem.id())
        .findFirst()
        .orElse(null);
  }

  @Test
  void 젬_목록은_공식_230000_분류의_6종_3등급_18개이고_가격은_넣지_않는다() {
    var gems = ArkGridGemCatalog.load().gems();
    assertThat(gems).hasSize(18);
    assertThat(gems.stream().map(ArkGridGemCatalog.Gem::id).distinct()).hasSize(18);
    assertThat(gems.stream().map(ArkGridGemCatalog.Gem::type).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("안정", "견고", "불변", "침식", "왜곡", "붕괴");
    assertThat(gems.stream().map(ArkGridGemCatalog.Gem::grade).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("고급", "희귀", "영웅");
    var erosionHero = ArkGridGemCatalog.load().find("침식", "영웅").orElseThrow();
    assertThat(erosionHero.id()).isEqualTo(67410303L);
    assertThat(erosionHero.name()).isEqualTo("혼돈의 젬 : 침식");
    assertThat(ArkGridGemCatalog.load().find("안정", "고급").orElseThrow().name())
        .isEqualTo("질서의 젬 : 안정");
    assertThat(ArkGridGemCatalog.load().source()).contains("230000").contains("2026-11-22");
    // 젬 가공 초기화권은 이 분류에 없다(사용자 입력).
    assertThat(gems).noneMatch(gem -> gem.name().contains("초기화권"));
  }

  @Test
  void 시세는_젬_순위_스냅숏에서_ID와_이름이_맞는_매물의_1개_최저가이고_스캔하지_않는다() {
    snapshot(
        item(67410303L, "혼돈의 젬 : 침식", "영웅", 1, 21_883L),
        item(67400003L, "질서의 젬 : 안정", "영웅", 1, 15_000L));
    var erosion = only("침식", "영웅");
    assertThat(erosion.unitPrice()).isEqualTo(21_883L);
    assertThat(erosion.observedAt()).isEqualTo(OBSERVED);
    assertThat(service.cachedPrices())
        .extracting(ArkGridGemPriceService.Price::gemId)
        .containsExactlyInAnyOrder(67410303L, 67400003L);
    verifyNoInteractions(boards);
  }

  @Test
  void 스냅숏이_없으면_보드_캐시의_아크_그리드_스캔에서_읽는다() {
    when(cache.findArkGridGemSnapshot()).thenReturn(Optional.empty());
    when(cache.findBoardScan(MarketTab.ARK_GRID, MarketTab.ARK_GRID.defaultTier()))
        .thenReturn(Optional.of(scan(OBSERVED, item(67410303L, "혼돈의 젬 : 침식", "영웅", 1, 9_000L))));
    assertThat(only("침식", "영웅").unitPrice()).isEqualTo(9_000L);
  }

  @Test
  void 묶음이면_1개_값으로_올림한다() {
    snapshot(item(67410303L, "혼돈의 젬 : 침식", "영웅", 3, 100L));
    assertThat(only("침식", "영웅").unitPrice()).isEqualTo(34L);
  }

  @Test
  void 시세가_없거나_이름이_바뀌었거나_캐시가_없거나_읽지_못하면_빈_값이다() {
    snapshot(
        item(67410303L, "혼돈의 젬 : 침식", "영웅", 1, null),
        item(67400003L, "다른 이름", "영웅", 1, 15_000L),
        item(67410403L, "혼돈의 젬 : 왜곡", "영웅", 1, 0L));
    assertThat(service.cachedPrices()).isEmpty();

    when(cache.findArkGridGemSnapshot()).thenReturn(Optional.empty());
    when(cache.findBoardScan(MarketTab.ARK_GRID, MarketTab.ARK_GRID.defaultTier()))
        .thenReturn(Optional.empty());
    assertThat(service.cachedPrices()).isEmpty();

    when(cache.findArkGridGemSnapshot()).thenThrow(new IllegalStateException("cache down"));
    assertThat(service.cachedPrices()).isEmpty();
  }
}
