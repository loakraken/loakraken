package com.loakraken.character.application;

import java.util.Optional;

public interface CharacterCache {
  Optional<CharacterResult> find(String characterName);

  void save(String characterName, CharacterResult result);
}
