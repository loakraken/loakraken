package com.loakraken.character.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CharacterQueryService {
  private final CharacterDetailsPort detailsPort;
  private final CharacterCache cache;
  private final Clock clock;
  private final CharacterSnapshotCommandService snapshots;
  private final CharacterMetrics metrics;
  private final CharacterHistoryService history;
  private final SingleFlight<CharacterResult> searches = new SingleFlight<>();
  private final SingleFlight<CharacterSiblingsResult> siblingQueries = new SingleFlight<>();

  public CharacterQueryService(
      CharacterDetailsPort detailsPort,
      CharacterCache cache,
      Clock clock,
      CharacterSnapshotCommandService snapshots,
      CharacterMetrics metrics,
      CharacterHistoryService history) {
    this.detailsPort = detailsPort;
    this.cache = cache;
    this.clock = clock;
    this.snapshots = snapshots;
    this.metrics = metrics;
    this.history = history;
  }

  public CharacterResult search(SearchCharacterCommand command) {
    boolean success = false;
    try {
      var result =
          command.includeSiblings()
              ? searchWithSiblings(command.characterName())
              : searchArmory(command.characterName());
      success = true;
      return result;
    } finally {
      metrics.search(success);
    }
  }

  public CharacterSiblingsResult siblings(SearchCharacterCommand command) {
    String name = command.characterName();
    var cached = cache.findSiblings(name);
    if (cached.isPresent()) return cached.get();
    return siblingQueries.run(
        name,
        () ->
            cache
                .findSiblings(name)
                .orElseGet(
                    () -> {
                      var siblings = detailsPort.fetchSiblings(name);
                      var now = Instant.now(clock);
                      var result =
                          new CharacterSiblingsResult(name, siblings, now, now.plus(cache.ttl()));
                      cache.saveSiblings(name, result);
                      return result;
                    }));
  }

  private CharacterResult searchWithSiblings(String name) {
    var cached = cache.find(name);
    if (cached.isPresent()) return cached.get();
    return searches.run(
        "summary:" + name,
        () ->
            cache
                .find(name)
                .orElseGet(
                    () -> {
                      var fetched = detailsPort.fetch(name);
                      var fetchedAt = Instant.now(clock);
                      try {
                        snapshots.save(fetched, fetchedAt);
                      } catch (DataAccessException | TransactionException exception) {
                        throw new CharacterException(
                            CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
                      }
                      var result =
                          new CharacterResult(
                              fetched.details(), fetchedAt, Instant.now(clock).plus(cache.ttl()));
                      cache.save(name, result);
                      history.record(fetched.details().profile(), fetchedAt);
                      return result;
                    }));
  }

  private CharacterResult searchArmory(String name) {
    var cached = cache.findArmory(name);
    if (cached.isPresent()) return cached.get();
    return searches.run(
        "armory:" + name,
        () ->
            cache
                .findArmory(name)
                .orElseGet(
                    () -> {
                      var fetched = detailsPort.fetchArmory(name);
                      var now = Instant.now(clock);
                      var result =
                          new CharacterResult(fetched.details(), now, now.plus(cache.ttl()));
                      cache.saveArmory(name, result);
                      history.record(fetched.details().profile(), now);
                      return result;
                    }));
  }
}
