package com.loakraken.character.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 전투정보실 페이지에서 뽑은 값의 캐시(#531·#532). 원문과 식별 값은 저장하지 않는다. */
public interface ArmoryPageStorePort {
  /**
   * @throws CharacterException CHARACTER_CACHE_UNAVAILABLE — 캐시를 확인할 수 없음(이때는 공식 페이지를 부르지 않는다)
   */
  Optional<Entry> find(String characterName);

  void save(String characterName, Entry entry, Duration ttl);

  /**
   * @throws CharacterException CHARACTER_CACHE_UNAVAILABLE — 캐시를 확인할 수 없음(이때는 공식 요청을 하지 않는다)
   */
  Optional<CodeEntry> findCode(String characterName);

  void saveCode(String characterName, CodeEntry entry, Duration ttl);

  /** lifeSkills가 null이면 실패 기록(실패 캐시 시간 동안 다시 부르지 않음). */
  record Entry(Instant fetchedAt, List<ArmoryPagePort.LifeSkill> lifeSkills) {
    public static Entry failed() {
      return new Entry(null, null);
    }

    public boolean failure() {
      return lifeSkills == null;
    }
  }

  /** skillCode가 null이면 실패 기록. */
  record CodeEntry(Instant fetchedAt, String skillCode) {
    public static CodeEntry failed() {
      return new CodeEntry(null, null);
    }

    public boolean failure() {
      return skillCode == null;
    }
  }
}
