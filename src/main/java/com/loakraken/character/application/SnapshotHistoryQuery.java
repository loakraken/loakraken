package com.loakraken.character.application;

import java.text.Normalizer;
import java.util.UUID;

public record SnapshotHistoryQuery(
    String characterName, String serverName, int limit, UUID cursor) {
  public SnapshotHistoryQuery {
    characterName = new SearchCharacterCommand(characterName).characterName();
    if (serverName == null || serverName.isBlank() || limit < 1 || limit > 50) {
      throw new IllegalArgumentException("서버명과 1~50 범위의 조회 개수가 필요합니다.");
    }
    serverName = Normalizer.normalize(serverName.strip(), Normalizer.Form.NFC);
  }
}
