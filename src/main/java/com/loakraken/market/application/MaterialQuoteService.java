package com.loakraken.market.application;

import com.loakraken.market.domain.MaterialPurchaseCost;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
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
                MaterialPurchaseCost.covered()));
        continue;
      }
      var result =
          pages.computeIfAbsent(
              item.search(), search -> markets.search(MarketSource.ACTIVE_LISTING, search));
      var matches =
          result.page().items().stream().filter(price -> price.id() == item.itemId()).toList();
      if (matches.size() != 1) {
        lines.add(
            new MaterialQuote.Line(
                item,
                matches.isEmpty()
                    ? MaterialQuote.Status.ITEM_NOT_IN_PAGE
                    : MaterialQuote.Status.AMBIGUOUS_ITEM,
                result.observedAt(),
                null,
                null,
                null,
                null));
        continue;
      }
      var price = matches.getFirst();
      var selected = command.priceBasis().select(price);
      var status =
          price.tradeRemainCount() != null
              ? MaterialQuote.Status.TRADE_RESTRICTED_ITEM
              : selected == null
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
}
