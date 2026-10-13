package com.loakraken.character.application;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 캐릭터 공개 요약. 캐릭터 페이지의 첫 HTML(제목·설명·요약 줄·구조화 데이터)을 봇·사람 구분 없이 같은 값으로 만들기 위한 가벼운 조회다. <b>공식 API를 부르지
 * 않는다</b>: 캐릭터 캐시(5분) → DB의 마지막 관측 순서로 찾고, 둘 다 없으면 CHARACTER_NOT_CACHED(404)다. 처리정지 목록 캐릭터는 저장된 관측을
 * 쓰지 않아 기록 없는 캐릭터와 같은 응답이다(#363). 색인 가능 값도 저장된 관측 기준이라 둘이 같다.
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
  }

  /**
   * 색인 가능(indexable)은 <b>저장된 관측이 있을 때만</b> true다. 최근 검색 캐시로만 찾은 캐릭터는 값은 주되 색인하지 않는다. 처리정지 목록 캐릭터는
   * 저장된 관측을 쓰지 않으므로 기록 없는 캐릭터와 색인 값까지 같다(B5, 보안 3회차). 처리정지 확인은 저장된 관측을 쓸지 정할 때만 한다.
   */
  public Summary summary(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    var summary = live(name);
    if (summary != null) {
      // 저장소 장애면 저장된 관측이 없는 것과 같게(색인 안 함) 둔다. 값은 캐시 그대로.
      Summary recorded;
      try {
        recorded = recorded(name);
      } catch (CharacterException exception) {
        recorded = null;
      }
      boolean sameCharacter =
          recorded != null
              && recorded.serverName() != null
              && recorded.serverName().equals(summary.serverName());
      if (!sameCharacter) return summary.notIndexable();
    } else {
      summary = recorded(name);
    }
    if (summary == null) throw new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED);
    // 원정대 목록에서만 발견해 전투력 관측이 없으면 내용이 빈약해 색인하지 않는다(#180, PM-백엔드 #177 P2).
    return summary.combatPower() == null ? summary.notIndexable() : summary;
  }

  /** 쓸 수 있는 저장된 관측. 처리정지 목록 캐릭터는 기록이 없는 것과 같다(#363). 저장소 장애는 503. */
  private Summary recorded(String name) {
    var summary = storedSummary(name);
    try {
      if (summary != null && stored.excluded(summary.characterName(), summary.serverName()))
        return null;
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
    return summary;
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
