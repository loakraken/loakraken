package com.loakraken.character.application;

import java.util.Optional;

public interface CharacterCache {
  Optional<CharacterResult> find(String characterName);

  void save(String characterName, CharacterResult result);

  Optional<CharacterResult> findArmory(String characterName);

  void saveArmory(String characterName, CharacterResult result);

  Optional<CharacterSiblingsResult> findSiblings(String characterName);

  void saveSiblings(String characterName, CharacterSiblingsResult result);
}
