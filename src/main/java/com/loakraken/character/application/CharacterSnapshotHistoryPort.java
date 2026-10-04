package com.loakraken.character.application;

public interface CharacterSnapshotHistoryPort {
  CharacterSnapshotPage find(SnapshotHistoryQuery query);
}
