package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.specup.domain.SpecUpCost;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 보석 비용의 시세 종류(P0 #455 B1): 광휘는 그 칸 효과와 같은 종류, 효과를 못 읽으면 null. */
class SpecUpPriceKindTest {
  @Test
  void 광휘는_피해_증가면_겁화_재사용_대기시간_감소면_작열이다() {
    assertThat(SpecUpService.priceKind("광휘", List.of("피해 44.00% 증가"))).isEqualTo("겁화");
    assertThat(SpecUpService.priceKind("광휘", List.of("재사용 대기시간 24.00% 감소"))).isEqualTo("작열");
  }

  @Test
  void 겁화_작열은_그대로이고_광휘_효과를_못_읽으면_null이다() {
    assertThat(SpecUpService.priceKind("겁화", null)).isEqualTo("겁화");
    assertThat(SpecUpService.priceKind("작열", List.of())).isEqualTo("작열");
    assertThat(SpecUpService.priceKind("광휘", null)).isNull();
    assertThat(SpecUpService.priceKind("광휘", List.of("알 수 없는 효과"))).isNull();
  }

  @Test
  void 보석_시세_표가_비었으면_아직_받지_못한_것이라_PRICE_PENDING이다() {
    var cost = SpecUpService.gemCost(Map.of(), new Instant[1], "겁화", 8, 9, false);
    assertThat(cost).isEqualTo(SpecUpCost.PRICE_PENDING);
  }

  @Test
  void 표는_있는데_그_종류_칸이_없으면_NO_PRICE_SOURCE다() {
    var prices = new HashMap<String, MarketAuctionService.GemCell>();
    prices.put(
        "작열:9",
        new MarketAuctionService.GemCell(
            "작열",
            9,
            "9레벨 작열의 보석",
            new AuctionCell(1_000_000L, 5, Instant.parse("2026-10-13T00:00:00Z")),
            null,
            MarketAuctionService.Status.READY));
    assertThat(SpecUpService.gemCost(prices, new Instant[1], "멸화", 8, 9, false))
        .isEqualTo(SpecUpCost.NO_PRICE_SOURCE);
    assertThat(SpecUpService.gemCost(prices, new Instant[1], "작열", 8, 9, true).code())
        .isEqualTo("GEM_BUY_BOUND");
  }
}
