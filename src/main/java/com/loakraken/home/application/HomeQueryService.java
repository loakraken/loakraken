package com.loakraken.home.application;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 공식 일정·소식을 캐시와 동시 요청 병합으로 제공한다. */
@Service
public class HomeQueryService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(HomeQueryService.class);
  private final HomeSourcePort source;
  private final HomeCache cache;
  private final Clock clock;
  private final java.util.List<NewsObserver> observers;
  private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
      new ConcurrentHashMap<>();

  public HomeQueryService(
      HomeSourcePort source, HomeCache cache, Clock clock, java.util.List<NewsObserver> observers) {
    this.source = source;
    this.cache = cache;
    this.clock = clock;
    this.observers = java.util.List.copyOf(observers);
  }

  public HomeCache.CalendarSnapshot calendar() {
    return collect(
        "calendar",
        cache::findCalendar,
        () -> new HomeCache.CalendarSnapshot(clock.instant(), source.fetchCalendar()),
        cache::saveCalendar);
  }

  public HomeCache.NewsSnapshot news() {
    return collect(
        "news",
        cache::findNews,
        () -> {
          var snapshot = new HomeCache.NewsSnapshot(clock.instant(), source.fetchNews());
          // 관찰자 실패는 소식 응답을 깨지 않는다(PM-백엔드 #126 P2).
          for (var observer : observers) {
            try {
              observer.observed(snapshot);
            } catch (RuntimeException exception) {
              log.warn("소식 관찰자가 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
            }
          }
          return snapshot;
        },
        cache::saveNews);
  }

  public Clock clock() {
    return clock;
  }

  @SuppressWarnings("unchecked")
  private <V> V collect(
      String key, Supplier<Optional<V>> read, Supplier<V> fetch, Consumer<V> save) {
    var cached = read.get();
    if (cached.isPresent()) return cached.get();
    var future = new CompletableFuture<Object>();
    var existing = flights.putIfAbsent(key, future);
    if (existing != null) {
      try {
        return (V) existing.join();
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
}
