package com.loakraken.character.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterSnapshotCommandService {
  private final CharacterSnapshotPort snapshots;

  public CharacterSnapshotCommandService(CharacterSnapshotPort snapshots) {
    this.snapshots = snapshots;
  }

  @Transactional(timeout = 3)
  public void save(FetchedCharacter fetched, Instant fetchedAt) {
    snapshots.save(fetched, fetchedAt);
  }
}
