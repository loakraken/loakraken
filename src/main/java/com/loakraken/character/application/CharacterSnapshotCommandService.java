package com.loakraken.character.application;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class CharacterSnapshotCommandService {
  private final CharacterSnapshotPort snapshots;
  private final CharacterSnapshotPolicy policy;

  public CharacterSnapshotCommandService(
      CharacterSnapshotPort snapshots, CharacterSnapshotPolicy policy) {
    this.snapshots = snapshots;
    this.policy = policy;
  }

  public void save(FetchedCharacter fetched, Instant fetchedAt) {
    if (!policy.enabled()) return;
    snapshots.save(fetched, fetchedAt);
  }
}
