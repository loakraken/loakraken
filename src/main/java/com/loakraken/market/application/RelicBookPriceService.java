package com.loakraken.market.application;

import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 유물 각인서 시세(L30, 스펙업 각인서 비용). 읽기는 예열이 저장한 캐시만 쓰고 공식 API를 부르지 않는다. 공식 거래소 품목 이름은 "유물 {각인} 각인서"이고
 * 띄어쓰기가 각인 이름과 다른 품목이 있어(예: "실드관통") 공백을 빼고 맞춘다.
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
                                .divide(
                                    BigDecimal.valueOf(item.bundleCount()), 0, RoundingMode.CEILING)
                                .longValue(),
                            value.observedAt())));
  }

  /**
   * 캐시에 있으면 그 각인의 유물 각인서 공식 아이콘 주소. 공식 각인 응답(아크 패시브 각인)에는 아이콘이 없어 스펙업 각인 줄 그림으로 쓴다. 없거나 캐시를 읽지 못하면
   * 빈 값(공식 호출 없음). 시세가 없는 매물이어도 아이콘은 쓴다.
   */
  public Optional<String> icon(String engravingName) {
    if (engravingName == null || engravingName.isBlank()) return Optional.empty();
    Optional<RelicBookScan> scan;
    try {
      scan = cache.findRelicBooks();
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
    var target = compact(GRADE + " " + engravingName + " 각인서");
    var fromScan =
        scan.flatMap(
            value ->
                value.items().stream()
                    .filter(item -> target.equals(compact(item.name())))
                    .filter(item -> item.grade() == null || GRADE.equals(item.grade()))
                    .map(MarketPrice::icon)
                    .filter(icon -> icon != null && !icon.isBlank())
                    .findFirst());
    if (fromScan.isPresent()) return fromScan;
    // 시세 스캔이 없거나(예열 꺼짐·만료) 그 품목이 없으면 마지막으로 본 아이콘(만료 없음).
    try {
      return Optional.ofNullable(cache.findRelicBookIcons().get(target));
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  /** 공식 이미지 서버 주소만 내보낸다. */
  static final String OFFICIAL_CDN = "https://cdn-lostark.game.onstove.com/";

  /**
   * 각인 그림 목록: 공백을 뺀 각인 이름 → 유물 각인서 공식 아이콘 주소(이름순). 마지막으로 본 아이콘(만료 없음)에 지금 시세 스캔을 덮어 쓴다. 공식 이미지 서버
   * 주소만 담는다. 캐시를 읽지 못하면 읽은 만큼만(공식 호출 없음).
   */
  public java.util.SortedMap<String, String> icons() {
    var byItem = new java.util.HashMap<String, String>();
    try {
      byItem.putAll(cache.findRelicBookIcons());
    } catch (RuntimeException exception) {
      // 마지막으로 본 아이콘을 읽지 못하면 시세 스캔만 쓴다.
    }
    try {
      cache
          .findRelicBooks()
          .ifPresent(
              scan -> {
                for (var item : scan.items())
                  if ((item.grade() == null || GRADE.equals(item.grade()))
                      && item.icon() != null
                      && !item.icon().isBlank()) byItem.put(compact(item.name()), item.icon());
              });
    } catch (RuntimeException exception) {
      // 시세 스캔을 읽지 못하면 마지막으로 본 아이콘만 쓴다.
    }
    var result = new java.util.TreeMap<String, String>();
    for (var entry : byItem.entrySet()) {
      var engraving = engraving(entry.getKey());
      if (engraving != null && entry.getValue().startsWith(OFFICIAL_CDN))
        result.put(engraving, entry.getValue());
    }
    return result;
  }

  /** 공백을 뺀 품목 이름 "유물{각인}각인서" → "{각인}". 모양이 다르면 null. */
  private static String engraving(String compactItemName) {
    var prefix = compact(GRADE);
    var suffix = "각인서";
    if (!compactItemName.startsWith(prefix) || !compactItemName.endsWith(suffix)) return null;
    var name =
        compactItemName.substring(prefix.length(), compactItemName.length() - suffix.length());
    return name.isEmpty() ? null : name;
  }

  /** 캐시된 유물 각인서 시세의 관측 시각. 캐시가 없거나 읽지 못하면 빈 값(공식 호출 없음). 결과 캐시 키에 넣어 시세가 바뀌면 다시 계산하게 한다. */
  public Optional<Instant> observedAt() {
    try {
      return cache.findRelicBooks().map(RelicBookScan::observedAt);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  /** 예열용: 한 페이지를 공식 API에서 새로 받는다. */
  public MarketResult fetchPage(int page) {
    return markets.refreshSearch(MarketSource.ACTIVE_LISTING, search(page));
  }

  /** 예열용: 다 모은 스캔을 저장한다. ttl은 예열 주기보다 넉넉하게 준다. */
  public void save(RelicBookScan scan, Duration ttl) {
    cache.saveRelicBooks(scan, ttl);
    var icons = new java.util.HashMap<String, String>();
    for (var item : scan.items())
      if ((item.grade() == null || GRADE.equals(item.grade()))
          && item.icon() != null
          && !item.icon().isBlank()) icons.put(compact(item.name()), item.icon());
    cache.saveRelicBookIcons(icons);
  }

  public static MarketSearch search(int page) {
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
