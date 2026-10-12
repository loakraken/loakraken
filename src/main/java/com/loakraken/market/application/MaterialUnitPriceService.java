package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MaterialPriceBasis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 재료 이름으로 거래소 현재 판매 목록에서 1개당 골드를 찾는다. 운명의 파편은 주머니 시세로 환산한다. */
@Service
public class MaterialUnitPriceService {
  public static final String SHARD = "운명의 파편";
  static final int MATERIAL_CATEGORY = 50000;
  private static final String SHARD_POUCH_SEARCH = "운명의 파편 주머니";
  // 공식 거래소 상세 Tooltip "사용 시, N 운명의 파편을 획득할 수 있다."(2026-10-05 확인)
  static final Map<String, Integer> SHARD_POUCHES =
      Map.of(
          "운명의 파편 주머니(소)", 1000,
          "운명의 파편 주머니(중)", 2000,
          "운명의 파편 주머니(대)", 3000);

  private final MarketQueryService markets;

  public MaterialUnitPriceService(MarketQueryService markets) {
    this.markets = markets;
  }

  public record UnitPrice(
      String name,
      Status status,
      BigDecimal unitPriceGold,
      String sourceItemName,
      Integer bundleCount,
      BigDecimal bundlePrice,
      Integer contentsPerItem,
      Instant observedAt) {
    public enum Status {
      PRICED,
      NOT_FOUND,
      PRICE_UNAVAILABLE
    }
  }

  public List<UnitPrice> prices(MaterialPriceBasis basis, List<String> names) {
    var result = new ArrayList<UnitPrice>();
    for (var name : new LinkedHashSet<>(names)) {
      result.add(SHARD.equals(name) ? shard(basis) : material(basis, name));
    }
    return result;
  }

  private UnitPrice material(MaterialPriceBasis basis, String name) {
    var page = search(name);
    var exact = page.page().items().stream().filter(item -> item.name().equals(name)).toList();
    if (exact.isEmpty())
      return new UnitPrice(
          name, UnitPrice.Status.NOT_FOUND, null, null, null, null, null, page.observedAt());
    MarketPrice best = null;
    BigDecimal bestUnit = null;
    for (var item : exact) {
      var unit = unit(basis.select(item), item.bundleCount(), 1);
      if (unit != null && (bestUnit == null || unit.compareTo(bestUnit) < 0)) {
        best = item;
        bestUnit = unit;
      }
    }
    if (best == null)
      return new UnitPrice(
          name,
          UnitPrice.Status.PRICE_UNAVAILABLE,
          null,
          exact.getFirst().name(),
          exact.getFirst().bundleCount(),
          null,
          null,
          page.observedAt());
    return new UnitPrice(
        name,
        UnitPrice.Status.PRICED,
        bestUnit,
        best.name(),
        best.bundleCount(),
        basis.select(best),
        null,
        page.observedAt());
  }

  private UnitPrice shard(MaterialPriceBasis basis) {
    var page = search(SHARD_POUCH_SEARCH);
    MarketPrice best = null;
    BigDecimal bestUnit = null;
    for (var item : page.page().items()) {
      var contents = SHARD_POUCHES.get(item.name());
      if (contents == null) continue;
      var unit = unit(basis.select(item), item.bundleCount(), contents);
      if (unit != null && (bestUnit == null || unit.compareTo(bestUnit) < 0)) {
        best = item;
        bestUnit = unit;
      }
    }
    if (best == null)
      return new UnitPrice(
          SHARD,
          UnitPrice.Status.PRICE_UNAVAILABLE,
          null,
          null,
          null,
          null,
          null,
          page.observedAt());
    return new UnitPrice(
        SHARD,
        UnitPrice.Status.PRICED,
        bestUnit,
        best.name(),
        best.bundleCount(),
        basis.select(best),
        SHARD_POUCHES.get(best.name()),
        page.observedAt());
  }

  private MarketResult search(String itemName) {
    return markets.search(
        MarketSource.ACTIVE_LISTING,
        new MarketSearch(
            MATERIAL_CATEGORY,
            "",
            null,
            "",
            itemName,
            1,
            MarketSearch.Sort.RECENT_PRICE,
            MarketSearch.SortCondition.ASC));
  }

  private static BigDecimal unit(BigDecimal bundlePrice, int bundleCount, int contents) {
    if (bundlePrice == null || bundlePrice.signum() <= 0) return null;
    return bundlePrice.divide(
        BigDecimal.valueOf((long) bundleCount * contents), 6, RoundingMode.HALF_UP);
  }
}
