package com.loakraken.market.application;

import com.loakraken.market.domain.MarketBoardRules;
import com.loakraken.market.domain.MarketItemHistory;
import com.loakraken.market.domain.MarketPrice;
import com.loakraken.market.domain.MarketTab;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;

/**
 * 시세 보드. 기본·고가·저가는 공식 정렬 한 페이지(검색 캐시 공유), 상승·하락은 탭 전체를 순차 스캔해 등락률로 정렬한다.
 *
 * <p>전체 스캔은 탭+티어 단위로 캐시하고 같은 스캔 동시 요청을 합친다. 페이지 상한(MAX_SCAN_PAGES)을 넘는 탭은 complete=false다.
 */
@Service
public class MarketBoardService {
  public static final int PAGE_SIZE = 10;
  public static final int MAX_SCAN_PAGES = 10;
  static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private final MarketQueryService markets;
  private final MarketCache cache;
  private final ConcurrentHashMap<ScanKey, CompletableFuture<MarketBoardScan>> scans =
      new ConcurrentHashMap<>();
  // 서로 다른 탭·티어 스캔이 겹쳐 공유 호출 예산을 몰아 쓰지 않도록 전체 스캔은 한 번에 하나만 실행한다.
  private final ReentrantLock scanLock = new ReentrantLock(true);

  public MarketBoardService(MarketQueryService markets, MarketCache cache) {
    this.markets = markets;
    this.cache = cache;
  }

  public MarketBoard board(MarketTab tab, Integer tier, MarketBoardRules.Sort sort, int page) {
    Integer effectiveTier = tier == null ? tab.defaultTier() : tier;
    if (!sort.scansWholeTab()) {
      var result =
          markets.search(MarketSource.ACTIVE_LISTING, search(tab, effectiveTier, sort, page));
      var items = result.page().items();
      return new MarketBoard(
          tab,
          effectiveTier,
          sort,
          page,
          result.observedAt(),
          result.page().totalCount(),
          items.size(),
          true,
          rows(items));
    }
    var scan = scan(tab, effectiveTier);
    var sorted = MarketBoardRules.sortByChange(scan.items(), sort);
    int from = Math.min(sorted.size(), (page - 1) * PAGE_SIZE);
    int to = Math.min(sorted.size(), from + PAGE_SIZE);
    return new MarketBoard(
        tab,
        effectiveTier,
        sort,
        page,
        scan.observedAt(),
        scan.totalItems(),
        scan.items().size(),
        scan.complete(),
        rows(sorted.subList(from, to)));
  }

  private MarketBoardScan scan(MarketTab tab, Integer tier) {
    var cached = cache.findBoardScan(tab, tier);
    if (cached.isPresent()) return cached.get();
    var key = new ScanKey(tab, tier);
    var future = new CompletableFuture<MarketBoardScan>();
    var existing = scans.putIfAbsent(key, future);
    if (existing != null) return join(existing);
    try {
      var result =
          cache
              .findBoardScan(tab, tier)
              .orElseGet(
                  () -> {
                    var fresh = fetchAllSerialized(tab, tier);
                    cache.saveBoardScan(fresh);
                    return fresh;
                  });
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      scans.remove(key, future);
    }
  }

  private MarketBoardScan fetchAllSerialized(MarketTab tab, Integer tier) {
    scanLock.lock();
    try {
      // 대기하는 동안 다른 요청이 같은 스캔을 저장했을 수 있다.
      return cache.findBoardScan(tab, tier).orElseGet(() -> fetchAll(tab, tier));
    } finally {
      scanLock.unlock();
    }
  }

  /** 기본 정렬로 1페이지부터 순차 조회한다. 동시 호출 예산을 독점하지 않도록 병렬화하지 않는다. */
  private MarketBoardScan fetchAll(MarketTab tab, Integer tier) {
    var first =
        markets.search(
            MarketSource.ACTIVE_LISTING, search(tab, tier, MarketBoardRules.Sort.DEFAULT, 1));
    int total = first.page().totalCount();
    int pages = Math.min(MAX_SCAN_PAGES, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    var items = new ArrayList<>(first.page().items());
    Instant observedAt = first.observedAt();
    for (int page = 2; page <= pages; page++) {
      var next =
          markets.search(
              MarketSource.ACTIVE_LISTING, search(tab, tier, MarketBoardRules.Sort.DEFAULT, page));
      items.addAll(next.page().items());
      if (next.observedAt().isBefore(observedAt)) observedAt = next.observedAt();
    }
    return new MarketBoardScan(tab, tier, observedAt, total, distinct(items));
  }

  private static List<MarketPrice> distinct(List<MarketPrice> items) {
    // 페이지를 넘기는 사이 순위가 바뀌면 같은 품목이 두 번 나올 수 있다.
    var seen = new java.util.LinkedHashMap<Long, MarketPrice>();
    for (var item : items) seen.putIfAbsent(item.id(), item);
    return List.copyOf(seen.values());
  }

  private List<MarketBoard.Row> rows(List<MarketPrice> items) {
    Map<Long, MarketItemHistoryResult> histories =
        items.isEmpty()
            ? Map.of()
            : cache.findItemHistories(items.stream().map(MarketPrice::id).toList());
    return items.stream().map(item -> row(item, histories.get(item.id()))).toList();
  }

  private static MarketBoard.Row row(MarketPrice item, MarketItemHistoryResult history) {
    var changeDay = MarketBoardRules.changeDay(item);
    var variant = history == null ? null : variant(history.history(), item);
    if (variant == null) return new MarketBoard.Row(item, changeDay, null, List.of(), false);
    LocalDate today = LocalDate.ofInstant(history.observedAt(), KST);
    var summary = variant.summary(today);
    var complete = variant.completeDays(today);
    var week = complete.subList(0, Math.min(MarketItemHistory.SEVEN_DAYS, complete.size()));
    var spark = new ArrayList<MarketBoard.SparkPoint>();
    for (int i = week.size() - 1; i >= 0; i--)
      spark.add(new MarketBoard.SparkPoint(week.get(i).date(), week.get(i).averagePrice()));
    return new MarketBoard.Row(
        item,
        changeDay,
        MarketBoardRules.change(item.currentMinPrice(), summary.sevenDayAveragePrice()),
        List.copyOf(spark),
        true);
  }

  /** 묶음 수와 거래 가능 횟수가 같은 변형을 우선하고, 없으면 묶음 수만 같은 변형을 쓴다. */
  private static MarketItemHistory.Variant variant(MarketItemHistory history, MarketPrice item) {
    MarketItemHistory.Variant sameBundle = null;
    for (var variant : history.variants()) {
      if (!Objects.equals(variant.bundleCount(), item.bundleCount())) continue;
      if (Objects.equals(variant.tradeRemainCount(), item.tradeRemainCount())) return variant;
      if (sameBundle == null) sameBundle = variant;
    }
    return sameBundle;
  }

  private static MarketSearch search(
      MarketTab tab, Integer tier, MarketBoardRules.Sort sort, int page) {
    var officialSort =
        sort == MarketBoardRules.Sort.HIGH || sort == MarketBoardRules.Sort.LOW
            ? MarketSearch.Sort.CURRENT_MIN_PRICE
            : MarketSearch.Sort.GRADE;
    var condition =
        sort == MarketBoardRules.Sort.LOW
            ? MarketSearch.SortCondition.ASC
            : MarketSearch.SortCondition.DESC;
    return new MarketSearch(tab.categoryCode(), "", tier, "", "", page, officialSort, condition);
  }

  private static <T> T join(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException exception) {
      if (exception.getCause() instanceof RuntimeException cause) throw cause;
      if (exception.getCause() instanceof Error cause) throw cause;
      throw exception;
    }
  }

  private record ScanKey(MarketTab tab, Integer tier) {}
}
