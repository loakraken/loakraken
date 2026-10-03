package com.loakraken.character.application;

import java.time.Instant;

public interface CharacterSnapshotPort {
  void save(FetchedCharacter fetched, Instant fetchedAt);
}
