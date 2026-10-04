package com.loakraken.market.application;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class MarketQueryService {
  private final MarketPricesPort prices;
  private final Clock clock;
  private final MarketOptionsPort options;
  private final MarketCache cache;
  private final ConcurrentHashMap<PriceKey, CompletableFuture<MarketResult>> priceFlights =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CompletableFuture<MarketOptionsResult>> optionFlights =
      new ConcurrentHashMap<>();

  public MarketQueryService(
      MarketPricesPort prices, MarketOptionsPort options, MarketCache cache, Clock clock) {
    this.prices = prices;
    this.options = options;
    this.cache = cache;
    this.clock = clock;
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
