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
  private final CharacterCardService cards;
  private final CharacterStopList stopList;
  private final SingleFlight<CharacterResult> searches = new SingleFlight<>();
  private final SingleFlight<CharacterSiblingsResult> siblingQueries = new SingleFlight<>();

  public CharacterQueryService(
      CharacterDetailsPort detailsPort,
      CharacterCache cache,
      Clock clock,
      CharacterSnapshotCommandService snapshots,
      CharacterMetrics metrics,
      CharacterHistoryService history,
      CharacterCardService cards,
      CharacterStopList stopList) {
    this.detailsPort = detailsPort;
    this.cache = cache;
    this.clock = clock;
    this.snapshots = snapshots;
    this.metrics = metrics;
    this.history = history;
    this.cards = cards;
    this.stopList = stopList;
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
    rejectMissing(name);
    return siblingQueries.run(
        name,
        () ->
            cache
                .findSiblings(name)
                .orElseGet(
                    () -> {
                      var siblings = remembering(name, () -> detailsPort.fetchSiblings(name));
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
    rejectMissing(name);
    return searches.run(
        "summary:" + name,
        () ->
            cache
                .find(name)
                .orElseGet(
                    () -> {
                      var fetched = remembering(name, () -> detailsPort.fetch(name));
                      var fetchedAt = Instant.now(clock);
                      var profile = fetched.details().profile();
                      // 처리정지 목록의 캐릭터는 응답만 주고 기록하지 않는다(#351).
                      boolean recordable;
                      try {
                        recordable =
                            !stopList.contains(profile.characterName(), profile.serverName());
                        if (recordable) snapshots.save(fetched, fetchedAt);
                      } catch (DataAccessException | TransactionException exception) {
                        throw new CharacterException(
                            CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
                      }
                      var result =
                          new CharacterResult(
                              fetched.details(),
                              fetchedAt,
                              Instant.now(clock).plus(cache.ttl()),
                              fetched.krakenScoreInput());
                      cache.save(name, result);
                      if (recordable) {
                        history.record(profile, fetchedAt);
                        cards.record(fetched.details(), fetchedAt);
                      }
                      return result;
                    }));
  }

  private CharacterResult searchArmory(String name) {
    var cached = cache.findArmory(name);
    if (cached.isPresent()) return cached.get();
    rejectMissing(name);
    return searches.run(
        "armory:" + name,
        () ->
            cache
                .findArmory(name)
                .orElseGet(
                    () -> {
                      var fetched = remembering(name, () -> detailsPort.fetchArmory(name));
                      var now = Instant.now(clock);
                      var result =
                          new CharacterResult(
                              fetched.details(),
                              now,
                              now.plus(cache.ttl()),
                              fetched.krakenScoreInput());
                      cache.saveArmory(name, result);
                      var profile = fetched.details().profile();
                      if (stopList.recordable(profile.characterName(), profile.serverName())) {
                        history.record(profile, now);
                        cards.record(fetched.details(), now);
                      }
                      return result;
                    }));
  }

  /** 최근 공식 API가 없다고 답한 이름은 공식 호출 없이 바로 404다(#108). */
  private void rejectMissing(String name) {
    if (cache.isMissing(name)) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND);
  }

  /** 공식 "없는 캐릭터" 응답만 기억한다. 한도·타임아웃·서버 오류는 다른 코드라 기억하지 않는다. */
  private <T> T remembering(String name, java.util.function.Supplier<T> fetch) {
    try {
      return fetch.get();
    } catch (CharacterException exception) {
      if (exception.errorCode() == CharacterErrorCode.CHARACTER_NOT_FOUND) cache.markMissing(name);
      throw exception;
    }
  }
}
