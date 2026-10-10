package com.loakraken.character.application;

import com.loakraken.character.domain.CharacterProfile;
import java.time.Instant;
import java.util.List;

public interface CharacterHistoryPort {
  void record(CharacterProfile profile, Instant fetchedAt);

  List<CharacterObservationPoint> latest(String characterName, String serverName, int limit);

  List<CharacterTitleObservation> titles(String characterName, String serverName);

  /** 같은 서버 원정대 캐릭터들이 장착한 칭호. 칭호마다 가장 먼저 확인한 기록 하나(처음 확인 순). */
  List<RosterTitle> rosterTitles(String serverName, java.util.Collection<String> characterNames);

  /** 원정대 중 칭호 관측이 있는 캐릭터 수. */
  int titleObservedCharacters(String serverName, java.util.Collection<String> characterNames);

  record RosterTitle(
      String title,
      java.time.Instant firstObservedAt,
      java.time.Instant lastObservedAt,
      String recordedBy) {}
}
