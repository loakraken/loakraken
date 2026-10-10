package com.loakraken.character.application;

import java.text.Normalizer;

public record SearchCharacterCommand(String characterName, boolean includeSiblings) {
  public SearchCharacterCommand {
    if (characterName == null || characterName.isBlank()) {
      throw new IllegalArgumentException("캐릭터명이 필요합니다.");
    }
    characterName = Normalizer.normalize(characterName.strip(), Normalizer.Form.NFC);
  }

  public SearchCharacterCommand(String characterName) {
    this(characterName, true);
  }
}
