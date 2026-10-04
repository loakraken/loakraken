package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import java.util.List;

public interface CharacterDetailsPort {
  FetchedCharacter fetch(String characterName);

  FetchedCharacter fetchArmory(String characterName);

  List<CharacterSibling> fetchSiblings(String characterName);
}
