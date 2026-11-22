package com.loakraken.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loakraken.common.api.BusinessException;
import com.loakraken.common.lostark.LostArkErrorCode;
import com.loakraken.market.domain.ArkGridGemCatalog;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** M49 G5: 아크 그리드 젬 시세(PM 총괄 11-22, 공식 230000 분류 전수 조회 18개). */
class ArkGridGemPriceServiceTest {
  private static final Instant OBSERVED = Instant.parse("2026-11-22T07:20:00Z");

  private final MarketBoardService boards = mock(MarketBoardService.class);
  private final ArkGridGemPriceService service = new ArkGridGemPriceService(boards);

  private static MarketPrice item(long id, String name, String grade, int bundle, Long min) {
    return new MarketPrice(
        id, name, grade, null, bundle, 0, null, null, min == null ? null : BigDecimal.valueOf(min));
  }

  private void scan(MarketPrice... items) {
    when(boards.scanForVolume(MarketTab.ARK_GRID))
        .thenReturn(new MarketBoardScan(MarketTab.ARK_GRID, null, OBSERVED, 18, List.of(items)));
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
  void 시세는_보드_탭_전체_스캔에서_ID와_이름이_맞는_매물의_1개_최저가다() {
    scan(
        item(67410303L, "혼돈의 젬 : 침식", "영웅", 1, 21_883L),
        item(67400003L, "질서의 젬 : 안정", "영웅", 1, 15_000L));
    assertThat(service.price("침식", "영웅"))
        .hasValueSatisfying(
            price -> {
              assertThat(price.gemId()).isEqualTo(67410303L);
              assertThat(price.unitPrice()).isEqualTo(21_883L);
              assertThat(price.observedAt()).isEqualTo(OBSERVED);
            });
    assertThat(service.prices())
        .extracting(ArkGridGemPriceService.Price::gemId)
        .containsExactlyInAnyOrder(67410303L, 67400003L);
  }

  @Test
  void 묶음이면_1개_값으로_올림한다() {
    scan(item(67410303L, "혼돈의 젬 : 침식", "영웅", 3, 100L));
    assertThat(service.price("침식", "영웅"))
        .hasValueSatisfying(price -> assertThat(price.unitPrice()).isEqualTo(34L));
  }

  @Test
  void 시세가_없거나_이름이_바뀌었거나_스캔이_실패하면_빈_값이다() {
    scan(
        item(67410303L, "혼돈의 젬 : 침식", "영웅", 1, null),
        item(67400003L, "다른 이름", "영웅", 1, 15_000L),
        item(67410403L, "혼돈의 젬 : 왜곡", "영웅", 1, 0L));
    assertThat(service.price("침식", "영웅")).isEmpty();
    assertThat(service.price("안정", "영웅")).isEmpty();
    assertThat(service.price("왜곡", "영웅")).isEmpty();
    assertThat(service.price("붕괴", "영웅")).isEmpty();
    assertThat(service.price("없는 종류", "영웅")).isEmpty();
    assertThat(service.prices()).isEmpty();

    when(boards.scanForVolume(MarketTab.ARK_GRID))
        .thenThrow(new BusinessException(LostArkErrorCode.LOST_ARK_API_ERROR));
    assertThat(service.price("침식", "영웅")).isEmpty();
    assertThat(service.prices()).isEmpty();
  }
}
