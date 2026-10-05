package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterProfile;
import java.time.Instant;
import java.util.List;

public interface CharacterHistoryPort {
  void record(CharacterProfile profile, Instant fetchedAt);

  List<CharacterObservationPoint> latest(String characterName, String serverName, int limit);

  List<CharacterTitleObservation> titles(String characterName, String serverName);
}
