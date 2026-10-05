package com.loakraken.character.application;

import java.text.Normalizer;

public record CharacterHistoryQuery(String characterName, String serverName, int limit) {
  public CharacterHistoryQuery {
    characterName = new SearchCharacterCommand(characterName).characterName();
    if (serverName == null || serverName.isBlank() || limit < 1 || limit > 500) {
      throw new IllegalArgumentException("서버명과 1~500 범위의 조회 개수가 필요합니다.");
    }
    serverName = Normalizer.normalize(serverName.strip(), Normalizer.Form.NFC);
  }
}
