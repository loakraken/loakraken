package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSibling;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record CharacterSiblingsResult(
    String characterName,
    List<CharacterSibling> siblings,
    Instant fetchedAt,
    Instant refreshAvailableAt) {
  public CharacterSiblingsResult {
    Objects.requireNonNull(characterName);
    Objects.requireNonNull(fetchedAt);
    siblings = List.copyOf(siblings);
  }
}
