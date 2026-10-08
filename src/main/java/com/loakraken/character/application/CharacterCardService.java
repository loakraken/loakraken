package com.loakraken.character.application;

import com.loakraken.character.domain.CardCollection;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.CharacterSibling;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * 원정대 카드 기록(F-6)과 장비 요약(포지션, 4티어 보석 평균 레벨) 기록. 관측 이력 기능(character.history.enabled)을 따른다.
 *
 * <p>쓰기는 조회수(F-8)처럼 응답 경로 밖에서 단일 작업자가 비동기로 한다. 대기열이 차면 그 관측은 버리고 지표만 남긴다. 같은 캐릭터는 다음 관측에서 다시 기록된다.
 */
@Service
public class CharacterCardService {
  private static final Logger log = LoggerFactory.getLogger(CharacterCardService.class);
  private static final Pattern TIER4_GEM = Pattern.compile("(겁화|작열|광휘)의 보석");
  static final int QUEUE_CAPACITY = 1000;
  private final CharacterCardPort cards;
  private final CharacterHistoryPolicy policy;
  private final CharacterMetrics metrics;
  private final ThreadPoolExecutor writer =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          Thread.ofVirtual().name("character-cards-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  public CharacterCardService(
      CharacterCardPort cards, CharacterHistoryPolicy policy, CharacterMetrics metrics) {
    this.cards = cards;
    this.policy = policy;
    this.metrics = metrics;
  }

  /** 관측 이력을 남긴 뒤 호출한다. 실패는 검색·수집을 막지 않는다. */
  public void record(CharacterDetails details, Instant observedAt) {
    if (!policy.enabled()) return;
    var observation = observe(details, observedAt);
    try {
      writer.execute(() -> write(observation));
    } catch (RejectedExecutionException exception) {
      metrics.cards(false);
    }
  }

  static CharacterCardPort.Observation observe(CharacterDetails details, Instant observedAt) {
    var loadoutCards = details.loadout().cards();
    var profile = details.profile();
    // 속성 칸(딜러·서포터)과 통계 역할 필터를 위해 사용자 검색에서도 포지션을 남긴다. 판정하지 못하면 기존 값을 유지한다.
    var position = CharacterPosition.judge(profile.className(), details.loadout().arkPassive());
    return new CharacterCardPort.Observation(
        profile.serverName(),
        profile.characterName(),
        loadoutCards == null ? List.of() : loadoutCards.items(),
        CardCollection.learn(loadoutCards),
        position.role() == null ? null : position.role().name(),
        tier4GemMeanLevel(details.loadout().gems()),
        details.loadout().gems() != null,
        observedAt);
  }

  /** 4티어 보석(겁화·작열·광휘)의 평균 레벨. 없으면 null이고 통계에서 빠진다. */
  static BigDecimal tier4GemMeanLevel(CharacterLoadout.Gems gems) {
    if (gems == null) return null;
    int sum = 0;
    int count = 0;
    for (var gem : gems.items()) {
      if (gem == null || gem.level() == null || gem.level() <= 0 || gem.name() == null) continue;
      if (!TIER4_GEM.matcher(gem.name()).find()) continue;
      sum += gem.level();
      count++;
    }
    return count == 0
        ? null
        : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
  }

  private void write(CharacterCardPort.Observation observation) {
    try {
      cards.record(observation);
      metrics.cards(true);
    } catch (DataAccessException | TransactionException exception) {
      metrics.cards(false);
      log.warn("카드 기록 저장에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    writer.shutdown();
    writer.awaitTermination(5, TimeUnit.SECONDS);
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
