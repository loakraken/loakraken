package com.loakraken.character.application;

import com.loakraken.character.domain.CardCollection;
import com.loakraken.character.domain.CharacterDetails;
import com.loakraken.character.domain.CharacterLoadout;
import com.loakraken.character.domain.CharacterPosition;
import com.loakraken.character.domain.CharacterSibling;
import com.loakraken.character.domain.EquipmentSummary;
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
import tools.jackson.databind.JsonNode;

/**
 * 원정대 카드 기록(F-6), 포지션·4티어 보석 평균 레벨, 장비 요약 테이블(#146) 기록. 관측 이력 기능(character.history.enabled)을 따른다.
 *
 * <p>쓰기는 조회수(F-8)처럼 응답 경로 밖에서 단일 작업자가 비동기로 한다. 대기열이 차면 그 관측은 버리고 지표만 남긴다. 같은 캐릭터는 다음 관측에서 다시 기록된다.
 */
@Service
public class CharacterCardService {
  private static final Logger log = LoggerFactory.getLogger(CharacterCardService.class);
  private static final Pattern TIER4_GEM = Pattern.compile("(겁화|작열|광휘)의 보석");
  static final int QUEUE_CAPACITY = 1000;
  private final CharacterCardPort cards;
  private final EquipmentSummaryPort summaries;
  private final CharacterHistoryPolicy policy;
  private final CharacterMetrics metrics;
  private final CombatPowerSimulationService simulator;
  private final ArkGridEfficiencyService arkGrids;
  private final BraceletRealService braceletReals;
  private final ArkGridRealService arkGridReals;
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
      CharacterCardPort cards,
      EquipmentSummaryPort summaries,
      CharacterHistoryPolicy policy,
      CharacterMetrics metrics,
      CombatPowerSimulationService simulator,
      ArkGridEfficiencyService arkGrids,
      BraceletRealService braceletReals,
      ArkGridRealService arkGridReals) {
    this.cards = cards;
    this.summaries = summaries;
    this.policy = policy;
    this.metrics = metrics;
    this.simulator = simulator;
    this.arkGrids = arkGrids;
    this.braceletReals = braceletReals;
    this.arkGridReals = arkGridReals;
  }

  /**
   * 관측 이력을 남긴 뒤 호출한다. 실패는 검색·수집을 막지 않는다. krakenScoreInput은 같은 공식 조회로 만든 크라켄 점수 엔진 입력이다(없으면 null — 실
   * 효율만 비운다).
   */
  public void record(CharacterDetails details, JsonNode krakenScoreInput, Instant observedAt) {
    if (!policy.enabled()) return;
    var base = observe(details, observedAt);
    // 해석은 이미 한 도메인에서 뽑으므로 요청 스레드에서 해도 가볍다. 쓰기만 작업자에 넘긴다.
    var summary = EquipmentSummary.from(details.profile(), details.loadout());
    var collectibles = details.loadout().collectibles();
    var polishLines = polishLines(details.loadout());
    try {
      writer.execute(
          () -> {
            // 팔찌·젬 효율 재계산은 검색 응답 시간을 늘리지 않도록 작업자에서 한다(#443 성능 조건).
            var observation =
                base.withEfficiency(efficiency(details, krakenScoreInput, observedAt));
            write(observation);
            writeSummary(observation, summary);
            writeCollectibles(observation, collectibles);
            if (polishLines != null) writePolishLines(observation, polishLines);
          });
    } catch (RejectedExecutionException exception) {
      metrics.cards(false);
    }
  }

  /**
   * 팔찌 효율·젬 효율(L38·L39): 검색 응답과 같은 모델로 메모리에서 계산한다(공식 호출 없음). 장비를 관측하지 않았으면 null(저장값 유지), 계산이 실패하거나
   * OK가 아니면 그 값만 null이다(B8: 관측 저장은 그대로). 실 효율(팔찌·젬)은 캐릭터 응답의
   * braceletEfficiency.real·arkGridEfficiency.real과 같은 서비스·같은 입력으로 내서 랭킹 값이 캐릭터 화면 값과 같다.
   */
  CharacterCardPort.Efficiency efficiency(
      CharacterDetails details, JsonNode krakenScoreInput, Instant observedAt) {
    if (details.loadout().equipment().isEmpty()) return null;
    var result = new CharacterResult(details, observedAt, null, krakenScoreInput);
    java.math.BigDecimal bracelet = null;
    String braceletVersion = null;
    try {
      var value = simulator.braceletEfficiency(result);
      if (value != null
          && value.status() == CombatPowerSimulationService.Status.OK
          && value.percent() != null) {
        bracelet = value.percent();
        braceletVersion = value.ruleVersion();
      }
    } catch (RuntimeException exception) {
      log.warn("팔찌 효율을 저장용으로 계산하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
    java.math.BigDecimal gem = null;
    String gemVersion = null;
    try {
      var value = arkGrids.efficiency(result);
      // 젬 효율 = 젬 옵션 합계 줄(코어 제외, L39). 마지막 줄이고 slotIndex가 없다.
      var line = value == null || value.lines().isEmpty() ? null : value.lines().getLast();
      if (line != null
          && line.slotIndex() == null
          && line.status() == CombatPowerSimulationService.Status.OK
          && line.percent() != null) {
        gem = line.percent();
        gemVersion = value.ruleVersion();
      }
    } catch (RuntimeException exception) {
      log.warn("젬 효율을 저장용으로 계산하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
    BigDecimal braceletReal = null;
    try {
      var value = braceletReals.compute(result);
      if (value.status() == BraceletRealEfficiency.Status.OK) braceletReal = value.percent();
    } catch (RuntimeException exception) {
      log.warn("팔찌 실 효율을 저장용으로 계산하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
    BigDecimal gemReal = null;
    try {
      var value = arkGridReals.compute(details, krakenScoreInput);
      if (value.status() == ArkGridRealEfficiency.Status.OK) gemReal = value.percent();
    } catch (RuntimeException exception) {
      log.warn("젬 실 효율을 저장용으로 계산하지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
    return new CharacterCardPort.Efficiency(
        bracelet,
        braceletVersion,
        gem,
        gemVersion,
        braceletReal,
        braceletReal == null ? null : BraceletRealEfficiency.RULE_VERSION,
        gemReal,
        gemReal == null ? null : ArkGridRealEfficiency.RULE_VERSION);
  }

  static CharacterCardPort.Observation observe(CharacterDetails details, Instant observedAt) {
    return observe(details, observedAt, null);
  }

  static CharacterCardPort.Observation observe(
      CharacterDetails details, Instant observedAt, CharacterCardPort.Efficiency efficiency) {
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
        observedAt,
        efficiency);
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

  private static final List<String> ACCESSORY_SLOTS = List.of("목걸이", "귀걸이", "반지");

  private static final java.util.regex.Pattern TRADE_ITEM_LEVEL =
      java.util.regex.Pattern.compile("거래 제한 아이템 레벨\\s*([0-9][0-9,]{0,5})");

  /**
   * 장신구(목걸이·귀걸이·반지)의 연마 효과 줄. 장비를 관측하지 않았으면 null(저장된 줄을 바꾸지 않음). 공식 장신구 툴팁에는 '아이템 레벨 N' 줄이 없어 아이템
   * 레벨은 거래 가능한 장신구의 '거래 제한 아이템 레벨 N' 줄에서 읽는다. 그 줄이 없는 장신구(거래 불가)는 null.
   */
  public static List<CharacterCardPort.PolishLine> polishLines(CharacterLoadout loadout) {
    if (loadout.equipment().isEmpty()) return null;
    var lines = new java.util.ArrayList<CharacterCardPort.PolishLine>();
    for (var item : loadout.equipment()) {
      if (item == null || !ACCESSORY_SLOTS.contains(item.type()) || item.detail() == null) continue;
      var detail = item.detail();
      if (detail.polishEffects() == null) continue;
      var itemLevel = detail.itemLevel() != null ? detail.itemLevel() : tradeItemLevel(detail);
      for (var effect : detail.polishEffects()) {
        if (effect == null || effect.name() == null || effect.value() == null) continue;
        lines.add(
            new CharacterCardPort.PolishLine(
                item.type(),
                itemLevel,
                item.grade(),
                effect.name().strip(),
                effect.value().strip(),
                effect.grade() == null ? null : effect.grade().name()));
      }
    }
    return List.copyOf(lines);
  }

  private static Integer tradeItemLevel(CharacterLoadout.EquipmentDetail detail) {
    if (detail.tradeLines() == null) return null;
    for (var line : detail.tradeLines()) {
      if (line == null) continue;
      var matcher = TRADE_ITEM_LEVEL.matcher(line);
      if (matcher.find()) return Integer.valueOf(matcher.group(1).replace(",", ""));
    }
    return null;
  }

  private void writePolishLines(
      CharacterCardPort.Observation observation, List<CharacterCardPort.PolishLine> lines) {
    try {
      cards.recordPolishLines(
          observation.serverName(), observation.characterName(), lines, observation.observedAt());
    } catch (DataAccessException | TransactionException exception) {
      log.warn("연마 효과 줄 저장에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  private void writeCollectibles(
      CharacterCardPort.Observation observation, List<CharacterLoadout.Collectible> collectibles) {
    if (collectibles.isEmpty()) return;
    try {
      cards.recordCollectibles(
          observation.serverName(),
          observation.characterName(),
          collectibles,
          observation.observedAt());
    } catch (DataAccessException | TransactionException exception) {
      log.warn("수집품 기록 저장에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    }
  }

  private void writeSummary(CharacterCardPort.Observation observation, EquipmentSummary summary) {
    try {
      var outcome =
          summaries.record(
              observation.serverName(),
              observation.characterName(),
              summary,
              observation.observedAt());
      metrics.equipmentSummary(outcome.name());
    } catch (DataAccessException | TransactionException exception) {
      metrics.equipmentSummary("FAILED");
      log.warn("장비 요약 저장에 실패했습니다. 예외 유형={}", exception.getClass().getSimpleName());
    } catch (RuntimeException exception) {
      metrics.equipmentSummary("FAILED");
      log.warn("장비 요약을 만들지 못했습니다. 예외 유형={}", exception.getClass().getSimpleName());
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
