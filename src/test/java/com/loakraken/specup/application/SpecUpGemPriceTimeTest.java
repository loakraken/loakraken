package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.MarketAuctionService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 보석 비용은 쓴 시세의 관측 시각과, 이전 시세(마지막 값)를 썼는지를 함께 낸다(PM 총괄 10-25). */
class SpecUpGemPriceTimeTest {
  private static MarketAuctionService.GemCell cell(int level, long price, Instant at, boolean stale) {
    return new MarketAuctionService.GemCell(
        "겁화",
        level,
        level + "레벨 겁화의 보석",
        new AuctionCell(price, 1, at),
        null,
        MarketAuctionService.Status.READY,
        stale);
  }

  @Test
  void 지금_시세면_stale_false이고_관측_시각은_목표와_지금_가운데_이른_쪽이다() {
    var old = Instant.parse("2026-10-25T09:00:00Z");
    var fresh = Instant.parse("2026-10-25T09:02:00Z");
    var prices = Map.of("겁화:8", cell(8, 900_000, fresh, false), "겁화:7", cell(7, 300_000, old, false));
    var cost = SpecUpService.gemCost(prices, new Instant[1], "겁화", 7, 8, false);
    assertThat(cost.code()).isEqualTo("GEM_UPGRADE");
    assertThat(cost.amount()).isEqualTo(600_000L);
    assertThat(cost.stale()).isFalse();
    assertThat(cost.priceObservedAt()).isEqualTo(old);
  }

  @Test
  void 목표나_지금_칸이_마지막_값이면_stale_true다() {
    var at = Instant.parse("2026-10-25T08:00:00Z");
    var prices = Map.of("겁화:8", cell(8, 900_000, at, true), "겁화:7", cell(7, 300_000, at, false));
    var cost = SpecUpService.gemCost(prices, new Instant[1], "겁화", 7, 8, false);
    assertThat(cost.stale()).isTrue();
    assertThat(cost.priceObservedAt()).isEqualTo(at);
    // 귀속이면 지금 칸을 보지 않는다
    var bound = SpecUpService.gemCost(prices, new Instant[1], "겁화", 7, 8, true);
    assertThat(bound.code()).isEqualTo("GEM_BUY_BOUND");
    assertThat(bound.stale()).isTrue();
  }

  @Test
  void 칸도_마지막_값도_없으면_지금처럼_PENDING이고_시각_stale은_비운다() {
    var cost = SpecUpService.gemCost(Map.of(), new Instant[1], "겁화", 7, 8, false);
    assertThat(cost.code()).isEqualTo("PRICE_PENDING");
    assertThat(cost.amount()).isNull();
    assertThat(cost.stale()).isNull();
    assertThat(cost.priceObservedAt()).isNull();
  }
}
