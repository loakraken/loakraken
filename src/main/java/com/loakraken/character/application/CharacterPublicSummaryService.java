package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 캐릭터 공개 요약(#145). 캐릭터 페이지의 첫 HTML(제목·설명·요약 줄·구조화 데이터)을 봇·사람 구분 없이 같은 값으로 만들기 위한 가벼운 조회다. <b>공식
 * API를 부르지 않는다</b>: 캐릭터 캐시(5분) → DB의 마지막 관측 순서로 찾고, 둘 다 없으면 CHARACTER_NOT_CACHED(404)다. 공개 제외 캐릭터는
 * indexable=false이고 이름 외 값을 주지 않는다.
 */
@Service
public class CharacterPublicSummaryService {
  private final CharacterCache cache;
  private final CharacterPublicSummaryPort stored;

  public CharacterPublicSummaryService(CharacterCache cache, CharacterPublicSummaryPort stored) {
    this.cache = cache;
    this.stored = stored;
  }

  public enum DataSource {
    LIVE_CACHE,
    STORED_OBSERVATION
  }

  public record Summary(
      String characterName,
      String serverName,
      String className,
      Integer characterLevel,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String arkPassiveTitle,
      Instant observedAt,
      DataSource dataSource,
      boolean indexable) {
    Summary notIndexable() {
      return new Summary(
          characterName,
          serverName,
          className,
          characterLevel,
          itemLevel,
          combatPower,
          arkPassiveTitle,
          observedAt,
          dataSource,
          false);
    }

    /** 공개 제외: 이름 외 값과 dataSource까지 null이다(관측 여부를 드러내지 않음, #194). */
    static Summary hidden(String characterName) {
      return new Summary(characterName, null, null, null, null, null, null, null, null, false);
    }
  }

  public Summary summary(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    var summary = live(name);
    if (summary == null) summary = storedSummary(name);
    boolean excluded;
    try {
      // 기록이 없어도 공개 제외 이름이면 404 대신 같은 숨김 응답을 준다. 관측 여부를 드러내지 않는다(#194).
      excluded =
          summary == null
              ? stored.excludedAnyServer(name)
              : stored.excluded(summary.characterName(), summary.serverName());
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
    if (excluded) return Summary.hidden(summary == null ? name : summary.characterName());
    if (summary == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED);
    // 원정대 목록에서만 발견해 전투력 관측이 없으면 내용이 빈약해 색인하지 않는다(#180, PM-백엔드 #177 P2).
    return summary.combatPower() == null ? summary.notIndexable() : summary;
  }

  private Summary live(String name) {
    CharacterResult result;
    try {
      result = cache.find(name).or(() -> cache.findArmory(name)).orElse(null);
    } catch (CharacterException exception) {
      // 캐시 장애면 저장된 관측으로 넘어간다(공식 호출은 하지 않는다).
      return null;
    }
    if (result == null) return null;
    var profile = result.details().profile();
    var passive = result.details().loadout().arkPassive();
    return new Summary(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        profile.characterLevel(),
        profile.itemLevel(),
        profile.combatPower(),
        passive == null ? null : passive.title(),
        result.fetchedAt(),
        DataSource.LIVE_CACHE,
        true);
  }

  private Summary storedSummary(String name) {
    try {
      return stored
          .stored(name)
          .map(
              row ->
                  new Summary(
                      row.characterName(),
                      row.serverName(),
                      row.className(),
                      row.characterLevel(),
                      row.itemLevel(),
                      row.combatPower(),
                      row.arkPassiveTitle(),
                      row.observedAt(),
                      DataSource.STORED_OBSERVATION,
                      true))
          .orElse(null);
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }
}
