package com.loakraken.character.domain;

import java.util.List;
import java.util.Objects;

public record CharacterDetails(
    CharacterProfile profile, CharacterLoadout loadout, List<CharacterSibling> siblings) {
  public CharacterDetails {
    Objects.requireNonNull(profile);
    Objects.requireNonNull(loadout);
    siblings = List.copyOf(siblings);
  }
}
