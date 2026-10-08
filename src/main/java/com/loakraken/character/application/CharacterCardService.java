package com.loakraken.character.application;

import com.loakraken.character.domain.CardCollection;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.CharacterSibling;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/** 원정대 카드 기록(F-6). 관측 이력 기능(character.history.enabled)을 따른다. */
@Service
public class CharacterCardService {
  private static final Logger log = LoggerFactory.getLogger(CharacterCardService.class);
  private final CharacterCardPort cards;
  private final CharacterHistoryPolicy policy;
  private final CharacterMetrics metrics;

  public CharacterCardService(
      CharacterCardPort cards, CharacterHistoryPolicy policy, CharacterMetrics metrics) {
    this.cards = cards;
    this.policy = policy;
    this.metrics = metrics;
  }

  /** 관측 이력을 남긴 뒤 호출한다. 카드와 포지션을 기록한다. 실패는 검색·수집을 막지 않는다. */
  public void record(CharacterDetails details, Instant observedAt) {
    if (!policy.enabled()) return;
    var loadoutCards = details.loadout().cards();
    var profile = details.profile();
    // 속성 칸(딜러·서포터)을 고르도록 사용자 검색에서도 포지션을 남긴다. 판정하지 못하면 기존 값을 유지한다.
    var position = CharacterPosition.judge(profile.className(), details.loadout().arkPassive());
    try {
      cards.record(
          profile.serverName(),
          profile.characterName(),
          loadoutCards == null ? List.of() : loadoutCards.items(),
          CardCollection.learn(loadoutCards),
          position.role() == null ? null : position.role().name(),
          observedAt);
      metrics.cards(true);
    } catch (DataAccessException | TransactionException exception) {
      metrics.cards(false);
      log.warn("카드 기록 저장에 실패했습니다. 결과는 그대로 반환합니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  /** siblings는 공식 원정대 목록이다. 같은 서버 캐릭터만 원정대로 본다. */
  public Collection collection(String characterName, List<CharacterSibling> siblings) {
    if (!policy.enabled())
      throw new CharacterException(CharacterErrorCode.CARD_COLLECTION_DISABLED);
    var self =
        siblings.stream()
            .filter(sibling -> characterName.equalsIgnoreCase(sibling.characterName()))
            .findFirst()
            .orElseThrow(() -> new CharacterException(CharacterErrorCode.CHARACTER_NOT_FOUND));
    String server = self.serverName();
    String name = self.characterName();
    var roster = new LinkedHashSet<String>();
    for (var sibling : siblings)
      if (server.equals(sibling.serverName()) && sibling.characterName() != null)
        roster.add(sibling.characterName());
    try {
      var owned = cards.rosterCards(server, roster);
      var cardNames = owned.cards().stream().map(CardCollection.OwnedCard::name).toList();
      var members = cards.members(cardNames);
      var setNames = new LinkedHashSet<String>();
      members.forEach(member -> setNames.add(member.setName()));
      var result = CardCollection.compute(owned.cards(), members, cards.effects(setNames));
      return new Collection(
          name,
          server,
          cards.position(server, name),
          roster.size(),
          owned.observedCharacters(),
          result);
    } catch (DataAccessException | TransactionException exception) {
      throw new CharacterException(CharacterErrorCode.CARD_COLLECTION_UNAVAILABLE);
    }
  }

  public record Collection(
      String characterName,
      String serverName,
      String position,
      int rosterCharacters,
      int observedCharacters,
      CardCollection.Result result) {}
}
