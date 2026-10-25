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
      Instant observedAt,
      Efficiency efficiency) {
    /** 같은 관측에 효율만 바꾼 값. */
    public Observation withEfficiency(Efficiency value) {
      return new Observation(
          serverName,
          characterName,
          cards,
          learned,
          position,
          gemMeanLevel,
          gemsObserved,
          observedAt,
          value);
    }

    /** 효율 없이(L38·L39 이전 호출). 효율 열은 바꾸지 않는다. */
    public Observation(
        String serverName,
        String characterName,
        List<CharacterLoadout.Card> cards,
        CardCollection.Learned learned,
        String position,
        BigDecimal gemMeanLevel,
        boolean gemsObserved,
        Instant observedAt) {
      this(
          serverName,
          characterName,
          cards,
          learned,
          position,
          gemMeanLevel,
          gemsObserved,
          observedAt,
          null);
    }
  }

  /**
   * 팔찌 효율·젬 효율(L38·L39 랭킹). 계산된(OK) 값만 있고, 못 하면 null이다. 규칙 버전은 값이 있을 때만 있다. 이 레코드가 null이면 저장된 효율 열을
   * 바꾸지 않는다(장비가 빈 관측은 모든 값이 null인 레코드로 비운다).
   */
  record Efficiency(
      BigDecimal braceletPct,
      String braceletRuleVersion,
      BigDecimal arkGridGemPct,
      String arkGridRuleVersion,
      BigDecimal braceletRealPct,
      String braceletRealRuleVersion,
      BigDecimal arkGridRealPct,
      String arkGridRealRuleVersion) {
    /** 실 효율 없이(전투력 기여 모델 값만). */
    public Efficiency(
        BigDecimal braceletPct,
        String braceletRuleVersion,
        BigDecimal arkGridGemPct,
        String arkGridRuleVersion) {
      this(
          braceletPct,
          braceletRuleVersion,
          arkGridGemPct,
          arkGridRuleVersion,
          null,
          null,
          null,
          null);
    }
  }

  record RosterCards(List<CardCollection.OwnedCard> cards, int observedCharacters) {}
}
