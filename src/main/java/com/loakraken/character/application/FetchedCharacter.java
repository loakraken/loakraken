package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterDetails;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** krakenScoreInput은 공식 armories에서 만든 크라켄 점수 엔진 입력(#559). 만들지 못했으면 null. */
public record FetchedCharacter(
    CharacterDetails details, String armoryJson, String siblingsJson, JsonNode krakenScoreInput) {
  public FetchedCharacter {
    Objects.requireNonNull(details);
    Objects.requireNonNull(armoryJson);
  }

  public FetchedCharacter(CharacterDetails details, String armoryJson, String siblingsJson) {
    this(details, armoryJson, siblingsJson, null);
  }
}
