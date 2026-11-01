package com.loakraken.character.application;

import com.loakraken.common.lostark.UserKeyContext;
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
  private final ArkGridRealService arkGridReal;
  private final StopListLookups lookups;
  private final SingleFlight<CharacterResult> searches = new SingleFlight<>();
  private final SingleFlight<CharacterSiblingsResult> siblingQueries = new SingleFlight<>();

  /** 아크 그리드 실 효율 계산 없이(단위 테스트). */
  public CharacterQueryService(
      CharacterDetailsPort detailsPort,
      CharacterCache cache,
      Clock clock,
      CharacterSnapshotCommandService snapshots,
      CharacterMetrics metrics,
      CharacterHistoryService history,
      CharacterCardService cards,
      CharacterStopList stopList) {
    this(detailsPort, cache, clock, snapshots, metrics, history, cards, stopList, null);
  }

  public CharacterQueryService(
      CharacterDetailsPort detailsPort,
      CharacterCache cache,
      Clock clock,
      CharacterSnapshotCommandService snapshots,
      CharacterMetrics metrics,
      CharacterHistoryService history,
      CharacterCardService cards,
      CharacterStopList stopList,
      ArkGridRealService arkGridReal) {
    this(
        detailsPort,
        cache,
        clock,
        snapshots,
        metrics,
        history,
        cards,
        stopList,
        arkGridReal,
        StopListLookups.allow());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CharacterQueryService(
      CharacterDetailsPort detailsPort,
      CharacterCache cache,
      Clock clock,
      CharacterSnapshotCommandService snapshots,
      CharacterMetrics metrics,
      CharacterHistoryService history,
      CharacterCardService cards,
      CharacterStopList stopList,
      ArkGridRealService arkGridReal,
      StopListLookups lookups) {
    this.lookups = lookups;
    this.arkGridReal = arkGridReal;
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
      // character.stop-list.lookups=HIDE면 제외 목록 캐릭터는 없는 캐릭터와 같다(기본 ALLOW는 그대로).
      var profile = result.details().profile();
      lookups.requireVisible(profile.characterName(), profile.serverName());
      success = true;
      return result;
    } finally {
      metrics.search(success);
    }
  }

  public CharacterSiblingsResult siblings(SearchCharacterCommand command) {
    var result = loadSiblings(command.characterName());
    if (lookups.mode() == StopListLookups.Mode.ALLOW) return result;
    // HIDE: 찾은 캐릭터가 목록에 있으면 없는 캐릭터와 같고, 목록 캐릭터는 원정대에서 뺀다.
    for (var sibling : result.siblings())
      if (command.characterName().equalsIgnoreCase(sibling.characterName()))
        lookups.requireVisible(sibling.characterName(), sibling.serverName());
    return new CharacterSiblingsResult(
        result.characterName(),
        lookups.visible(result.siblings()),
        result.fetchedAt(),
        result.refreshAvailableAt());
  }

  private CharacterSiblingsResult loadSiblings(String name) {
    var cached = cache.findSiblings(name);
    if (cached.isPresent()) return cached.get();
    rejectMissing(name);
    return siblingQueries.run(
        flight(name),
        () ->
            cache
                .findSiblings(name)
                .orElseGet(
                    () -> {
                      var siblings = remembering(name, () -> detailsPort.fetchSiblings(name));
                      var now = Instant.now(clock);
                      var result =
                          new CharacterSiblingsResult(name, siblings, now, now.plus(cache.ttl()));
                      if (shared()) cache.saveSiblings(name, result);
                      return result;
                    }));
  }

  private CharacterResult searchWithSiblings(String name) {
    var cached = cache.find(name);
    if (cached.isPresent()) return cached.get();
    rejectMissing(name);
    return searches.run(
        flight("summary:" + name),
        () ->
            cache
                .find(name)
                .orElseGet(
                    () -> {
                      var fetched = remembering(name, () -> detailsPort.fetch(name));
                      var fetchedAt = Instant.now(clock);
                      var profile = fetched.details().profile();
                      // 처리정지 목록의 캐릭터는 응답만 주고 기록하지 않는다(#351). 사용자 키로 받은 응답도 기록하지 않는다.
                      boolean recordable = false;
                      if (shared()) {
                        try {
                          recordable =
                              !stopList.contains(profile.characterName(), profile.serverName());
                          if (recordable) snapshots.save(fetched, fetchedAt);
                        } catch (DataAccessException | TransactionException exception) {
                          throw new CharacterException(
                              CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
                        }
                      }
                      var result =
                          new CharacterResult(
                              fetched.details(),
                              fetchedAt,
                              Instant.now(clock).plus(cache.ttl()),
                              fetched.krakenScoreInput(),
                              arkGridReal(fetched));
                      if (shared()) cache.save(name, result);
                      if (recordable) {
                        history.record(profile, fetchedAt);
                        cards.record(fetched.details(), fetched.krakenScoreInput(), fetchedAt);
                      }
                      return result;
                    }));
  }

  private CharacterResult searchArmory(String name) {
    var cached = cache.findArmory(name);
    if (cached.isPresent()) return cached.get();
    rejectMissing(name);
    return searches.run(
        flight("armory:" + name),
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
                              fetched.krakenScoreInput(),
                              arkGridReal(fetched));
                      if (!shared()) return result;
                      cache.saveArmory(name, result);
                      var profile = fetched.details().profile();
                      if (stopList.recordable(profile.characterName(), profile.serverName())) {
                        history.record(profile, now);
                        cards.record(fetched.details(), fetched.krakenScoreInput(), now);
                      }
                      return result;
                    }));
  }

  /** 아크 그리드 실 효율(#520): 조회 때 한 번 계산(응답 때는 계산하지 않음). 실패해도 조회는 그대로 성공한다. */
  private ArkGridRealEfficiency arkGridReal(FetchedCharacter fetched) {
    if (arkGridReal == null) return null;
    try {
      return arkGridReal.compute(fetched.details(), fetched.krakenScoreInput());
    } catch (RuntimeException exception) {
      return ArkGridRealEfficiency.unsupported(ArkGridRealService.UNAVAILABLE, "실 효율을 계산하지 못했습니다.");
    }
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
      if (exception.errorCode() == CharacterErrorCode.CHARACTER_NOT_FOUND && shared())
        cache.markMissing(name);
      throw exception;
    }
  }

  /**
   * 사용자 공식 API 키(BYOK)로 받는 중이 아니면 true. 사용자 키로 받은 응답은 공용 캐시·없는 캐릭터 기억·DB(스냅샷·관측 이력·카드)에 남기지 않는다(그
   * 사용자의 요청에만 쓴다).
   */
  private static boolean shared() {
    return !UserKeyContext.applied();
  }

  /** 같은 이름의 동시 요청 묶기 키. 사용자 키 요청은 같은 키끼리만 묶는다(한 사람의 키로 다른 사람 요청을 채우지 않게). */
  private static String flight(String key) {
    var id = UserKeyContext.id();
    return id == null ? key : "byok:" + id + ":" + key;
  }
}
