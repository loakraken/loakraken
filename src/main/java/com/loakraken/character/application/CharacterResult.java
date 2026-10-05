package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterAnalysis;
import com.loakraken.character.domain.CharacterDetails;
import java.time.Instant;

/** refreshAvailableAt은 캐시 만료 예정 시각이다. 이전 캐시 값처럼 알 수 없으면 null이다. */
public record CharacterResult(
    CharacterDetails details, Instant fetchedAt, Instant refreshAvailableAt) {
  public CharacterResult(CharacterDetails details, Instant fetchedAt) {
    this(details, fetchedAt, null);
  }

  public CharacterAnalysis analysis() {
    return CharacterAnalysis.from(details.loadout());
  }
}
