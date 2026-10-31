package com.loakraken.market.application;

import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * T4 재련 재료 시세(L30 후속, 스펙업 재련 비용). 읽기는 예열이 저장한 캐시만 쓰고 공식 API를 부르지 않는다. 값은 즉시 구매
 * 최저가(CurrentMinPrice)를 묶음 수로 나눈 1개 값이다. 운명의 파편은 주머니(소·중·대) 가운데 1개당 가장 싼 값이다.
 */
@Service
public class RefiningMaterialPriceService {
  /** 예열이 훑는 탭(T4): 재련 재료(돌·돌파석·융화 재료·파편 주머니), 재련 추가 재료(숨결). */
  public static final List<MarketTab> TABS = List.of(MarketTab.REFINING, MarketTab.REFINING_EXTRA);

  public static final int TIER = 4;
  private static final int UNIT_SCALE = 6;

  private final MarketQueryService markets;
  private final MarketCache cache;

  public RefiningMaterialPriceService(MarketQueryService markets, MarketCache cache) {
    this.markets = markets;
    this.cache = cache;
  }

  /** 재료 이름 → 1개 값(골드). observedAt은 스캔 관측 시각이다. */
  public record Prices(Instant observedAt, Map<String, BigDecimal> unitPrices) {
    public Prices {
      unitPrices = Map.copyOf(unitPrices);
    }

    public Optional<BigDecimal> unit(String name) {
      return Optional.ofNullable(unitPrices.get(name));
    }
  }

  /** 캐시에 있으면 재료별 1개 값. 없거나 캐시를 읽지 못하면 빈 값(공식 호출 없음). */
  public Optional<Prices> cached() {
    try {
      return cache.findRefiningMaterials().map(RefiningMaterialPriceService::prices);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  /** 예열용: 한 탭의 한 페이지를 공식 API에서 새로 받는다. */
  public MarketResult fetchPage(MarketTab tab, int page) {
    return markets.refreshSearch(MarketSource.ACTIVE_LISTING, search(tab, page));
  }

  /** 예열용: 다 모은 스캔을 저장한다. ttl은 예열 주기보다 넉넉하게 준다. */
  public void save(RefiningMaterialScan scan, Duration ttl) {
    cache.saveRefiningMaterials(scan, ttl);
  }

  public static MarketSearch search(MarketTab tab, int page) {
    return new MarketSearch(
        tab.categoryCode(),
        "",
        TIER,
        "",
        "",
        page,
        MarketSearch.Sort.GRADE,
        MarketSearch.SortCondition.ASC);
  }

  static Prices prices(RefiningMaterialScan scan) {
    var units = new HashMap<String, BigDecimal>();
    BigDecimal shard = null;
    for (var item : scan.items()) {
      var price = item.currentMinPrice();
      if (price == null || price.signum() <= 0) continue;
      var pouch = MaterialUnitPriceService.SHARD_POUCHES.get(item.name());
      int contents = pouch == null ? 1 : pouch;
      var unit =
          price.divide(
              BigDecimal.valueOf((long) item.bundleCount() * contents),
              UNIT_SCALE,
              RoundingMode.HALF_UP);
      if (pouch != null) {
        if (shard == null || unit.compareTo(shard) < 0) shard = unit;
        continue;
      }
      units.merge(item.name(), unit, (a, b) -> a.compareTo(b) <= 0 ? a : b);
    }
    if (shard != null) units.put(MaterialUnitPriceService.SHARD, shard);
    return new Prices(scan.observedAt(), units);
  }
}
