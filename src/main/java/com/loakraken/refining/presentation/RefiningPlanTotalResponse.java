package com.loakraken.refining.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.MaterialUnitPriceService.UnitPrice;
import com.loakraken.market.domain.MaterialPriceBasis;
import com.loakraken.refining.domain.RefiningPlan;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 여러 부위 재련 계획 합계(#764). 화면 합계와 같은 규칙: 재료마다 모든 부위·단계의 기대 수량(단계 값, 소수 둘째 자리)을 더해 한 번 올리고, 가진 수량을 뺀
 * 나머지를 통째 매물로 산다. 가진 재료는 계획(숨결·책 선택)을 바꾸지 않는다.
 */
public record RefiningPlanTotalResponse(
    @Schema(example = "refining-plan-total-v1") String totalRuleVersion,
    @Schema(description = "이번 계산의 숨결 방식(NONE·MAX·OPTIMAL)") String breath,
    @Schema(description = "재료 값 기준(재료 시세 API와 같음)", example = "CURRENT_MIN_PRICE")
        String priceBasis,
    @Schema(description = "부위별 계획(요청 순서, /plans 응답과 같은 모양)") List<RefiningPlanResponse> parts,
    @Schema(description = "재료별 합계(처음 나온 순서)") List<MaterialLine> materials,
    @Schema(description = "재련 골드 = max(0, ⌈모든 부위·단계 expectedGold 합⌉), 한 번 올림") long refineGold,
    @Schema(description = "사야 할 골드 = 재료별 purchaseGold 합(시세가 있는 재료만). 재료 시세 캐시가 없으면 null")
        @NullableResponse
        Long purchaseGold,
    @Schema(description = "합계 = refineGold + purchaseGold(화면 머리 숫자). purchaseGold가 null이면 null")
        @NullableResponse
        Long totalGold,
    @Schema(
            description =
                "가진 재료까지 시세로 치면 = 반올림(모든 단계 expectedGold 합 + Σ 재료 expected × unitPrice), 가진 재료는 빼지 않음(시세가 있는 재료만). 시세 캐시가 없으면 null")
        @NullableResponse
        Long marketValueGold,
    @Schema(description = "시세가 없어 purchaseGold·marketValueGold에서 뺀 재료 이름")
        List<String> unpricedMaterials,
    @Schema(
            description =
                "OPTIMAL: 계획 기대 비용 = ⌈모든 부위·단계 expectedCostGold(올리기 전) 합⌉, 한 번 올림. 가진 재료는 빼지 않음. 다른 방식이면 null")
        @NullableResponse
        Long expectedCostGold,
    @Schema(description = "쓴 재료 값 가운데 가장 오래된 관측 시각. 시세가 하나도 없으면 null") @NullableResponse
        Instant pricesObservedAt,
    @Schema(description = "계산 가정") List<String> assumptions) {

  public static final String RULE_VERSION = "refining-plan-total-v1";
  private static final BigDecimal EPSILON = new BigDecimal("1e-9");

  public record MaterialLine(
      String name,
      @Schema(description = "모든 부위·단계 기대 수량(단계 값, 소수 둘째 자리)의 합. 올리기 전") BigDecimal expected,
      @Schema(description = "필요 수량 = ⌈expected⌉") long amount,
      @Schema(description = "가진 수량(보내지 않았으면 0)") long owned,
      @Schema(description = "가진 것에서 쓰는 수량 = min(owned, amount)") long used,
      @Schema(description = "사야 할 수량 = max(0, amount − owned)") long missing,
      @Schema(description = "살 때 쓰는 매물(재료 시세 API와 같은 매물). 시세가 없으면 null") @NullableResponse
          ListingResponse listing,
      @Schema(description = "살 매물 수 = ⌈missing ÷ perUnit⌉. 시세가 없으면 null") @NullableResponse
          Long listings,
      @Schema(description = "이 재료 사야 할 골드 = listings × bundlePrice. 시세가 없으면 null") @NullableResponse
          Long purchaseGold) {}

  public record ListingResponse(
      @Schema(description = "묶음 수") int bundleCount,
      @Schema(description = "한 개에 든 수(운명의 파편 주머니면 파편 수, 그 밖은 1)") int contentsPerItem,
      @Schema(description = "매물 하나 값(골드, priceBasis 기준)") BigDecimal bundlePrice,
      @Schema(
              description =
                  "1개 값 = bundlePrice ÷ (bundleCount × contentsPerItem), 소수 여섯째 자리(재료 시세 API와 같음)")
          BigDecimal unitPrice) {}

  /** 한 부위의 풀이(응답과 계산 값). optimal은 OPTIMAL일 때만. */
  record Solved(
      RefiningPlanResponse response, RefiningPlan plan, List<RefiningPlan.OptimalStage> optimal) {}

  /**
   * prices: 재료 이름 목록을 받아 재료 시세 API와 같은 값을 주는 함수(캐시만). PRICED이고 매물 값이 있는 이름만 쓴다. 하나도 없으면 시세가 없는 것으로
   * 보고 골드 칸을 null로 둔다.
   */
  static RefiningPlanTotalResponse of(
      RefiningPlanRequest.Breath breath,
      MaterialPriceBasis basis,
      List<Solved> parts,
      List<RefiningPlanTotalRequest.Owned> ownedList,
      java.util.function.Function<List<String>, List<UnitPrice>> prices) {
    var expected = new LinkedHashMap<String, BigDecimal>();
    BigDecimal stageGold = BigDecimal.ZERO;
    double optimalCost = 0;
    for (var part : parts) {
      for (var stage : part.plan().stages()) {
        stageGold = stageGold.add(stage.expectedGold());
        for (var quantity : stage.expectedMaterials())
          expected.merge(quantity.name(), quantity.amount(), BigDecimal::add);
      }
      if (part.optimal() != null)
        for (var stage : part.optimal()) optimalCost += stage.expectedCostGold();
    }
    Map<String, Long> owned = new LinkedHashMap<>();
    if (ownedList != null)
      for (var item : ownedList) owned.merge(item.name().strip(), item.amount(), Long::sum);
    var priced = new LinkedHashMap<String, UnitPrice>();
    if (prices != null && !expected.isEmpty())
      for (var price : prices.apply(List.copyOf(expected.keySet())))
        if (price.status() == UnitPrice.Status.PRICED
            && price.unitPriceGold() != null
            && price.bundlePrice() != null) priced.put(price.name(), price);
    boolean anyPrice = !priced.isEmpty();

    var lines = new ArrayList<MaterialLine>();
    var unpriced = new ArrayList<String>();
    BigDecimal purchase = BigDecimal.ZERO;
    BigDecimal market = stageGold;
    Instant observedAt = null;
    for (var entry : expected.entrySet()) {
      var name = entry.getKey();
      var sum = entry.getValue();
      long amount = ceil(sum);
      long have = owned.getOrDefault(name, 0L);
      long used = Math.min(have, amount);
      long missing = Math.max(0, amount - have);
      var price = priced.get(name);
      if (anyPrice && price == null) unpriced.add(name);
      Long count = null;
      Long gold = null;
      ListingResponse listing = null;
      if (price != null) {
        int bundle = price.bundleCount() == null ? 1 : Math.max(1, price.bundleCount());
        int contents = price.contentsPerItem() == null ? 1 : Math.max(1, price.contentsPerItem());
        long perUnit = (long) bundle * contents;
        count = (missing + perUnit - 1) / perUnit;
        var cost = price.bundlePrice().multiply(BigDecimal.valueOf(count));
        gold = ceil(cost);
        purchase = purchase.add(cost);
        market = market.add(sum.multiply(price.unitPriceGold()));
        listing = new ListingResponse(bundle, contents, price.bundlePrice(), price.unitPriceGold());
        if (price.observedAt() != null
            && (observedAt == null || price.observedAt().isBefore(observedAt)))
          observedAt = price.observedAt();
      }
      lines.add(new MaterialLine(name, sum, amount, have, used, missing, listing, count, gold));
    }
    long refineGold = Math.max(0, ceil(stageGold));
    Long purchaseGold = anyPrice ? Math.max(0, ceil(purchase)) : null;
    return new RefiningPlanTotalResponse(
        RULE_VERSION,
        breath.name(),
        basis.name(),
        parts.stream().map(Solved::response).toList(),
        List.copyOf(lines),
        refineGold,
        purchaseGold,
        purchaseGold == null ? null : refineGold + purchaseGold,
        anyPrice ? market.setScale(0, RoundingMode.HALF_UP).longValueExact() : null,
        List.copyOf(unpriced),
        breath == RefiningPlanRequest.Breath.OPTIMAL ? (long) Math.ceil(optimalCost) : null,
        observedAt,
        List.of(
            "보유 재료는 계획을 바꾸지 않고 구매량에서만 뺍니다.",
            "재료마다 모든 부위·단계 기대 수량을 더해 한 번 올린 수량에서 가진 수량을 빼고, 남은 수량은 재료 시세 API와 같은 매물(priceBasis 기준 1개 값이 가장 싼 것)을 통째로 산다고 봅니다.",
            "재료 값은 서버 캐시만 읽습니다. 캐시에 없는 재료는 시세 없음(unpricedMaterials)으로 둡니다.",
            "재련 골드는 모든 단계 골드를 더해 한 번 올립니다."));
  }

  /** ⌈value − 1e-9⌉(화면과 같은 올림: 부동소수 찌꺼기로 한 단위 올라가지 않게). */
  static long ceil(BigDecimal value) {
    return value.subtract(EPSILON).setScale(0, RoundingMode.CEILING).longValueExact();
  }
}
