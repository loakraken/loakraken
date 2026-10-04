package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterDetails;
import java.util.Objects;

public record FetchedCharacter(CharacterDetails details, String armoryJson, String siblingsJson) {
  public FetchedCharacter {
    Objects.requireNonNull(details);
    Objects.requireNonNull(armoryJson);
  }
}
