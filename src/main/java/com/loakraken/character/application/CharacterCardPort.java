package com.loakraken.character.application;

import com.loakraken.character.domain.CardCollection;
import com.loakraken.character.domain.CharacterLoadout;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface CharacterCardPort {
  /**
   * 캐릭터가 없으면(관측 이력에 아직 없으면) 아무것도 하지 않는다. 카드 각성은 높아질 때만 바꾼다. position이 null이면 기존 값을 유지한다. 보석 평균은
   * gemsObserved일 때만 바꾸고, 이때는 null(4티어 보석 없음)로도 덮는다.
   */
  void record(Observation observation);

  /**
   * 수집품(#295): 이 캐릭터의 원정대 값(공식 Point·MaxPoint)을 덮어쓴다. 수집품은 원정대가 함께 모은다. 캐릭터가 없거나 원정대 키가 아직 없으면(원정대를
   * 훑기 전) 아무것도 하지 않는다. 다음 관측에서 다시 기록된다.
   */
  void recordCollectibles(
      String serverName,
      String characterName,
      List<com.loakraken.character.domain.CharacterLoadout.Collectible> collectibles,
      Instant observedAt);

  /** 같은 서버의 원정대 캐릭터들이 기록한 카드. recordedBy는 그 최고 기록을 남긴 캐릭터다. */
  RosterCards rosterCards(String serverName, Collection<String> characterNames);

  List<CardCollection.SetMember> members(Collection<String> cardNames);

  List<CardCollection.SetEffect> effects(Collection<String> setNames);

  /** 같은 서버 캐릭터들의 마지막 프로필 관측 성공 시각. 관측한 적 없는 캐릭터는 빠진다. */
  Map<String, Instant> refreshedAt(String serverName, Collection<String> characterNames);

  /** DEALER·SUPPORT 또는 null. */
  String position(String serverName, String characterName);

  record Observation(
      String serverName,
      String characterName,
      List<CharacterLoadout.Card> cards,
      CardCollection.Learned learned,
      String position,
      BigDecimal gemMeanLevel,
      boolean gemsObserved,
      Instant observedAt) {}

  record RosterCards(List<CardCollection.OwnedCard> cards, int observedCharacters) {}
}
