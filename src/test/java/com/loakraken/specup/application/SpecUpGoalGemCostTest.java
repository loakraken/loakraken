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

  /**
   * 추천 순서 고르기(PM 총괄 11-19, 추천 순서의 10-18 '공짜 먼저' 대체, 비교 기준과 같음): 효율 있는 단계가 먼저이고, 더 드는 비용이 0 이하이거나
   * 모르는 단계는 그 뒤(Δ 큰 순, 비용 낮은 순, 모름은 가장 뒤). 목표 경로는 자기 규칙(아래).
   */
  @Test
  void 효율_있는_단계가_먼저이고_공짜와_비용_모름은_그_뒤다() {
    var free = scored(option("GEM_LEVEL:0", SpecUpCost.gem(390_000, 400_000L, false)), "24", null);
    var priced =
        scored(option("GEM_LEVEL:1", SpecUpCost.gem(1_000_000, 400_000L, false)), "24", "0.40");
    assertThat(SpecUpGoalService.rated(0L)).isFalse();
    assertThat(SpecUpGoalService.rated(-50_000L)).isFalse();
    assertThat(SpecUpGoalService.rated(null)).isFalse();
    assertThat(SpecUpGoalService.rated(1L)).isTrue();
    assertThat(SpecUpGoalService.baselinePreferred(priced, 600_000L, free, 0L)).isTrue();
    assertThat(SpecUpGoalService.baselinePreferred(free, 0L, priced, 600_000L)).isFalse();
    // 비율 없는 것끼리는 Δ 큰 순.
    var bigger =
        scored(option("GEM_LEVEL:2", SpecUpCost.gem(390_000, 400_000L, false)), "48", null);
    assertThat(SpecUpGoalService.baselinePreferred(bigger, null, free, 0L)).isTrue();
    // Δ도 같으면 비용 낮은 순: 공짜가 비용 모름보다 먼저.
    var unknown = scored(option("GEM_LEVEL:3", SpecUpCost.PRICE_PENDING), "24", null);
    assertThat(SpecUpGoalService.baselinePreferred(free, 0L, unknown, null)).isTrue();
    assertThat(SpecUpGoalService.baselinePreferred(unknown, null, free, 0L)).isFalse();
  }

  /** 목표 경로는 자기 규칙(PM 판정 10-18, 11-19 유지): 더 드는 비용이 0 이하인 단계를 효율 있는 단계보다 먼저(가장 싸게 목표까지). */
  @Test
  void 목표_경로는_더_드는_비용이_0_이하인_단계를_효율_있는_단계보다_먼저_고른다() {
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

  /**
   * 추천 순서 단계 비용(PM 총괄 11-19, 비교 기준과 같음): 귀속 보석·빈 칸은 단계마다 새 레벨 가격 전부, 거래 가능한 보석은 앞 단계와의 차이(0 미만은 0).
   */
  @Test
  void 귀속_보석은_단계마다_새_레벨_가격_전부이고_거래_가능하면_앞_단계_품목과의_차이다() {
    var held = new java.util.HashMap<String, Long>();
    // 귀속: 앞 단계와 상관없이 새 레벨 가격 전부.
    held.put("GEM_LEVEL:0", 80_000L);
    assertThat(
            SpecUpGoalService.paidCost(
                option("GEM_LEVEL:0", SpecUpCost.gem(900_000, null, true)), held))
        .isEqualTo(900_000L);
    // 빈 칸(지금 보석 없음): 전부.
    assertThat(
            SpecUpGoalService.paidCost(
                option("GEM_LEVEL:0", SpecUpCost.gem(900_000, null, false)), held))
        .isEqualTo(900_000L);
    // 거래 가능, 그 칸의 첫 단계: 처음 보석과의 차이(목록 비용 그대로). Lv7 100 → Lv8 80이면 0.
    var first = new java.util.HashMap<String, Long>();
    assertThat(
            SpecUpGoalService.paidCost(
                option("GEM_LEVEL:0", SpecUpCost.gem(80_000, 100_000L, false)), first))
        .isZero();
    // 이어 바꾸는 단계: 새 가격 − 앞 단계에서 산 보석 가격(계산 검증 11-19 예: Lv8 80 → Lv9 150 = 70, 처음 보석과의 차이 50이 아님).
    assertThat(
            SpecUpGoalService.paidCost(
                option("GEM_LEVEL:0", SpecUpCost.gem(150_000, 100_000L, false)), held))
        .isEqualTo(70_000L);
    // 앞 단계 보석보다 싸면 0.
    held.put("GEM_LEVEL:0", 200_000L);
    assertThat(
            SpecUpGoalService.paidCost(
                option("GEM_LEVEL:0", SpecUpCost.gem(150_000, 100_000L, false)), held))
        .isZero();
    // 앞 단계 가격을 모르면 모름, 시세를 모르면 모름.
    held.put("GEM_LEVEL:0", null);
    assertThat(
            SpecUpGoalService.paidCost(
                option("GEM_LEVEL:0", SpecUpCost.gem(150_000, 100_000L, false)), held))
        .isNull();
    assertThat(SpecUpGoalService.paidCost(option("GEM_LEVEL:0", SpecUpCost.PRICE_PENDING), first))
        .isNull();
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
    assertThat(SpecUpGoalService.baselinePreferred(b, 590_003L, a, 593_698L)).isTrue();
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
