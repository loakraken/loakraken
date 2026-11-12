package com.loakraken.market.application;

import com.loakraken.common.api.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MarketQueryService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(MarketQueryService.class);
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final MarketPricesPort prices;
  private final Clock clock;
  private final MarketOptionsPort options;
  private final MarketCache cache;
  private final MarketItemHistoryPort itemHistory;
  private final MarketDailyRecorder daily;
  private final ConcurrentHashMap<PriceKey, CompletableFuture<MarketResult>> priceFlights =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<MarketOptionsResult>> optionFlights =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, CompletableFuture<MarketItemHistoryResult>> historyFlights =
      new ConcurrentHashMap<>();

  @Autowired
  public MarketQueryService(
      MarketPricesPort prices,
      MarketOptionsPort options,
      MarketCache cache,
      Clock clock,
      MarketItemHistoryPort itemHistory,
      MarketDailyRecorder daily) {
    this.prices = prices;
    this.options = options;
    this.cache = cache;
    this.clock = clock;
    this.itemHistory = itemHistory;
    this.daily = daily;
  }

  /** 테스트·기존 호출 호환: 일별 시세를 저장하지 않는 구성. */
  public MarketQueryService(
      MarketPricesPort prices,
      MarketOptionsPort options,
      MarketCache cache,
      Clock clock,
      MarketItemHistoryPort itemHistory) {
    this(prices, options, cache, clock, itemHistory, MarketDailyRecorder.NONE);
  }

  /** 테스트·기존 호출 호환: 상품 이력을 쓰지 않는 구성. */
  public MarketQueryService(
      MarketPricesPort prices, MarketOptionsPort options, MarketCache cache, Clock clock) {
    this(
        prices,
        options,
        cache,
        clock,
        itemId -> {
          throw new UnsupportedOperationException("상품 이력 포트가 없습니다.");
        });
  }

  public MarketResult search(MarketSource source, MarketSearch search) {
    return collect(
        new PriceKey(source, search),
        priceFlights,
        () -> cache.findPrices(source, search),
        () -> {
          var page = prices.fetch(source, search);
          return new MarketResult(source, clock.instant(), search, page);
        },
        cache::savePrices);
  }

  public MarketOptionsResult options() {
    return collect(
        "options",
        optionFlights,
        cache::findOptions,
        () -> {
          var fetched = options.fetchOptions();
          return new MarketOptionsResult(clock.instant(), fetched);
        },
        cache::saveOptions);
  }

  /** 공식 상품 상세(14일 일별 시세). 없는 상품은 MARKET_ITEM_NOT_FOUND이며 캐시하지 않는다. */
  public MarketItemHistoryResult itemHistory(long itemId) {
    return collect(
        itemId,
        historyFlights,
        () -> cache.findItemHistory(itemId),
        () ->
            recordDaily(
                new MarketItemHistoryResult(
                    clock.instant(),
                    itemHistory
                        .fetchHistory(itemId)
                        .orElseThrow(
                            () -> new BusinessException(MarketErrorCode.MARKET_ITEM_NOT_FOUND)))),
        cache::saveItemHistory);
  }

  /** 캐시를 건너뛰고 공식 검색을 다시 받아 저장한다. 예열 전용이다. */
  public MarketResult refreshSearch(MarketSource source, MarketSearch search) {
    var result = new MarketResult(source, clock.instant(), search, prices.fetch(source, search));
    cache.savePrices(result);
    return result;
  }

  /** 캐시를 건너뛰고 공식 상세를 다시 받아 저장한다. 예열 전용이며 모르는 상품은 빈 값이다(캐시하지 않음). */
  public Optional<MarketItemHistoryResult> refreshItemHistory(long itemId) {
    var history = itemHistory.fetchHistory(itemId);
    if (history.isEmpty()) return Optional.empty();
    var result = recordDaily(new MarketItemHistoryResult(clock.instant(), history.get()));
    cache.saveItemHistory(result);
    return Optional.of(result);
  }

  /**
   * 이미 받은 공식 상세(14일)의 완료일 저장을 요청 경로 밖으로 넘긴다(새 공식 호출 없음, 기다리지 않음). 넘기기 실패도 상세 응답을 바꾸지 않는다(로그만, 상품
   * ID와 예외 유형).
   */
  private MarketItemHistoryResult recordDaily(MarketItemHistoryResult result) {
    try {
      daily.record(
          result.history(), LocalDate.ofInstant(result.observedAt(), KST), result.observedAt());
    } catch (RuntimeException exception) {
      log.warn(
          "event=market_daily_handoff_failed itemId={} exceptionType={}",
          result.history().itemId(),
          exception.getClass().getSimpleName());
    }
    return result;
  }

  private <K, V> V collect(
      K key,
      ConcurrentHashMap<K, CompletableFuture<V>> flights,
      Supplier<Optional<V>> read,
      Supplier<V> fetch,
      Consumer<V> save) {
    var cached = read.get();
    if (cached.isPresent()) return cached.get();
    var future = new CompletableFuture<V>();
    var existing = flights.putIfAbsent(key, future);
    if (existing != null) {
      try {
        return existing.join();
      } catch (CompletionException exception) {
        if (exception.getCause() instanceof RuntimeException cause) throw cause;
        if (exception.getCause() instanceof Error cause) throw cause;
        throw exception;
      }
    }
    try {
      var result =
          read.get()
              .orElseGet(
                  () -> {
                    var fetched = fetch.get();
                    save.accept(fetched);
                    return fetched;
                  });
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      flights.remove(key, future);
    }
  }

  private record PriceKey(MarketSource source, MarketSearch search) {}
}
