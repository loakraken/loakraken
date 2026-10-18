package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MaterialPriceBasis;
import com.loakraken.market.domain.MaterialPurchaseCost;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MaterialQuoteService {
  private final MarketQueryService markets;
  private final Clock clock;

  public MaterialQuoteService(MarketQueryService markets, Clock clock) {
    this.markets = markets;
    this.clock = clock;
  }

  public MaterialQuote quote(MaterialQuoteCommand command) {
    // 견적 내 같은 검색은 캐시 만료 경계에서도 동일한 가격 관측을 사용한다.
    var pages = new HashMap<MarketSearch, MarketResult>();
    var lines = new ArrayList<MaterialQuote.Line>();
    for (var item : command.items()) {
      if (item.missingQuantity() == 0) {
        lines.add(
            new MaterialQuote.Line(
                item,
                MaterialQuote.Status.COVERED_BY_INVENTORY,
                null,
                null,
                null,
                null,
                null,
                MaterialPurchaseCost.covered()));
        continue;
      }
      var result =
          pages.computeIfAbsent(
              item.search(), search -> markets.search(MarketSource.ACTIVE_LISTING, search));
      var matches =
          result.page().items().stream().filter(price -> price.id() == item.itemId()).toList();
      if (matches.isEmpty()) {
        lines.add(
            new MaterialQuote.Line(
                item,
                MaterialQuote.Status.ITEM_NOT_IN_PAGE,
                result.observedAt(),
                null,
                null,
                null,
                null,
                null));
        continue;
      }
      // 구매 후 거래 횟수 제한 상품(TradeRemainCount 있음 — 각인서는 늘 있음)도 쓰려고 사는 비용이라 넣는다. 같은 ID가 여러 줄(거래 가능
      // 횟수·묶음이
      // 다른 변형)이면 선택한 가격 기준으로 가장 싸게 사는 줄을 쓴다(#617).
      var price = cheapest(matches, command.priceBasis(), item.missingQuantity());
      var selected = command.priceBasis().select(price);
      var status =
          selected == null
              ? MaterialQuote.Status.PRICE_UNAVAILABLE
              : selected.signum() == 0
                  ? MaterialQuote.Status.ZERO_PRICE
                  : MaterialQuote.Status.PRICED;
      var purchase =
          status == MaterialQuote.Status.PRICED
              ? MaterialPurchaseCost.calculate(
                  item.missingQuantity(), price.bundleCount(), selected)
              : null;
      lines.add(
          new MaterialQuote.Line(
              item,
              status,
              result.observedAt(),
              price.name(),
              price.bundleCount(),
              price.tradeRemainCount(),
              selected,
              purchase));
    }
    boolean complete = lines.stream().allMatch(line -> line.purchase() != null);
    var known =
        lines.stream()
            .filter(line -> line.purchase() != null)
            .map(line -> line.purchase().costGold())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new MaterialQuote(
        command.priceBasis(), clock.instant(), complete, known, complete ? known : null, lines);
  }

  /**
   * 같은 상품 ID 줄 중 부족 수량을 살 때 드는 골드가 가장 적은 줄. 같으면 거래 제한 없는 줄(null), 그다음 거래 가능 횟수가 많은 줄. 가격이 있는 줄이 없으면
   * 0원 줄(ZERO_PRICE), 그것도 없으면 첫 줄(PRICE_UNAVAILABLE).
   */
  static MarketPrice cheapest(
      List<MarketPrice> matches, MaterialPriceBasis basis, long missingQuantity) {
    Comparator<MarketPrice> order =
        Comparator.comparing(
                (MarketPrice price) ->
                    MaterialPurchaseCost.calculate(
                            missingQuantity, price.bundleCount(), basis.select(price))
                        .costGold())
            .thenComparing(
                MarketPrice::tradeRemainCount,
                Comparator.nullsFirst(Comparator.<Integer>reverseOrder()));
    var priced =
        matches.stream()
            .filter(price -> basis.select(price) != null && basis.select(price).signum() > 0)
            .min(order);
    if (priced.isPresent()) return priced.get();
    return matches.stream()
        .filter(price -> basis.select(price) != null)
        .findFirst()
        .orElse(matches.getFirst());
  }
}
