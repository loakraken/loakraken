package com.loakraken.character.application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class SingleFlight<T> {
  private final ConcurrentHashMap<String, CompletableFuture<T>> flights = new ConcurrentHashMap<>();

  T run(String key, Supplier<T> loader) {
    var future = new CompletableFuture<T>();
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
      var result = loader.get();
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
