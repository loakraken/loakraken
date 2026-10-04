package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterSnapshotSummary;
import java.util.List;
import java.util.UUID;

public record CharacterSnapshotPage(List<CharacterSnapshotSummary> items, UUID nextCursor) {
  public CharacterSnapshotPage {
    items = List.copyOf(items);
  }
}
