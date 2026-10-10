package com.loakraken.character.application;

import java.time.Duration;
import java.util.Optional;

public interface CharacterCache {
  /** 새로 저장하는 값의 만료까지 남은 시간. */
  Duration ttl();

  Optional<CharacterResult> find(String characterName);

  void save(String characterName, CharacterResult result);

  Optional<CharacterResult> findArmory(String characterName);

  void saveArmory(String characterName, CharacterResult result);

  Optional<CharacterSiblingsResult> findSiblings(String characterName);

  void saveSiblings(String characterName, CharacterSiblingsResult result);

  /** 공식 API가 최근(5분) 없다고 답한 이름인지(#108). 저장소 장애면 false라 정상 경로로 조회한다. */
  default boolean isMissing(String characterName) {
    return false;
  }

  /** 공식 API의 "없는 캐릭터" 응답을 짧게 기억한다. 타임아웃·429 등은 기록하지 않는다. 실패해도 예외를 던지지 않는다. */
  default void markMissing(String characterName) {}
}
