package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterProfile;

public interface CharacterProfilePort {
  CharacterProfile fetch(String characterName);
}
