package com.loakraken.character.application;

public interface CharacterDetailsPort {
  FetchedCharacter fetch(String characterName);
}
