package com.loakraken.character.application;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CharacterSnapshotQueryService {
  private final CharacterSnapshotHistoryPort history;
  private final CharacterSnapshotPolicy policy;

  public CharacterSnapshotQueryService(
      CharacterSnapshotHistoryPort history, CharacterSnapshotPolicy policy) {
    this.history = history;
    this.policy = policy;
  }

  public CharacterSnapshotPage history(SnapshotHistoryQuery query) {
    if (!policy.enabled()) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_DISABLED);
    }
    try {
      return history.find(query);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CHARACTER_HISTORY_UNAVAILABLE);
    }
  }
}
