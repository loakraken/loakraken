package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterDetails;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * krakenScoreInput은 공식 armories에서 만든 크라켄 점수 엔진 입력(#559). 만들지 못했으면 null. shapeVerdict는 2부 대비 원본 형태
 * 검사에서 어긋난 HARD 규칙 id(빈 목록 = 오늘의 모양, null = 검사하지 못함).
 */
public record FetchedCharacter(
    CharacterDetails details,
    String armoryJson,
    String siblingsJson,
    JsonNode krakenScoreInput,
    List<String> shapeVerdict) {
  public FetchedCharacter {
    Objects.requireNonNull(details);
    Objects.requireNonNull(armoryJson);
    if (shapeVerdict != null) shapeVerdict = List.copyOf(shapeVerdict);
  }

  public FetchedCharacter(
      CharacterDetails details, String armoryJson, String siblingsJson, JsonNode krakenScoreInput) {
    this(details, armoryJson, siblingsJson, krakenScoreInput, null);
  }

  public FetchedCharacter(CharacterDetails details, String armoryJson, String siblingsJson) {
    this(details, armoryJson, siblingsJson, null, null);
  }
}
