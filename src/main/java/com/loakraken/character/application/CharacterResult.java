package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterAnalysis;
import com.loakraken.character.domain.CharacterDetails;
import java.time.Instant;

public record CharacterResult(CharacterDetails details, Instant fetchedAt) {
  public CharacterAnalysis analysis() {
    return CharacterAnalysis.from(details.loadout());
  }
}
