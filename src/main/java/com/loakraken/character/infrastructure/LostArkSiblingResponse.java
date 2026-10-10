package com.loakraken.character.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loakraken.character.application.CharacterErrorCode;
import com.loakraken.character.application.CharacterException;
import com.loakraken.character.domain.CharacterSibling;

@JsonIgnoreProperties(ignoreUnknown = true)
record LostArkSiblingResponse(
    @JsonProperty("CharacterName") String characterName,
    @JsonProperty("ServerName") String serverName,
    @JsonProperty("CharacterClassName") String className,
    @JsonProperty("CharacterLevel") Integer characterLevel,
    @JsonProperty("ItemAvgLevel") String itemLevel) {
  CharacterSibling toDomain() {
    if (characterName == null
        || characterName.isBlank()
        || serverName == null
        || serverName.isBlank()
        || className == null
        || className.isBlank()
        || itemLevel == null
        || itemLevel.isBlank()) throw new CharacterException(CharacterErrorCode.LOST_ARK_API_ERROR);
    return new CharacterSibling(
        characterName,
        serverName,
        className,
        characterLevel,
        LostArkProfileMapper.number(itemLevel));
  }
}
