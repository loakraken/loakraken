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

/**
 * 재료 이름으로 거래소 현재 판매 목록에서 1개당 골드를 찾는다. 운명의 파편은 주머니 시세로 환산한다.
 *
 * <p>강화 재료(50000)에서 먼저 찾고, 정확히 같은 이름이 없으면 생활(90000, 목재·철광석·유물 등)에서 찾는다. 두 코드는 공식 거래소
 * options(2026-10-14 확인)의 상위 분류다. 이름 검색은 부분 일치라 정확한 이름이 1페이지에 없으면 몇 페이지 더 본다. 한 번 찾은 이름은 그 분류부터 찾아
 * 공식 호출을 줄인다.
 */
@Service
public class MaterialUnitPriceService {
  public static final String SHARD = "운명의 파편";
  static final int MATERIAL_CATEGORY = 50000;
  static final int LIFE_CATEGORY = 90000;
  static final List<Integer> CATEGORIES = List.of(MATERIAL_CATEGORY, LIFE_CATEGORY);

  /** 정확한 이름을 찾을 때 한 분류에서 보는 최대 페이지(페이지당 10개). */
  static final int MAX_PAGES = 3;

  private static final int MAX_REMEMBERED = 2000;
  private static final String SHARD_POUCH_SEARCH = "운명의 파편 주머니";
  // 공식 거래소 상세 Tooltip "사용 시, N 운명의 파편을 획득할 수 있다."(2026-10-05 확인)
  static final Map<String, Integer> SHARD_POUCHES =
      Map.of(
          "운명의 파편 주머니(소)", 1000,
          "운명의 파편 주머니(중)", 2000,
          "운명의 파편 주머니(대)", 3000);

  private final MarketQueryService markets;
  // 이름 → 찾은 분류(공식 호출을 줄이는 힌트일 뿐, 틀리면 다른 분류도 본다).
  private final Map<String, Integer> foundIn = new java.util.concurrent.ConcurrentHashMap<>();

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

  /** 정확히 같은 이름의 판매 목록과 그 조회 시각. 못 찾으면 items가 비고 시각은 마지막 조회 시각. */
  private record Exact(List<MarketPrice> items, Instant observedAt) {}

  private Exact exact(String name) {
    var known = foundIn.get(name);
    var order = new ArrayList<Integer>();
    if (known != null) order.add(known);
    for (var category : CATEGORIES) if (!order.contains(category)) order.add(category);
    Instant observedAt = null;
    for (var category : order) {
      for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
        var result = search(category, name, pageNo);
        observedAt = result.observedAt();
        var exact =
            result.page().items().stream().filter(item -> item.name().equals(name)).toList();
        if (!exact.isEmpty()) {
          if (foundIn.size() < MAX_REMEMBERED || foundIn.containsKey(name))
            foundIn.put(name, category);
          return new Exact(exact, observedAt);
        }
        if ((long) pageNo * result.page().pageSize() >= result.page().totalCount()) break;
      }
    }
    return new Exact(List.of(), observedAt);
  }

  private UnitPrice material(MaterialPriceBasis basis, String name) {
    var found = exact(name);
    var exact = found.items();
    if (exact.isEmpty())
      return new UnitPrice(
          name, UnitPrice.Status.NOT_FOUND, null, null, null, null, null, found.observedAt());
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
          found.observedAt());
    return new UnitPrice(
        name,
        UnitPrice.Status.PRICED,
        bestUnit,
        best.name(),
        best.bundleCount(),
        basis.select(best),
        null,
        found.observedAt());
  }

  private UnitPrice shard(MaterialPriceBasis basis) {
    var page = search(MATERIAL_CATEGORY, SHARD_POUCH_SEARCH, 1);
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

  private MarketResult search(int category, String itemName, int pageNo) {
    return markets.search(
        MarketSource.ACTIVE_LISTING,
        new MarketSearch(
            category,
            "",
            null,
            "",
            itemName,
            pageNo,
            MarketSearch.Sort.RECENT_PRICE,
            MarketSearch.SortCondition.ASC));
  }

  private static BigDecimal unit(BigDecimal bundlePrice, int bundleCount, int contents) {
    if (bundlePrice == null || bundlePrice.signum() <= 0) return null;
    return bundlePrice.divide(
        BigDecimal.valueOf((long) bundleCount * contents), 6, RoundingMode.HALF_UP);
  }
}
