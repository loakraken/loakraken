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
}
