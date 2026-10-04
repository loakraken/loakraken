package com.loakraken.character.application;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CharacterSnapshotQueryService {
  private final CharacterSnapshotHistoryPort history;

  public CharacterSnapshotQueryService(CharacterSnapshotHistoryPort history) {
    this.history = history;
  }

  public CharacterSnapshotPage history(SnapshotHistoryQuery query) {
    try {
      return history.find(query);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_UNAVAILABLE);
    }
  }
}
