package com.loakraken.character.application;

import com.loakraken.character.domain.EquipmentSummary;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 캐릭터 한 명의 장비 요약(빌드 통계 '내 칸', M26 2단계). <b>공식 API를 부르지 않는다</b>: 캐릭터 캐시(최근 검색 5분) → 저장된 마지막 장비 요약
 * 순서로 찾고, 둘 다 없으면 CHARACTER_NOT_CACHED(404)다. 공개 요약(#493)과 같은 순서·같은 404다.
 *
 * <p>처리정지 목록 캐릭터(B5): 저장된 요약을 쓰지 않으므로 기록 없는 캐릭터와 같다. 캐시 경로는 처리방침 6항(직접 검색한 현재 정보)과 같아 기록 없는 캐릭터와 같은
 * 값을 준다. 처리정지 확인은 저장 경로 SQL 안에서만 하고, 그 결과가 응답 필드로 나가지 않는다.
 */
@Service
public class CharacterBuildSourceService {
  private final CharacterCache cache;
  private final EquipmentSummaryPort summaries;

  public CharacterBuildSourceService(CharacterCache cache, EquipmentSummaryPort summaries) {
    this.cache = cache;
    this.summaries = summaries;
  }

  public enum Origin {
    LIVE_CACHE,
    STORED_OBSERVATION
  }

  /**
   * featuresJson은 저장 행과 같은 모양(equipment-summary features). itemLevel은 캐시면 공식 프로필, 저장이면 characters 행
   * 값(통계 모집단과 같은 열)이다.
   */
  public record BuildSource(
      String characterName,
      String serverName,
      String className,
      BigDecimal itemLevel,
      BigDecimal combatPower,
      String arkPassiveTitle,
      Instant observedAt,
      Origin origin,
      String featuresJson) {}

  public BuildSource find(String characterName) {
    var name = new SearchCharacterCommand(characterName).characterName();
    var live = live(name);
    if (live != null) return live;
    try {
      return summaries
          .latest(name)
          .map(
              stored ->
                  new BuildSource(
                      stored.characterName(),
                      stored.serverName(),
                      stored.className(),
                      stored.itemLevel(),
                      stored.combatPower(),
                      stored.arkPassiveTitle(),
                      stored.observedAt(),
                      Origin.STORED_OBSERVATION,
                      stored.featuresJson()))
          .orElseThrow(() -> new CharacterException(CharacterErrorCode.CHARACTER_NOT_CACHED));
    } catch (DataAccessException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_STORAGE_UNAVAILABLE);
    }
  }

  private BuildSource live(String name) {
    CharacterResult result;
    try {
      result = cache.find(name).or(() -> cache.findArmory(name)).orElse(null);
    } catch (CharacterException exception) {
      // 캐시 장애면 저장된 요약으로 넘어간다(공식 호출은 하지 않는다).
      return null;
    }
    if (result == null) return null;
    var profile = result.details().profile();
    var summary = EquipmentSummary.from(profile, result.details().loadout());
    return new BuildSource(
        profile.characterName(),
        profile.serverName(),
        profile.className(),
        profile.itemLevel(),
        profile.combatPower(),
        summary.arkPassiveTitle(),
        result.fetchedAt(),
        Origin.LIVE_CACHE,
        summaries.canonicalFeatures(summary));
  }
}
