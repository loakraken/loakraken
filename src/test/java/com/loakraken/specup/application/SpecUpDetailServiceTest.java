package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.RefiningMaterialPriceService;
import com.loakraken.refining.domain.RefiningPlan;
import com.loakraken.specup.domain.SpecUpCost;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 줄 상세(#544)의 금액 대조·시세 줄 합계·재료 내역 규칙. */
class SpecUpDetailServiceTest {
  private static final Instant AT = Instant.parse("2026-10-18T00:00:00Z");

  @Test
  void 비용이_OK일_때만_내역_금액과_대조하고_다르면_다시_만든다() {
    var ok = SpecUpCost.gem(1_000_000, 400_000L, false);
    assertThat(SpecUpDetailService.matches(ok, 620_000L)).isTrue();
    // 목록 계산과 내역 읽기 사이에 시세가 바뀐 경우.
    assertThat(SpecUpDetailService.matches(ok, 630_000L)).isFalse();
    assertThat(SpecUpDetailService.matches(ok, null)).isFalse();
    // 시세 대기·근거 없음·비용 없음은 대조하지 않는다.
    assertThat(SpecUpDetailService.matches(SpecUpCost.PRICE_PENDING, null)).isTrue();
    assertThat(SpecUpDetailService.matches(SpecUpCost.NO_PRICE_SOURCE, 1L)).isTrue();
    assertThat(SpecUpDetailService.matches(null, null)).isTrue();
  }

  @Test
  void 보석_시세_줄_합계는_목록_보석_비용과_같은_식이다() {
    var buy = market("BUY", 1_000_000, 1, null);
    var sell = market("SELL", 400_000, 1, 380_000L);
    assertThat(SpecUpDetailService.gemTotal(List.of(buy, sell)))
        .isEqualTo(SpecUpCost.gem(1_000_000, 400_000L, false).amount());
    // 귀속 보석·빈 칸은 파는 줄이 없어 사는 값 그대로.
    assertThat(SpecUpDetailService.gemTotal(List.of(buy)))
        .isEqualTo(SpecUpCost.gem(1_000_000, 400_000L, true).amount());
    // 판매 기대가가 더 크면 0(목록 비용과 같음).
    assertThat(
            SpecUpDetailService.gemTotal(
                List.of(market("BUY", 100, 1, null), market("SELL", 500, 1, 475L))))
        .isZero();
    assertThat(SpecUpDetailService.gemTotal(null)).isNull();
    assertThat(SpecUpDetailService.gemTotal(List.of(sell))).isNull();
  }

  @Test
  void 각인서_합계는_1권_값_곱하기_5권이고_시세가_없으면_null이다() {
    assertThat(SpecUpDetailService.bookTotal(List.of(market("BUY", 12_000, 5, null))))
        .isEqualTo(SpecUpCost.engravingBooks(12_000).amount());
    assertThat(SpecUpDetailService.bookTotal(null)).isNull();
  }

  @Test
  void 재료_내역은_0개_재료를_빼고_시세가_없는_재료만_PENDING이다() {
    var plan =
        new RefiningPlan(
            List.of(),
            List.of(
                new RefiningPlan.Quantity("운명의 파괴석", new BigDecimal("1250.404")),
                new RefiningPlan.Quantity("용암의 숨결", BigDecimal.ZERO),
                new RefiningPlan.Quantity("아비도스 융화 재료", new BigDecimal("12.5"))),
            List.of(),
            new BigDecimal("18432.5"),
            0);
    var prices =
        new RefiningMaterialPriceService.Prices(
            AT, Map.of("운명의 파괴석", new BigDecimal("1.525")));
    var rows = SpecUpDetailService.materialRows(plan, prices);
    assertThat(rows).extracting(SpecUpDetailService.Material::itemName)
        .containsExactly("운명의 파괴석", "아비도스 융화 재료");
    var stone = rows.getFirst();
    assertThat(stone.quantity()).isEqualByComparingTo("1250.40");
    assertThat(stone.unitPrice()).isEqualByComparingTo("1.53");
    // 금액은 반올림 전 수량 × 단가(1250.404 × 1.525 = 1906.866…)를 반올림한 값.
    assertThat(stone.gold()).isEqualByComparingTo("1906.87");
    assertThat(stone.priceStatus()).isEqualTo("OK");
    assertThat(stone.priceBasis()).isEqualTo("MARKET_MIN_UNIT");
    var fusion = rows.get(1);
    assertThat(fusion.priceStatus()).isEqualTo("PENDING");
    assertThat(fusion.unitPrice()).isNull();
    assertThat(fusion.gold()).isNull();
    assertThat(fusion.priceBasis()).isNull();
    // 시세가 아예 없으면 모두 PENDING.
    assertThat(SpecUpDetailService.materialRows(plan, null))
        .allMatch(row -> "PENDING".equals(row.priceStatus()));
  }

  private static SpecUpDetailService.Market market(
      String role, long price, int quantity, Long sale) {
    return new SpecUpDetailService.Market(
        role, "보석", null, null, price, quantity, sale, null, AT);
  }
}
