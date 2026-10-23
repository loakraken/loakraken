package com.loakraken.market.presentation;

import com.loakraken.common.api.NullableResponse;
import com.loakraken.market.application.*;
import com.loakraken.market.domain.MaterialPriceBasis;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MaterialQuoteResponse(
    String ruleVersion,
    MaterialPriceBasis priceBasis,
    @Schema(description = "가격을 읽은 목록. 현재 판매 목록의 최근/전일 가격도 선택할 수 있습니다.") MarketSource source,
    @Schema(description = "견적 계산 완료 시각. 각 항목의 가격 수집 시각과 다릅니다.") Instant calculatedAt,
    @Schema(description = "모든 항목의 구매 비용을 계산할 수 있는지 여부. 실제 구매 가능 보장은 아닙니다.") boolean complete,
    @Schema(description = "계산 가능한 항목만의 골드 소계") BigDecimal knownCostGold,
    @NullableResponse @Schema(description = "미계산 항목이 하나라도 있으면 null. 재련 골드·페온·판매 수수료는 제외합니다.")
        BigDecimal totalCostGold,
    List<MaterialQuoteLineResponse> items) {
  public static MaterialQuoteResponse from(MaterialQuote result) {
    return new MaterialQuoteResponse(
        MaterialQuote.RULE_VERSION,
        result.priceBasis(),
        MarketSource.ACTIVE_LISTING,
        result.calculatedAt(),
        result.complete(),
        result.knownCostGold(),
        result.totalCostGold(),
        result.items().stream().map(MaterialQuoteLineResponse::from).toList());
  }

  public record MaterialQuoteLineResponse(
      long itemId,
      long requiredQuantity,
      long ownedQuantity,
      long missingQuantity,
      MaterialQuoteSearchResponse search,
      @Schema(
              description =
                  "PRICED 계산됨(구매 후 거래 횟수 제한 상품 포함), COVERED_BY_INVENTORY 보유량으로 충당, ITEM_NOT_IN_PAGE 검색 페이지에 상품 없음,"
                      + " PRICE_UNAVAILABLE 선택한 가격 미제공, ZERO_PRICE 0원 가격(무료로 해석하지 않음). AMBIGUOUS_ITEM·TRADE_RESTRICTED_ITEM은"
                      + " material-purchase-v2부터 내지 않습니다(값 목록 호환용).")
          MaterialQuote.Status status,
      @NullableResponse @Schema(description = "가격 페이지 관측 시각. 재고로 충당하면 조회하지 않아 null")
          Instant observedAt,
      @NullableResponse String itemName,
      @NullableResponse Integer bundleCount,
      @NullableResponse
          @Schema(
              description =
                  "가격을 쓴 상품의 공식 TradeRemainCount(구매 후 거래 가능 횟수, 0이면 산 뒤 거래 불가). 일반 상품·가격을 쓰지 않은 줄은 null."
                      + " 같은 상품이 여러 줄이면 선택한 가격 기준으로 가장 싸게 사는 줄의 값입니다.")
          Integer tradeRemainCount,
      @NullableResponse @Schema(description = "선택한 공식 묶음 가격. 0을 무료로 해석하지 않고 ZERO_PRICE로 계산 제외")
          BigDecimal pricePerBundle,
      @NullableResponse MaterialPurchaseResponse purchase) {
    static MaterialQuoteLineResponse from(MaterialQuote.Line line) {
      var item = line.requirement();
      var cost = line.purchase();
      return new MaterialQuoteLineResponse(
          item.itemId(),
          item.requiredQuantity(),
          item.ownedQuantity(),
          item.missingQuantity(),
          MaterialQuoteSearchResponse.from(item.search()),
          line.status(),
          line.observedAt(),
          line.itemName(),
          line.bundleCount(),
          line.tradeRemainCount(),
          line.pricePerBundle(),
          cost == null
              ? null
              : new MaterialPurchaseResponse(
                  cost.bundleQuantity(),
                  cost.purchaseQuantity(),
                  cost.surplusQuantity(),
                  cost.costGold()));
    }
  }

  public record MaterialPurchaseResponse(
      long bundleQuantity, long purchaseQuantity, long surplusQuantity, BigDecimal costGold) {}

  public record MaterialQuoteSearchResponse(
      int categoryCode,
      String characterClass,
      @NullableResponse Integer itemTier,
      String itemGrade,
      String itemName,
      int pageNo,
      MarketSearch.Sort sort,
      MarketSearch.SortCondition sortCondition) {
    static MaterialQuoteSearchResponse from(MarketSearch search) {
      return new MaterialQuoteSearchResponse(
          search.categoryCode(),
          search.characterClass(),
          search.itemTier(),
          search.itemGrade(),
          search.itemName(),
          search.pageNo(),
          search.sort(),
          search.sortCondition());
    }
  }
}
