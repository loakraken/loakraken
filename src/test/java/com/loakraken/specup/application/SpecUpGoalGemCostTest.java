package com.loakraken.specup.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.loakraken.character.application.CombatPowerSimulationService.Change;
import com.loakraken.character.application.CombatPowerSimulationService.Kind;
import com.loakraken.market.application.AuctionPort.AuctionCell;
import com.loakraken.market.application.MarketAuctionService;
import com.loakraken.market.application.MarketAuctionService.GemCell;
import com.loakraken.specup.domain.SpecUpCost;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 목표 경로 보석 단계 비용 = 지금에서 그 레벨까지 누적(목록 직접 비용과 같은 식), 단계마다 따로, 경로 합계는 칸마다 마지막 누적. */
class SpecUpGoalGemCostTest {
  private static final Instant AT = Instant.parse("2026-10-18T00:00:00Z");

  @Test
  void 거꾸로_시세에서도_단계마다_누적이고_마지막_단계가_직접_비용이다() {
    // 8레벨 400,000(지금) · 9레벨 390,000 · 10레벨 1,000,000.
    var gems = cells(Map.of(8, 400_000L, 9, 390_000L, 10, 1_000_000L));
    var pricesAt = new Instant[1];
    var nine = SpecUpGoalService.gemCost(gems, pricesAt, slot(8, 8, false), 9);
    var ten = SpecUpGoalService.gemCost(gems, pricesAt, slot(8, 9, false), 10);
    assertThat(nine.amount()).isZero(); // 음수 없음
    assertThat(ten.amount())
        .isEqualTo(600_000)
        .isEqualTo(SpecUpService.gemCost(gems, pricesAt, "겁화", 8, 10, false).amount());
    // 같은 값(9레벨 = 지금)도 0.
    var same = cells(Map.of(8, 400_000L, 9, 400_000L, 10, 1_000_000L));
    assertThat(SpecUpGoalService.gemCost(same, pricesAt, slot(8, 8, false), 9).amount()).isZero();
    // 귀속이면 그 레벨 최저가 그대로.
    assertThat(SpecUpGoalService.gemCost(gems, pricesAt, slot(8, 9, true), 10).amount())
        .isEqualTo(1_000_000);
  }

  @Test
  void 그_레벨_시세가_없으면_그_단계만_모르고_지금_시세가_없으면_모두_모른다() {
    var noNine = cells(Map.of(8, 400_000L, 10, 1_000_000L));
    var pricesAt = new Instant[1];
    assertThat(SpecUpGoalService.gemCost(noNine, pricesAt, slot(8, 8, false), 9).amount()).isNull();
    assertThat(SpecUpGoalService.gemCost(noNine, pricesAt, slot(8, 9, false), 10).amount())
        .isEqualTo(600_000);
    var noNow = cells(Map.of(9, 390_000L, 10, 1_000_000L));
    assertThat(SpecUpGoalService.gemCost(noNow, pricesAt, slot(8, 8, false), 9).amount()).isNull();
    assertThat(SpecUpGoalService.gemCost(noNow, pricesAt, slot(8, 9, false), 10).amount()).isNull();
  }

  @Test
  void 경로_합계는_보석_칸마다_마지막_누적이고_앞_단계_모름이_번지지_않는다() {
    var totals = new HashMap<String, Long>();
    totals.put("GEM_LEVEL:0", null); // 8 → 9 단계 비용 모름
    assertThat(SpecUpGoalService.total(0, false, totals)).isNull();
    // 9 → 10 단계 비용(지금에서 누적 600,000)을 알면 그 칸 값이 바뀌어 합계가 다시 생긴다.
    var option = option("GEM_LEVEL:0", SpecUpCost.gem(1_000_000, 400_000L, false));
    assertThat(SpecUpGoalService.marginal(option, totals)).isNull(); // 이미 낸 누적을 몰라 더 드는 비용은 모름
    totals.put("GEM_LEVEL:0", 600_000L);
    assertThat(SpecUpGoalService.total(50_000, false, totals)).isEqualTo(650_000);
    // 이어서 올리는 단계의 더 드는 비용 = 누적 차이(거꾸로면 0 이하 → 효율 null).
    totals.put("GEM_LEVEL:1", 500_000L);
    assertThat(
            SpecUpGoalService.marginal(
                option("GEM_LEVEL:1", SpecUpCost.gem(900_000, 400_000L, false)), totals))
        .isZero();
    assertThat(SpecUpCost.efficiency(java.math.BigDecimal.TEN, 0L)).isNull();
    // 다른 단계 비용을 모르면 합계도 모름.
    assertThat(SpecUpGoalService.total(50_000, true, totals)).isNull();
  }

  @Test
  void 누적이_줄면_더_드는_비용이_음수라_효율이_없고_여러_칸_경로_합계는_칸마다_마지막_누적이다() {
    var totals = new HashMap<String, Long>();
    // 0번 칸: 9레벨 누적 600,000 → 10레벨 누적 500,000(윗 레벨이 더 쌈) → 더 드는 비용 −100,000, 효율 null.
    totals.put("GEM_LEVEL:0", 600_000L);
    var cheaper = option("GEM_LEVEL:0", SpecUpCost.gem(900_000, 400_000L, false));
    assertThat(SpecUpGoalService.marginal(cheaper, totals)).isEqualTo(-100_000);
    assertThat(
            SpecUpCost.efficiency(
                java.math.BigDecimal.TEN, SpecUpGoalService.marginal(cheaper, totals)))
        .isNull();
    totals.put("GEM_LEVEL:0", 500_000L);
    // 1번 칸: 두 단계를 올려 마지막 누적 2,600,000. 그 밖 단계(재련) 합 300,000.
    totals.put("GEM_LEVEL:1", 2_600_000L);
    assertThat(SpecUpGoalService.total(300_000, false, totals))
        .isEqualTo(500_000 + 2_600_000 + 300_000);
    // 보석 단계가 아닌 후보의 더 드는 비용은 그 단계 비용 그대로.
    var refine =
        new SpecUpGoalService.Option(
            "EQUIPMENT_REFINE:0",
            "EQUIPMENT_REFINE:0:21",
            "무기",
            null,
            null,
            null,
            null,
            null,
            new Change(
                Kind.EQUIPMENT_REFINE,
                null,
                0,
                null,
                null,
                21,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            SpecUpCost.refineExpected(300_000),
            null,
            null,
            null);
    assertThat(SpecUpGoalService.marginal(refine, totals)).isEqualTo(300_000);
  }

  @Test
  void 더_드는_비용이_0_이하인_단계를_효율_있는_단계보다_먼저_고른다() {
    var free = scored(option("GEM_LEVEL:0", SpecUpCost.gem(390_000, 400_000L, false)), "24", null);
    var priced =
        scored(option("GEM_LEVEL:1", SpecUpCost.gem(1_000_000, 400_000L, false)), "24", "0.40");
    assertThat(SpecUpGoalService.free(0L)).isTrue();
    assertThat(SpecUpGoalService.free(-50_000L)).isTrue();
    assertThat(SpecUpGoalService.free(null)).isFalse();
    assertThat(SpecUpGoalService.preferred(free, true, priced, false)).isTrue();
    assertThat(SpecUpGoalService.preferred(priced, false, free, true)).isFalse();
    // 0 단계끼리는 Δ 큰 순.
    var bigger =
        scored(option("GEM_LEVEL:2", SpecUpCost.gem(390_000, 400_000L, false)), "48", null);
    assertThat(SpecUpGoalService.preferred(bigger, true, free, true)).isTrue();
  }

  /** 소수 둘째 자리에서 같아지는 효율(0.6476 · 0.6517 → 둘 다 0.65)도 반올림 전 값으로 가른다. Δ가 같아도 id 순으로 가지 않는다. */
  @Test
  void 효율은_반올림_전_값으로_고르고_화면_값만_둘째_자리다() {
    var delta = new java.math.BigDecimal("38.45");
    var low = SpecUpGoalService.rank(delta, 593_698L);
    var high = SpecUpGoalService.rank(delta, 590_003L);
    assertThat(SpecUpCost.efficiency(delta, 593_698L))
        .isEqualByComparingTo(SpecUpCost.efficiency(delta, 590_003L));
    assertThat(high).isGreaterThan(low);
    var a =
        option("GEM_LEVEL:10", SpecUpCost.gem(593_698, 0L, false))
            .scored(delta, java.math.BigDecimal.valueOf(3000), low);
    var b =
        option("GEM_LEVEL:5", SpecUpCost.gem(590_003, 0L, false))
            .scored(delta, java.math.BigDecimal.valueOf(3000), high);
    // id만 보면 "GEM_LEVEL:10:10" < "GEM_LEVEL:5:10"이지만 반올림 전 효율이 큰 b가 앞선다.
    assertThat(SpecUpGoalService.ORDER.compare(b, a)).isNegative();
    assertThat(SpecUpGoalService.preferred(b, false, a, false)).isTrue();
    assertThat(SpecUpGoalService.rank(delta, 0L)).isNull();
    assertThat(SpecUpGoalService.rank(delta, null)).isNull();
  }

  private static SpecUpGoalService.Option scored(
      SpecUpGoalService.Option option, String delta, String efficiency) {
    return option.scored(
        new java.math.BigDecimal(delta),
        java.math.BigDecimal.valueOf(3000),
        efficiency == null ? null : new java.math.BigDecimal(efficiency));
  }

  private static SpecUpGoalService.Option option(String slot, SpecUpCost cost) {
    return new SpecUpGoalService.Option(
        slot,
        slot + ":10",
        "보석",
        null,
        null,
        null,
        null,
        null,
        new Change(
            Kind.GEM_LEVEL,
            0,
            null,
            null,
            10,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null),
        cost,
        null,
        null,
        null);
  }

  private static SpecUpGoalService.GemSlot slot(int original, int level, boolean bound) {
    return new SpecUpGoalService.GemSlot(0, "겁화", "겁화", original, level, bound, null, null, null);
  }

  private static Map<String, GemCell> cells(Map<Integer, Long> prices) {
    var cells = new HashMap<String, GemCell>();
    prices.forEach(
        (level, price) ->
            cells.put(
                "겁화:" + level,
                new GemCell(
                    "겁화",
                    level,
                    level + "레벨 겁화의 보석",
                    new AuctionCell(price, 5, AT),
                    null,
                    MarketAuctionService.Status.READY)));
    return cells;
  }
}
