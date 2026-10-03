package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterDetails;

public interface CharacterDetailsPort {
  CharacterDetails fetch(String characterName);
}
