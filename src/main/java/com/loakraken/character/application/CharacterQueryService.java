package com.loakraken.character.application;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class CharacterQueryService {
  private final CharacterDetailsPort detailsPort;
  private final CharacterCache cache;
  private final Clock clock;
  private final ConcurrentHashMap<String, CompletableFuture<CharacterResult>> flights =
      new ConcurrentHashMap<>();

  public CharacterQueryService(
      CharacterDetailsPort detailsPort, CharacterCache cache, Clock clock) {
    this.detailsPort = detailsPort;
    this.cache = cache;
    this.clock = clock;
  }

  public CharacterResult search(SearchCharacterCommand command) {
    String name = command.characterName();
    var cached = cache.find(name);
    if (cached.isPresent()) return cached.get();
    var future = new CompletableFuture<CharacterResult>();
    var existing = flights.putIfAbsent(name, future);
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
          cache
              .find(name)
              .orElseGet(
                  () -> {
                    var details = detailsPort.fetch(name);
                    var fetched = new CharacterResult(details, Instant.now(clock));
                    cache.save(name, fetched);
                    return fetched;
                  });
      future.complete(result);
      return result;
    } catch (RuntimeException | Error exception) {
      future.completeExceptionally(exception);
      throw exception;
    } finally {
      flights.remove(name, future);
    }
  }
}
