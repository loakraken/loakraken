package com.loakraken.character.application;

import com.loakraken.character.domain.CardCollection;
import com.loakraken.character.domain.CharacterLoadout;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CharacterCardPort {
  /** 캐릭터가 없으면(관측 이력에 아직 없으면) 아무것도 하지 않는다. 각성은 높아질 때만 바꾼다. position이 null이면 기존 포지션을 유지한다. */
  void record(
      String serverName,
      String characterName,
      List<CharacterLoadout.Card> cards,
      CardCollection.Learned learned,
      String position,
      Instant observedAt);

  /** 같은 서버의 원정대 캐릭터들이 기록한 카드. recordedBy는 그 최고 기록을 남긴 캐릭터다. */
  RosterCards rosterCards(String serverName, Collection<String> characterNames);

  List<CardCollection.SetMember> members(Collection<String> cardNames);

  List<CardCollection.SetEffect> effects(Collection<String> setNames);

  /** DEALER·SUPPORT 또는 null. */
  String position(String serverName, String characterName);

  record RosterCards(List<CardCollection.OwnedCard> cards, int observedCharacters) {}
}
