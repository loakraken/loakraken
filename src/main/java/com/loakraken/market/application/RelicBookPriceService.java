package com.loakraken.market.application;

import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 유물 각인서 시세(L30, 스펙업 각인서 비용). 읽기는 예열이 저장한 캐시만 쓰고 공식 API를 부르지 않는다. 공식 거래소 품목 이름은 "유물 {각인} 각인서"이고
 * 띄어쓰기가 각인 이름과 다를 수 있어(예: "실드관통") 공백을 빼고 맞춘다.
 */
@Service
public class RelicBookPriceService {
  public static final String GRADE = "유물";

  private final MarketQueryService markets;
  private final MarketCache cache;

  public RelicBookPriceService(MarketQueryService markets, MarketCache cache) {
    this.markets = markets;
    this.cache = cache;
  }

  /** 1권 즉시 구매 최저가(묶음이면 1권 값, 올림)와 관측 시각. */
  public record Price(long unitPrice, Instant observedAt) {}

  /** 캐시에 있으면 그 각인의 유물 각인서 1권 값. 없거나 캐시를 읽지 못하면 빈 값(공식 호출 없음). */
  public Optional<Price> price(String engravingName) {
    if (engravingName == null || engravingName.isBlank()) return Optional.empty();
    Optional<RelicBookScan> scan;
    try {
      scan = cache.findRelicBooks();
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
    var target = compact(GRADE + " " + engravingName + " 각인서");
    return scan.flatMap(
        value ->
            value.items().stream()
                .filter(item -> target.equals(compact(item.name())))
                .filter(item -> item.grade() == null || GRADE.equals(item.grade()))
                .filter(item -> item.currentMinPrice() != null)
                .findFirst()
                .map(
                    item ->
                        new Price(
                            item.currentMinPrice()
                                .divide(BigDecimal.valueOf(item.bundleCount()), 0, RoundingMode.CEILING)
                                .longValue(),
                            value.observedAt())));
  }

  /** 예열용: 한 페이지를 공식 API에서 새로 받는다. */
  public MarketResult fetchPage(int page) {
    return markets.refreshSearch(MarketSource.ACTIVE_LISTING, search(page));
  }

  /** 예열용: 다 모은 스캔을 저장한다. ttl은 예열 주기보다 넉넉하게 준다. */
  public void save(RelicBookScan scan, Duration ttl) {
    cache.saveRelicBooks(scan, ttl);
  }

  static MarketSearch search(int page) {
    return new MarketSearch(
        MarketTab.ENGRAVING_BOOK.categoryCode(),
        "",
        null,
        GRADE,
        "",
        page,
        MarketSearch.Sort.GRADE,
        MarketSearch.SortCondition.DESC);
  }

  private static String compact(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "");
  }
}
